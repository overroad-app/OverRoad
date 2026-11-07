#!/bin/bash
# 11 이모트 상점 시나리오 (정차 중 전체 화면에서만 열린다)
#   1) B: 상점 열기 → 특별 지급 구역 → '출고 기념' 장착 → 유료 팩 구매는 "결제 SDK 출시 후" 안내
#   2) B가 뒤차 C에게 고마워요 → C는 '출고 기념' 팩 소리·그림으로 받는다 (받는 쪽은 보낸 차의 팩으로)
#   3) 특별 지급 '긴급차량 감사': 1차로로 지나간 구급차가 길을 열어 준 B·C에게 자동으로 고마움을 전한다
# 사용: scenario_store.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
SHOT=${TMPDIR:-/tmp}/hyperconnect
mkdir -p "$SHOT"
T="bash $S/tap.sh emulator-5554"
M="bash $S/mark.sh"

bash $S/stop_fleet.sh
C_GAP=60 bash $S/launch_bc.sh --es emote_pack basic --ez driving_view false
for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p stop >/dev/null; done   # 신호 대기 (D, 0km/h)
$ADB -s emulator-5556 shell am start -n com.hyperconnect.app/.MainActivity >/dev/null 2>&1
$ADB -s emulator-5554 shell am start -n com.hyperconnect.app/.MainActivity >/dev/null 2>&1; sleep 2
for i in 1 2 3 4 5; do   # 전체 화면('접기')이 될 때까지 (정차 판정이 잡힌 뒤에만 전체 화면이 열린다)
  $ADB -s emulator-5554 shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  $ADB -s emulator-5554 exec-out cat /sdcard/ui.xml | grep -q 'text="접기"' && break
  $T "자세히"; sleep 1.5
done
echo "--- 1) 상점"
$T "상점"; sleep 2; $M in -1.5
$T "장착"; sleep 2            # 첫 '장착' 버튼 = 출고 기념 (기본은 장착 중)
$T "구매"; sleep 3            # 결제 SDK 출시 후 안내
$M out
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/store_b.png"
$T "닫기"; sleep 1
echo "--- 2) 출고 기념 팩으로 고마워요"
$T "뒤차에 고마워요"; $M in -0.3; sleep 3.5; $M out
$ADB -s emulator-5556 logcat -d | grep "이모트 수신" | tail -1 | sed 's/.*HyperConnect: //'
echo "--- 3) 구급차 통과 → 긴급차량 감사"
python3 -I $HC/scripts/fleet.py run --anchor B AMB:1:-200:60:role=ambulance > "$SHOT/fleet.log" 2>&1 &
bash $S/wait_log.sh emulator-5556 "길을 열어 줘서" 60; $M in -1; sleep 4; $M out
$ADB -s emulator-5556 exec-out screencap -p > "$SHOT/store_c_emergency.png"
$ADB -s emulator-5556 logcat -d | grep "이모트 수신" | tail -2 | sed 's/.*HyperConnect: //'
$T "접기"
bash $S/stop_fleet.sh
