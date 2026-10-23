#!/bin/bash
# 정체·사고 구간 알림 시나리오
#  1) 단순 정체: 800m 앞 9대가 10km/h → B 접근 시 "정체 구간"
#  2) 같은 정체 머리에 멈춘 차량 X1 → "사고·장애물 추정 구간"
#  3) X1이 사고 보고 → "사고 구간"
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
SL=$1

show() { echo "== B"; $ADB -s emulator-5554 logcat -d | grep '구간 알림' | tail -1; }

echo "--- 1) 단순 정체"
bash $S/stop_fleet.sh; bash $S/launch_bc.sh
python3 -I $HC/scripts/fleet.py run --anchor B --jam +800:9:10 > $S/fleet.log 2>&1 &
sleep 3; python3 -I $HC/scripts/vc.py --port 8088 drive 60 >/dev/null; sleep 4; show
python3 -I $HC/scripts/vc.py --port 8088 park >/dev/null

echo "--- 2) 멈춘 차량이 있는 정체"
bash $S/stop_fleet.sh; bash $S/launch_bc.sh
python3 -I $HC/scripts/fleet.py run --anchor B --jam +800:9:10 X1:2:+1080:0 > $S/fleet.log 2>&1 &
sleep 3; python3 -I $HC/scripts/vc.py --port 8088 drive 60 >/dev/null; sleep 4; show

echo "--- 3) 멈춘 차량이 사고 보고"
python3 -I $HC/scripts/fleet.py report X1 crash >/dev/null; sleep 3; show
$ADB -s emulator-5554 exec-out screencap -p > $S/zone_b.png; sips -Z 1000 $S/zone_b.png >/dev/null
python3 -I $HC/scripts/vc.py --port 8088 park >/dev/null
echo "== server"; grep '\[zone\]' $SL | tail -4
