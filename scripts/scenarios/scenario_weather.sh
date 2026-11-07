#!/bin/bash
# 기상 동시 다발 시나리오 (03 rev2): 한두 대의 와이퍼로는 확정하지 않는다.
#   B 앞 200~500m에 가상 차량 W1~W8이 있다. 90초 안에 서로 다른 5대 이상이 새로 와이퍼를 켜고(꺼짐→켜짐),
#   그 지역 차량의 60% 이상이 같은 관측을 해야 "폭우 구간"으로 확정 → 뒤따르는 C(B 뒤 300m, 내비 화면)에 알림.
#   확정된 구간은 외부 제공 피드(GET /feed/weather)에 실리고, 와이퍼가 꺼지면 구간이 해제된다.
# 사용: scenario_weather.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
SL=$1
SHOT=${TMPDIR:-/tmp}/hyperconnect; mkdir -p "$SHOT"
FLEET="python3 -I $HC/scripts/fleet.py"
feed() { curl -s localhost:8080/feed/weather | python3 -c "
import sys,json
z=json.load(sys.stdin)['zones']
print('   외부 피드:', [(i['hazard'], i['status'], i['observing_vehicles'], i['observing_share']) for i in z] or '없음')"; }
bash $S/stop_fleet.sh
python3 -I $HC/scripts/vc.py --port 8088 wiper Off >/dev/null
NAV_FRONT="B C" bash $S/launch_bc.sh
$FLEET run --anchor B W1:1:+200:50 W2:2:+250:50 W3:3:+300:50 W4:1:+350:50 W5:2:+400:50 W6:3:+450:50 W7:1:+480:50 W8:2:+500:50 \
  > "$SHOT/fleet.log" 2>&1 &
for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p drive 50 >/dev/null; done   # B·C 함께 50km/h로 주행
sleep 3
N=$(wc -l < $SL)
echo "--- 1) W1 혼자 와이퍼 (세차·워셔액일 수 있음) → 확정 안 됨"
$FLEET weather W1 --rain 3 >/dev/null; sleep 4
echo "--- 2) W2, W3도 켬 → 3대, 아직 부족"
$FLEET weather W2 --rain 3 >/dev/null; sleep 1; $FLEET weather W3 --rain 3 >/dev/null; sleep 4
echo "--- 3) W4, W5와 B(실제 차량 신호)까지 거의 동시에 → 6대·지역 67% → 폭우 구간 확정"
$FLEET weather W4 --rain 3 >/dev/null; sleep 1; $FLEET weather W5 --rain 3 >/dev/null; sleep 1
bash $S/mark.sh in
python3 -I $HC/scripts/vc.py --port 8088 wiper High >/dev/null
bash $S/wait_log.sh emulator-5556 "경고 수신" 20; sleep 5
bash $S/mark.sh out
$ADB -s emulator-5556 exec-out screencap -p > "$SHOT/weather_c.png"
feed
sleep 6
echo "--- 4) 비가 그쳐 와이퍼를 끔 → 구간 해제 (피드에 ended)"
for w in W1 W2 W3 W4 W5; do $FLEET weather $w --rain 0 >/dev/null; done
bash $S/mark.sh in
python3 -I $HC/scripts/vc.py --port 8088 wiper Off >/dev/null
sleep 4
bash $S/mark.sh out
feed
echo "== server"; tail -n +$((N+1)) $SL | grep -E "\[weather\]" | head -10
echo "== C"; $ADB -s emulator-5556 logcat -d | grep -E "경고 수신" | tail -2
bash $S/stop_fleet.sh
