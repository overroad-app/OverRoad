#!/bin/bash
# 차로 변경 시나리오: B(2차로)가 왼쪽 깜빡이를 켜고 1차로로 진입한다. C는 1차로 B 뒤 30m에서 더 빠르게 다가온다.
#   기대: B "왼쪽 차로 뒤 Xm 접근 · 진입 주의", C "옆 차량이 내 차로로 진입하려 해요"
#         진입 성공 → 자동 감사 인사 → C는 주행 중이라 소리만, 정차하면 "주행 중 받은 이모트 👍"
#   제외 확인: A(1차로 앞), D(3차로), F(1차로 200m 뒤)는 알림을 받지 않아야 한다
# 사용: scenario_lane.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
APK=$HC/app/build/outputs/apk/debug/app-debug.apk
SL=$1
SHOT=${TMPDIR:-/tmp}/hyperconnect  # 화면 캡처는 저장소 밖에 둔다
mkdir -p "$SHOT"

bash $S/stop_fleet.sh
for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p park >/dev/null; python3 -I $HC/scripts/vc.py --port $p turn off >/dev/null; done
for d in emulator-5554 emulator-5556; do
  $ADB -s $d install -r $APK >/dev/null
  $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.car.permission.CAR_SPEED
  $ADB -s $d logcat -c
done
$ADB -s emulator-5554 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id B --ei lane 2 --ed offset_m 0 >/dev/null
$ADB -s emulator-5556 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id C --ei lane 1 --ed offset_m -30 >/dev/null
for i in $(seq 1 40); do
  curl -s localhost:8080/status | python3 -c "
import sys,json
v=json.load(sys.stdin)['vehicles']
sys.exit(0 if {'B','C'}<=set(v) and abs(v['B']['lat']-37.4127212)<2e-5 and abs((v['C']['lat']-v['B']['lat'])*111320+30)<2 else 1)" && break
  sleep 1
done
python3 -I $HC/scripts/fleet.py run --anchor B A:1:+20:20 D:3:-20:40 F:1:-200:60 > "$SHOT/fleet.log" 2>&1 &
sleep 3
N=$(wc -l < $SL)
echo "--- B 20km/h, C 40km/h 주행"
python3 -I $HC/scripts/vc.py --port 8088 drive 20 >/dev/null
python3 -I $HC/scripts/vc.py --port 8089 drive 40 >/dev/null
sleep 1
echo "--- B 왼쪽 깜빡이"
python3 -I $HC/scripts/vc.py --port 8088 turn left >/dev/null
sleep 3
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/lane_b.png"
$ADB -s emulator-5556 exec-out screencap -p > "$SHOT/lane_c.png"
echo "--- B 1차로로 이동"
$ADB -s emulator-5554 shell am broadcast --user 10 -a com.hyperconnect.app.SET_LANE --ei lane 1 >/dev/null
python3 -I $HC/scripts/vc.py --port 8088 turn off >/dev/null
sleep 8
echo "--- C 정차"
python3 -I $HC/scripts/vc.py --port 8089 park >/dev/null
python3 -I $HC/scripts/vc.py --port 8088 park >/dev/null
sleep 3
$ADB -s emulator-5556 exec-out screencap -p > "$SHOT/lane_c_parked.png"
echo "== server"; tail -n +$((N+1)) $SL | grep -E '\[lane\]|\[emote\]'
echo "== B"; $ADB -s emulator-5554 logcat -d | grep -E '차로 변경 알림|마지막 이모트' | tail -3
echo "== C"; $ADB -s emulator-5556 logcat -d | grep -E '차로 변경 알림|이모트 (수신|보류)|정차 후' | tail -5
echo "== 가상 차량"; grep -E 'lane|이모트' "$SHOT/fleet.log" || echo "(알림 없음)"
bash $S/stop_fleet.sh
