#!/bin/bash
# B, C 앱을 기준 위치로 다시 띄운다. 추가 인자는 B의 am start에 그대로 붙는다 (예: 목적지).
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
APK=$HC/app/build/outputs/apk/debug/app-debug.apk
for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p park >/dev/null; done
for d in emulator-5554 emulator-5556; do
  $ADB -s $d install -r $APK >/dev/null
  $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.car.permission.CAR_SPEED
  $ADB -s $d logcat -c
done
$ADB -s emulator-5554 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id B --ei lane 2 --ed offset_m 0 "$@" >/dev/null
$ADB -s emulator-5556 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id C --ei lane 2 --ed offset_m -300 >/dev/null
for i in $(seq 1 40); do
  curl -s localhost:8080/status | python3 -c "
import sys,json
v=json.load(sys.stdin)['vehicles']
sys.exit(0 if {'B','C'}<=set(v) and abs(v['B']['lat']-37.4127212)<2e-5 and abs((v['C']['lat']-v['B']['lat'])*111320+300)<3 else 1)" && break
  sleep 1
done
echo "B, C ready"
