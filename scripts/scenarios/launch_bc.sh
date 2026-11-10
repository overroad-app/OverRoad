#!/bin/bash
# B, C 앱을 기준 위치로 다시 띄운다. 추가 인자는 B의 am start에 그대로 붙는다 (예: 목적지).
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
# 직접 빌드한 디버그 APK가 있으면 그것을, 없으면 저장소의 release APK를 설치한다
APK=$HC/app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || APK=$HC/apk/OverRoad_release.apk
for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p park >/dev/null; done
# 이전 시연의 이벤트·전송 기록을 비운다 (같은 지점을 다시 시연해도 경고가 막히지 않게)
curl -s -X POST localhost:8080/demo -H 'Content-Type: application/json' -d '{"reset": true}' >/dev/null
for d in emulator-5554 emulator-5556; do
  # 서명이 다른 앱(디버그 ↔ release)이 이미 있으면 설치가 거절된다 → 이유를 보여 준다
  $ADB -s $d install -r "$APK" >/dev/null || echo "[$d] 앱 설치 실패: 기존 앱과 서명이 다르면 adb uninstall --user 10 com.hyperconnect.app 후 다시 실행" >&2
  $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.car.permission.CAR_SPEED
  $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.permission.POST_NOTIFICATIONS
  $ADB -s $d logcat -c
done
$ADB -s emulator-5554 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id B --ei lane 2 --ed offset_m 0 "$@" >/dev/null
$ADB -s emulator-5556 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id C --ei lane 2 --ed offset_m -${C_GAP:-300} >/dev/null
for i in $(seq 1 40); do
  curl -s localhost:8080/status | python3 -c "
import sys,json
v=json.load(sys.stdin)['vehicles']
sys.exit(0 if {'B','C'}<=set(v) and abs(v['B']['lat']-37.4127212)<2e-5 and abs((v['C']['lat']-v['B']['lat'])*111320+${C_GAP:-300})<3 else 1)" && break
  sleep 1
done
# NAV_FRONT="B C": 그 차량은 내비를 앞에 띄워 주행 중 화면처럼 둔다 (경고는 내비 위 상단 알림으로 보인다)
sleep 2
for v in ${NAV_FRONT:-}; do
  bash $(dirname "$0")/nav_front.sh $([ "$v" = B ] && echo emulator-5554 || echo emulator-5556)
done
echo "B, C ready"
