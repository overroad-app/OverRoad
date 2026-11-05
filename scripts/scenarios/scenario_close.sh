#!/bin/bash
# 2차 사고 방지 (02 rev2, 옛 04 정체·사고 구간을 통합)
#   1000m 앞 X1이 사고(에어백) → 그 뒤로 9대가 5km/h로 막혀 정체가 생긴다 (700~940m).
#   B는 멀리서 "사고 발생"과 "700m부터 사고 정체 · 길이 240m" 카드를 받고, 달리기 시작하면 앱이 뒤로 물러나 내비가 보인다.
#   2차 사고가 나는 곳은 사고 지점이 아니라 정체 꼬리다 → 꼬리 300m 안으로 들어오면 빨간 경고 "사고 정체 끝, 감속하세요".
#   사고 증거가 없는 단순 정체는 알리지 않는다 (내비 실시간 교통이 이미 하는 일).
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
SHOT=${TMPDIR:-/tmp}/hyperconnect  # 화면 캡처·로그는 저장소 밖에 둔다
mkdir -p "$SHOT"
SL=$1
bash $S/stop_fleet.sh; bash $S/launch_bc.sh
# B는 내비를 띄운 뒤 앱을 다시 앞으로 (정차 중 앱 화면 → 출발하면 내비로)
bash $S/nav_front.sh emulator-5554
$ADB -s emulator-5554 shell am start -n com.hyperconnect.app/.MainActivity >/dev/null; sleep 2
N=$(wc -l < $SL)
python3 -I $HC/scripts/fleet.py run --anchor B --jam +700:9:5 X1:2:+1000:0 > "$SHOT/fleet.log" 2>&1 &
sleep 3
python3 -I $HC/scripts/fleet.py report X1 crash >/dev/null; sleep 4
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/close_far.png"
echo "== 멀리서 (B 정차)"; $ADB -s emulator-5554 logcat -d | grep -E '구간 알림|경고 수신' | sed 's/.*HyperConnect: //' | tail -3
python3 -I $HC/scripts/vc.py --port 8088 drive 60 >/dev/null
sleep 32
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/close_near.png"
echo "== 정체 꼬리 접근 (B 60km/h, 내비 화면)"; $ADB -s emulator-5554 logcat -d | grep -E '구간 알림|경고 수신|주행 시작' | sed 's/.*HyperConnect: //' | tail -3
python3 -I $HC/scripts/vc.py --port 8088 park >/dev/null
echo "== server"; tail -n +$((N+1)) $SL | grep -E '\[zone\]|\[close\]' | head -6
bash $S/stop_fleet.sh
