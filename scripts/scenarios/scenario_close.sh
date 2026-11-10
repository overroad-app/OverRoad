#!/bin/bash
# 2차 사고 방지 (02 rev3: 처음부터 주행 중)
#   B는 2차로를 60km/h로 달리며 내비를 보고 있다. 1000m 앞 X1이 사고(에어백) → 그 뒤로 9대가 막혀 정체가 생긴다 (약 700~940m).
#   1) 사고가 나자마자 멀리서 "사고 발생" · "700m부터 사고 정체" 안내 (내비 위 상단 알림)
#   2) 2차 사고가 나는 곳은 사고 지점이 아니라 정체 꼬리 → 꼬리 300m 안으로 들어오면 "사고 정체 구간, 주의하세요"
#   3) B는 경고를 듣고 서서히 감속해 정체 흐름에 합류 (멈추지 않는다)
#   사고 증거가 없는 단순 정체는 알리지 않는다 (내비 실시간 교통이 이미 하는 일).
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
SHOT=${TMPDIR:-/tmp}/hyperconnect  # 화면 캡처·로그는 저장소 밖에 둔다
mkdir -p "$SHOT"
SL=$1
bash $S/stop_fleet.sh
NAV_FRONT=B bash $S/launch_bc.sh
N=$(wc -l < $SL)
python3 -I $HC/scripts/fleet.py run --anchor B --jam +700:9:5 X1:2:+1000:0 > "$SHOT/fleet.log" 2>&1 &
python3 -I $HC/scripts/vc.py --port 8088 drive 60 >/dev/null     # 처음부터 60km/h로 주행
sleep 4
python3 -I $HC/scripts/fleet.py report X1 crash >/dev/null
bash $S/wait_log.sh emulator-5554 "구간 알림" 15; bash $S/mark.sh in -1; sleep 5; bash $S/mark.sh out
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/close_far.png"
echo "== 멀리서 (B 60km/h 주행 중)"; $ADB -s emulator-5554 logcat -d | grep -E '구간 알림|경고 수신' | sed 's/.*HyperConnect: //' | tail -3
bash $S/wait_log.sh emulator-5554 "사고 정체 구간, 주의" 60; bash $S/mark.sh in -0.5
sleep 3
for v in 45 30 20 10; do python3 -I $HC/scripts/vc.py --port 8088 drive $v >/dev/null; sleep 0.8; done   # 정체 꼬리를 보고 서서히 감속해 합류
sleep 3.5   # 경고 음성("사고 정체 구간, 주의하세요")이 끝날 때까지
bash $S/mark.sh out
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/close_near.png"
echo "== 정체 꼬리 접근 (내비 화면)"; $ADB -s emulator-5554 logcat -d | grep -E '구간 알림|경고 수신' | sed 's/.*HyperConnect: //' | tail -3
echo "== server"; tail -n +$((N+1)) $SL | grep -E '\[zone\]|\[close\]' | head -6
bash $S/stop_fleet.sh
