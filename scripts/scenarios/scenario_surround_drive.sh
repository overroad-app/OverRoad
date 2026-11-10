#!/bin/bash
# 10 연결망 시각화 · 주행 상황 (상위 티어 SVS 미리보기: '주행 중에도 연결망 화면 유지'를 켠 상태)
#   B(2차로)가 60km/h로 주행하며 OverRoad 전체 화면을 본다. C는 2차로 35m 뒤.
#   - 3차로 차가 왼쪽 깜빡이를 켜고 B 앞으로 끼어든다 (옆으로 미끄러지듯)
#   - 앞차가 급제동(브레이크등 + 급제동 보고) → B에 OS 상단 알림, B도 감속했다가 다시 가속
#   - 구급차가 1차로 뒤에서 다가오면 1차로 앞차가 2차로로 비켜 주고, 구급차가 지나간다
# 사용: scenario_surround_drive.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
SHOT=${TMPDIR:-/tmp}/hyperconnect
mkdir -p "$SHOT"
FLEET="python3 -I $HC/scripts/fleet.py"
VC="python3 -I $HC/scripts/vc.py"

bash $S/stop_fleet.sh
C_GAP=35 bash $S/launch_bc.sh --ez driving_view true
$ADB -s emulator-5554 shell am start -n com.hyperconnect.app/.MainActivity >/dev/null 2>&1; sleep 2
bash $S/tap.sh emulator-5554 "자세히"; sleep 1
bash $S/tap.sh emulator-5554 "자세히"; sleep 1          # 출발 전에 전체 화면으로 (켜 둔 미리보기 덕분에 주행해도 유지)
$FLEET run --anchor B \
  L1:2:+55:60:speed=25@7,speed=60@11 M1:3:+12:60:turn=L@2,lane=2@3 N1:1:+90:60 \
  R1:3:-40:62 AMB:1:-260:100:role=ambulance > "$SHOT/fleet.log" 2>&1 &
for p in 8088 8089; do $VC --port $p drive 60 >/dev/null; done
sleep 1
bash $S/mark.sh in
sleep 6.5
$FLEET report L1 hard_brake >/dev/null                   # 앞차 급제동 → 브레이크등 + 알림
for v in 45 30; do $VC --port 8088 drive $v >/dev/null; $VC --port 8089 drive $v >/dev/null; sleep 0.8; done
sleep 2.5
for v in 45 60; do $VC --port 8088 drive $v >/dev/null; $VC --port 8089 drive $v >/dev/null; sleep 1; done
sleep 7
# 구급차가 지나가면 길을 열어 준 차에게 '긴급차량 감사'가 온다 → 그 👍가 선을 타고 들어오는 것까지 담는다
bash $S/wait_log.sh emulator-5554 "길을 열어 줘서" 25; sleep 3
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/surround_drive_b.png"
bash $S/mark.sh out
$ADB -s emulator-5554 logcat -d | grep -E "경고 수신|주행 시작" | sed 's/.*HyperConnect: //' | tail -3
grep -E "양보|차로|목표|깜빡이" "$SHOT/fleet.log" | head -8
bash $S/tap.sh emulator-5554 "접기"
bash $S/stop_fleet.sh
