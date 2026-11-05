#!/bin/bash
# EV 충전 예측 rev2 시나리오
#   판교역 충전소(충전기 2기): HyperConnect 차량 C가 충전 중(SoC 35%), 타사 차량 1대가 12분째 충전 중(공공 충전기 상태),
#   가상 EV Q3도 같은 충전소로 오는 중. B가 이 충전소로 경로를 잡으면
#     → 충전소 별점·출력, 도착 시각, 내 순번, 대기 시간, 80% 완료 시각, 충전 중인 차(타사 포함), 대안 충전소
#   C의 충전이 끝나면 그 기록(시간·실측 출력)이 충전소 정보가 되고, C가 별점을 남긴다 → B의 카드가 바로 갱신된다
#   (시연에서는 충전 시간을 60배 빠르게 흘린다: 실제 약 20분 → 20초)
# 사용: scenario_ev.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
APK=$HC/app/build/outputs/apk/debug/app-debug.apk
SL=$1
SHOT=${TMPDIR:-/tmp}/hyperconnect; mkdir -p "$SHOT"
VC="python3 -I $HC/scripts/vc.py"
api() { curl -s -X POST "localhost:8080$1" -H 'Content-Type: application/json' -d "$2" >/dev/null; }
PANGYO="--ed fix_lat 37.4020 --ed fix_lon 127.1080"

bash $S/stop_fleet.sh
N=$(wc -l < $SL)
api /demo '{"charge_time_scale": 60}'
# 공공 충전기 상태 (환경공단 충전기 상태 API 대체): 판교역은 타사 차량 1대가 12분째 충전 중, 시간당 1.5대 도착
api /public_status '{"station": "PANGYO", "busy_since_min_ago": [12], "arrivals_per_hour": 1.5}'
api /public_status '{"station": "SIHEUNG", "busy_since_min_ago": [], "arrivals_per_hour": 0.5}'

for p in 8088 8089; do $VC --port $p park >/dev/null; $VC --port $p charge off >/dev/null; done
$VC --port 8089 soc 35 >/dev/null
$VC --port 8088 soc 30 >/dev/null
for d in emulator-5554 emulator-5556; do
  $ADB -s $d install -r $APK >/dev/null
  $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.car.permission.CAR_SPEED
  $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.permission.POST_NOTIFICATIONS
  $ADB -s $d logcat -c
done
echo "--- C: 판교역 충전소에서 충전 시작 (SoC 35%)"
$ADB -s emulator-5556 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id C --ei lane 2 $PANGYO >/dev/null
sleep 5
$VC --port 8089 charge on >/dev/null
echo "--- B: 판교역 충전소로 경로 설정 (SoC 30%)"
$ADB -s emulator-5554 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id B --ei lane 2 \
  --ed dest_lat 37.4020 --ed dest_lon 127.1080 >/dev/null
for i in $(seq 1 30); do
  curl -s localhost:8080/status | python3 -c "import sys,json; sys.exit(0 if 'B' in json.load(sys.stdin)['vehicles'] else 1)" && break
  sleep 1
done
python3 -I $HC/scripts/fleet.py run --anchor B Q3:2:+300:60:dest=PANGYO:eta=120:soc=25 > "$SHOT/fleet.log" 2>&1 &
sleep 4
# 경로를 잡으면 내비가 앞으로 나온다. 우리 앱을 다시 앞으로 가져와 충전 예측 카드를 보여준다
$ADB -s emulator-5554 shell am start -n com.hyperconnect.app/.MainActivity >/dev/null
sleep 10
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/ev2_b_before.png"

echo "--- C 충전 진행 35% → 80% (60배속)"
for soc in 44 53 62 71 80; do $VC --port 8089 soc $soc >/dev/null; sleep 4; done
$VC --port 8089 charge off >/dev/null
sleep 5
$ADB -s emulator-5556 exec-out screencap -p > "$SHOT/ev2_c_done.png"
echo "--- C: 별점 ★4"
bash $S/tap.sh emulator-5556 "★4"
sleep 10
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/ev2_b_after.png"

echo "== server"; tail -n +$((N+1)) $SL | grep -E "\[charg|\[rating\]" | head -12
echo "== B"; $ADB -s emulator-5554 logcat -d | grep -E "충전 예측" | sed 's/.*HyperConnect: //' | tail -3
echo "== C"; $ADB -s emulator-5556 logcat -d | grep -E "충전 완료|별점" | sed 's/.*HyperConnect: //' | tail -3

# 정리: 경로 안내 종료, 시연 설정 원복, 공공 상태 비움
$ADB -s emulator-5554 shell am broadcast --user 10 -a com.hyperconnect.app.CANCEL_ROUTE >/dev/null
api /demo '{"charge_time_scale": 1}'
api /public_status '{"station": "PANGYO", "busy_since_min_ago": [], "arrivals_per_hour": 0}'
api /public_status '{"station": "SIHEUNG", "busy_since_min_ago": [], "arrivals_per_hour": 0}'
bash $S/stop_fleet.sh
