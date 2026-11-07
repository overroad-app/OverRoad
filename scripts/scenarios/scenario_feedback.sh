#!/bin/bash
# 급제동 경고 시나리오 (모두 주행 중): B·C가 같은 2차로를 80km/h로 달린다. C는 B 뒤 200m.
#   1) B 급제동(80 → 20km/h) → 10초 안에 닿는 같은 차로 뒤차 C에만 경고 (OS 상단 알림: 브레이크등 아이콘 + 초록 체크)
#      800m 뒤를 같이 달리는 E는 도달 거리 밖이라 받지 않는다
#   2) B는 위험을 피한 뒤 다시 60km/h로, C는 경고를 듣고 50km/h로 줄였다가 다시 주행 (아무도 멈추지 않는다)
#   보고 기록은 서버 /stats의 history로 확인 (설정의 '내 기여'에 같은 내용이 보인다)
# 사용: scenario_feedback.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
SL=$1
SHOT=${TMPDIR:-/tmp}/hyperconnect
mkdir -p "$SHOT"
VC="python3 -I $HC/scripts/vc.py"

bash $S/stop_fleet.sh
NAV_FRONT="B C" C_GAP=200 bash $S/launch_bc.sh
curl -s -X POST localhost:8080/demo -H 'Content-Type: application/json' -d '{"reset": true, "reset_stats": true}' >/dev/null
python3 -I $HC/scripts/fleet.py run --anchor B E:2:-800:80 > "$SHOT/fleet.log" 2>&1 &
$VC --port 8089 drive 80 >/dev/null
sleep 3
N=$(wc -l < $SL)

echo "--- B 급제동 (주행 중)"
bash $HC/scripts/hard_brake.sh >/dev/null &
HB=$!
bash $S/wait_log.sh emulator-5556 "경고 수신" 25; bash $S/mark.sh in -1
for v in 70 60 50; do $VC --port 8089 drive $v >/dev/null; sleep 0.6; done   # C는 경고를 듣고 속도를 줄인다
sleep 3; bash $S/mark.sh out
$ADB -s emulator-5556 exec-out screencap -p > "$SHOT/hard_brake_c.png"
wait $HB   # 급제동 스크립트만 기다린다 (가상 차량 시뮬레이터는 계속 돈다)
$VC --port 8089 drive 60 >/dev/null
echo "B 보고 기록: $(curl -s 'localhost:8080/stats?vehicle=B' | python3 -c 'import sys,json; print(json.load(sys.stdin)["history"])')"
echo "== server"; tail -n +$((N+1)) $SL | grep -E 'hard_brake|급제동|    E:|    C:' | head -8
echo "== C"; $ADB -s emulator-5556 logcat -d | grep -E '경고 수신' | tail -2
bash $S/stop_fleet.sh
