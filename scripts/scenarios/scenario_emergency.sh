#!/bin/bash
# 긴급차량 시나리오 (rev2): 경찰차·소방차·구급차가 2차로 뒤에서 90km/h로 C, B를 차례로 추월한다. 옆 1차로 D도 있다.
#   역할마다 카드 색·아이콘·문구·사이렌음이 다르고, 처음 다가올 때 음성으로 한 번 알린다.
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
SHOT=${TMPDIR:-/tmp}/hyperconnect  # 화면 캡처·로그는 저장소 밖에 둔다
mkdir -p "$SHOT"
SL=$1  # 서버 로그 파일

bash $S/stop_fleet.sh
(cd $HC && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain -q 2>&1 | grep -E '^e:')
NAV_FRONT=C bash $S/launch_bc.sh
N=$(wc -l < $SL)
# rev2: 경찰차·소방차·구급차가 차례로 지나간다 (역할별 카드·사이렌음·음성). B는 앱 화면, C는 내비 위 상단 알림
python3 -I $HC/scripts/fleet.py run --anchor B POL:2:-700:90:role=police FIRE:2:-1500:90:role=fire \
  AMB:2:-2300:90:role=ambulance D:1:-150 > "$SHOT/fleet.log" 2>&1 &
sleep 8
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/em_police_b.png"
$ADB -s emulator-5556 exec-out screencap -p > "$SHOT/em_police_c.png"
sleep 30
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/em_fire_b.png"
sleep 34
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/em_amb_b.png"
sleep 30
tail -n +$((N+1)) $SL | grep emergency
echo "== B"; $ADB -s emulator-5554 logcat -d | grep '긴급차량' | tail -6
echo "== C"; $ADB -s emulator-5556 logcat -d | grep '긴급차량' | tail -6
bash $S/stop_fleet.sh
