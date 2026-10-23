#!/bin/bash
# 먼 사고 → 구간 카드, 300m 안으로 접근 → 빨간 경고
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
bash $S/stop_fleet.sh; bash $S/launch_bc.sh
python3 -I $HC/scripts/fleet.py run --anchor B X1:2:+700:0 > $S/fleet.log 2>&1 &
sleep 3
python3 -I $HC/scripts/fleet.py report X1 crash >/dev/null; sleep 2
echo "== 먼 사고 (B 정차, 700m)"; $ADB -s emulator-5554 logcat -d | grep -E '구간 알림|경고 수신' | tail -2
python3 -I $HC/scripts/vc.py --port 8088 drive 60 >/dev/null
sleep 28
echo "== 접근 후"; $ADB -s emulator-5554 logcat -d | grep -E '구간 알림|경고 수신' | tail -2
python3 -I $HC/scripts/vc.py --port 8088 park >/dev/null
bash $S/stop_fleet.sh
