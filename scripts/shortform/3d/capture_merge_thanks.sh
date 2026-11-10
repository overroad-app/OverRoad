#!/bin/bash
# B(1차로)가 신호 대기 줄의 C(2차로) 앞으로 끼어든 뒤 👍(뒷차)를 직접 눌러 보내고, C가 받는 장면
HC=~/Developer/HyperConnect; SC=$HC/scripts/scenarios
ADB=~/Library/Android/sdk/platform-tools/adb; APK=$HC/app/build/outputs/apk/debug/app-debug.apk
OUT=$(dirname "$0"); VC="python3 -I $HC/scripts/vc.py"
bash $SC/stop_fleet.sh
curl -s -X POST localhost:8080/demo -H 'Content-Type: application/json' -d '{"reset": true}' >/dev/null
for p in 8088 8089; do $VC --port $p park >/dev/null; $VC --port $p turn off >/dev/null; done
for d in emulator-5554 emulator-5556; do
  $ADB -s $d install -r $APK >/dev/null
  $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.car.permission.CAR_SPEED
  $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.permission.POST_NOTIFICATIONS
  $ADB -s $d shell am force-stop --user 10 com.hyperconnect.app
done
# B는 자동 감사 인사를 끈다 (직접 누르는 장면)
$ADB -s emulator-5554 shell "run-as com.hyperconnect.app --user 10 sh -c 'cat shared_prefs/hyperconnect.xml'" > $OUT/prefs_b.xml
python3 - $OUT/prefs_b.xml <<'PY'
import sys,re
p=sys.argv[1]; s=open(p).read()
s=re.sub(r'<boolean name="auto_thanks" value="[a-z]+" />\n?','',s)
s=s.replace('</map>','    <boolean name="auto_thanks" value="false" />\n</map>')
open(p,'w').write(s)
PY
$ADB -s emulator-5554 push $OUT/prefs_b.xml /data/local/tmp/p.xml >/dev/null
$ADB -s emulator-5554 shell "run-as com.hyperconnect.app --user 10 sh -c 'cat /data/local/tmp/p.xml > shared_prefs/hyperconnect.xml'"
$ADB -s emulator-5554 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id B --ei lane 1 --ed offset_m 0 --ez driving_view false >/dev/null
$ADB -s emulator-5556 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id C --ei lane 2 --ed offset_m -9 --ez driving_view false >/dev/null
for i in $(seq 1 40); do
  curl -s localhost:8080/status | python3 -c "
import sys,json
v=json.load(sys.stdin)['vehicles']
sys.exit(0 if {'B','C'}<=set(v) and abs((v['C']['lat']-v['B']['lat'])*111320+9)<2 else 1)" && break
  sleep 1
done
for p in 8088 8089; do $VC --port $p stop >/dev/null; done
python3 -I $HC/scripts/fleet.py run --anchor B Q1:2:+9:0 Q2:2:+17:0 L1:1:+9:0 L2:1:-11:0 R1:3:+3:0 R2:3:-7:0 R3:3:+12:0 > $OUT/fleet.log 2>&1 &
sleep 3
bash $SC/tap.sh emulator-5556 "자세히"; sleep 1; bash $SC/tap.sh emulator-5556 "자세히"; sleep 2
$ADB -s emulator-5554 shell uiautomator dump /sdcard/ui.xml >/dev/null; $ADB -s emulator-5554 exec-out cat /sdcard/ui.xml | grep -oE 'text="[^"]*고마워[^"]*"' 
for d in emulator-5554 emulator-5556; do $ADB -s $d shell rm -f /sdcard/clip.mp4; $ADB -s $d shell screenrecord --bit-rate 8000000 --time-limit 40 /sdcard/clip.mp4 & RP="$RP $!"; done
sleep 3; echo "t3 blinker"; $VC --port 8088 turn right >/dev/null
sleep 3; echo "t6 merge"; $ADB -s emulator-5554 shell am broadcast --user 10 -a com.hyperconnect.app.SET_LANE --ei lane 2 >/dev/null
sleep 2; $VC --port 8088 turn off >/dev/null
sleep 2; echo "t10 tap thanks"; bash $SC/tap.sh emulator-5554 "뒤차" last
sleep 6
for d in emulator-5554 emulator-5556; do $ADB -s $d shell pkill -INT screenrecord; done; wait $RP 2>/dev/null; sleep 2
$ADB -s emulator-5554 pull /sdcard/clip.mp4 $OUT/b.mp4 >/dev/null; $ADB -s emulator-5556 pull /sdcard/clip.mp4 $OUT/c.mp4 >/dev/null
bash $SC/stop_fleet.sh
