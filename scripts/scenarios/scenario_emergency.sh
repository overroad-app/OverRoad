#!/bin/bash
# 긴급차량 시나리오: 구급차가 2차로 B 뒤 800m에서 90km/h로 C, B를 차례로 추월한다. 옆 1차로 D도 있다.
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
SL=$1  # 서버 로그 파일

bash $S/stop_fleet.sh
(cd $HC && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain -q 2>&1 | grep -E '^e:')
bash $S/launch_bc.sh
N=$(wc -l < $SL)
python3 -I $HC/scripts/fleet.py run --anchor B AMB:2:-800:90:role=ambulance D:1:-150 > $S/fleet.log 2>&1 &
sleep 8
$ADB -s emulator-5554 exec-out screencap -p > $S/amb_b.png
sips -Z 1000 $S/amb_b.png >/dev/null
sleep 30
tail -n +$((N+1)) $SL | grep emergency
echo "== B"; $ADB -s emulator-5554 logcat -d | grep '긴급차량' | tail -3
echo "== C"; $ADB -s emulator-5556 logcat -d | grep '긴급차량' | tail -2
bash $S/stop_fleet.sh
