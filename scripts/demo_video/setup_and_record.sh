#!/bin/bash
# 시연 환경(B, C 앱 + 가상 A, D, E)을 초기화하고 v3 시나리오를 녹화한다. 서버는 미리 떠 있어야 한다.
set -e
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
APK=$HC/app/build/outputs/apk/debug/app-debug.apk
OUT=$1

for p in 8088 8089; do
  python3 -I $HC/scripts/vc.py --port $p park >/dev/null
  python3 -I $HC/scripts/vc.py --port $p wiper Off >/dev/null
done
for s in emulator-5554:B:2:0 emulator-5556:C:2:-300; do
  IFS=: read d id lane off <<< "$s"
  $ADB -s $d install -r $APK >/dev/null
  $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.car.permission.CAR_SPEED
  $ADB -s $d shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id $id --ei lane $lane --ed offset_m $off >/dev/null
done
# B·C가 재시작 후 기준 위치(내비 기준점, B는 0m·C는 -300m)로 등록될 때까지 기다린다
for i in $(seq 1 30); do
  curl -s localhost:8080/status | python3 -c "
import sys,json
v=json.load(sys.stdin)['vehicles']
ok={'B','C'}<=set(v) and abs(v['B']['lat']-37.4127212)<2e-5 and abs((v['C']['lat']-37.4127212)*111320+300)<3
sys.exit(0 if ok else 1)" && break
  sleep 1
done
python3 -I $HC/scripts/fleet.py run --anchor B A:2:+250 D:1:-120 E:2:-1500 > $S/fleet.log 2>&1 &
sleep 4
curl -s localhost:8080/status | python3 -c "import sys,json;s=json.load(sys.stdin);v=s['vehicles'];print({k:(v[k]['lane'],round((v[k]['lat']-v['B']['lat'])*111320)) for k in sorted(v)}, 'events', len(s['events']))"

rm -rf $OUT; mkdir -p $OUT
python3 -I $S/record_v3.py $OUT
python3 -I -c "
import json
m=json.load(open('$OUT/markers.json'));t0=m['rec_start']
last=[json.loads(l) for l in open('$OUT/timeline.jsonl')][-1]['s']
for a in last['alerts']: print(round(a['ts']-t0,2), a['to'], a['type'], a['distance_m'], a['lane'])"
