#!/bin/bash
# 09 연결망 시각화 · 정지 상황: 신호 대기로 멈춘 B(2차로) 주변
#   - 2·3차로에 신호 대기 줄, 3차로로 다가오던 차들이 감속해(브레이크등) 줄 뒤에 선다
#   - 1차로 차가 깜빡이를 켜고 2차로 줄 뒤로 들어온다 (옆으로 미끄러지듯)
#   - 구급차가 비어 있는 1차로로 지나간다 (다른 차를 뚫고 지나가지 않는다)
#   - 경고는 앱이 앞에 있어도 OS 상단 알림
# 사용: scenario_surround.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
SHOT=${TMPDIR:-/tmp}/hyperconnect
mkdir -p "$SHOT"
FLEET="python3 -I $HC/scripts/fleet.py"

bash $S/stop_fleet.sh
C_GAP=9 bash $S/launch_bc.sh --ez driving_view false     # C는 B 바로 뒤에 함께 신호 대기
for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p stop >/dev/null; done   # D 기어, 0km/h
$FLEET run --anchor B \
  Q1:2:+9:0 Q2:2:+17:0 Q3:2:+25:0 Q5:3:+11:0 Q6:3:+19:0 \
  P1:3:-90:40 P3:3:-150:45 M1:1:-60:30:turn=R@2,lane=2@3 AMB:1:-230:55:role=ambulance > "$SHOT/fleet.log" 2>&1 &
sleep 2
$ADB -s emulator-5554 shell am start -n com.hyperconnect.app/.MainActivity >/dev/null 2>&1; sleep 2
bash $S/tap.sh emulator-5554 "자세히"; sleep 1
bash $S/tap.sh emulator-5554 "자세히"; sleep 1.5      # 정차 중이라 전체 화면 → 배경에 연결망 시각화
bash $S/mark.sh in -0.5
sleep 13
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/surround_stop_b.png"
bash $S/mark.sh out
curl -s "localhost:8080/surroundings?vehicle=B" | python3 -c "import sys,json; d=json.load(sys.stdin); print('주변', len(d['vehicles']), '대')"
grep -E "양보|차로|목표" "$SHOT/fleet.log" | head -8
bash $S/tap.sh emulator-5554 "접기"
bash $S/stop_fleet.sh
