#!/bin/bash
# 기상 합의 시나리오: B의 와이퍼(실제 차량 신호)와 앞쪽 가상 차량 W1의 폭우 관측이 같은 구간에서 겹치면
#   "폭우 구간"으로 확정되어 뒤따르는 C(B 뒤 300m)에 경고가 간다. 한 대만으로는 확정되지 않는다.
# 사용: scenario_weather.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
SL=$1
SHOT=${TMPDIR:-/tmp}/hyperconnect; mkdir -p "$SHOT"
bash $S/stop_fleet.sh
python3 -I $HC/scripts/vc.py --port 8088 wiper Off >/dev/null
bash $S/launch_bc.sh
python3 -I $HC/scripts/fleet.py run --anchor B W1:2:+200:0 > "$SHOT/fleet.log" 2>&1 &
sleep 3
N=$(wc -l < $SL)
echo "--- 1) 가상 차량 W1 혼자 폭우 관측 → 확정 안 됨"
python3 -I $HC/scripts/fleet.py weather W1 --rain 3
sleep 4
echo "--- 2) B 와이퍼 High (실제 차량 신호) → 2대 합의로 확정"
python3 -I $HC/scripts/vc.py --port 8088 wiper High >/dev/null
sleep 8
python3 -I $HC/scripts/vc.py --port 8088 wiper Off >/dev/null
echo "== server"; tail -n +$((N+1)) $SL | grep -E "weather|heavy_rain|합의" | head -8
echo "== C"; $ADB -s emulator-5556 logcat -d | grep -E "경고 수신" | tail -2
bash $S/stop_fleet.sh
