#!/bin/bash
# 긴급차량 시나리오 (rev2): 경찰차·소방차·구급차가 2차로 뒤에서 90km/h로 C, B를 차례로 추월한다. 옆 1차로 D도 있다.
#   역할마다 카드 색·아이콘·문구·사이렌음이 다르고, 처음 다가올 때 음성으로 한 번 알린다.
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$(dirname "$0")/../.." && pwd)  # 저장소 루트
ADB=~/Library/Android/sdk/platform-tools/adb
SHOT=${TMPDIR:-/tmp}/hyperconnect  # 화면 캡처·로그는 저장소 밖에 둔다
mkdir -p "$SHOT"
SL=$1  # 서버 로그 파일

bash $S/stop_fleet.sh
NAV_FRONT="B C" C_GAP=15 bash $S/launch_bc.sh
N=$(wc -l < $SL)
# rev2: 경찰차·소방차·구급차가 차례로 지나간다 (역할별 카드·사이렌음·음성). B는 앱 화면, C는 내비 위 상단 알림
# B·C는 2차로를 50km/h로 주행 중. 긴급차량은 같은 2차로 뒤에서 90km/h로 다가온다
python3 -I $HC/scripts/fleet.py run --anchor B POL:2:-350:90:role=police FIRE:2:-900:90:role=fire \
  AMB:2:-1450:90:role=ambulance D:1:-150:50 > "$SHOT/fleet.log" 2>&1 &
for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p drive 50 >/dev/null; done
yielded=""
for role in "경찰차" "소방차" "구급차"; do   # 역할마다 처음 다가올 때만 영상에 남긴다
  # rev7: 뒤에 있는 C가 B보다 먼저 안내받는다. B 기준으로만 자르면 C의 카드는 이미 사라진 뒤라 영상에 안 보였다
  #   → C가 안내받는 순간부터 B의 안내 5초까지 남기고, 두 차 간격도 15m로 좁혀 카드가 함께 보이게 한다
  bash $S/wait_log.sh emulator-5556 "긴급차량: .*$role" 120; bash $S/mark.sh in -0.5
  bash $S/wait_log.sh emulator-5554 "긴급차량: .*$role" 30; sleep 5; bash $S/mark.sh out
  $ADB -s emulator-5554 exec-out screencap -p > "$SHOT/em_${role}_b.png"
  if [ -z "$yielded" ]; then   # 첫 안내("오른쪽으로 비켜 주세요")를 듣고 B·C가 3차로로 비켜 준다 (주행은 계속)
    for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p turn right >/dev/null; done; sleep 2
    for d in emulator-5554 emulator-5556; do $ADB -s $d shell am broadcast --user 10 -a com.hyperconnect.app.SET_LANE --ei lane 3 >/dev/null; done
    for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p turn off >/dev/null; done
    yielded=1
  fi
done
sleep 2
tail -n +$((N+1)) $SL | grep emergency
echo "== B"; $ADB -s emulator-5554 logcat -d | grep '긴급차량' | tail -6
echo "== C"; $ADB -s emulator-5556 logcat -d | grep '긴급차량' | tail -6
bash $S/stop_fleet.sh
