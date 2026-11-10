#!/bin/bash
# 12 전기차 · 가장 빨리 충전되는 곳: 목적지를 정하지 않아도 충전이 가장 빨리 끝나는 근처 충전소를 늘 계산해 오른쪽 위에 띄워 둔다
#   1) B(배터리 45%)가 신호 대기 중: 오른쪽 위에 "가장 빨리 충전되는 곳 · ○○ 충전소 · HH:MM 완료"
#   2) 배터리가 18%로 떨어지면 주황 테두리 + "배터리 18%"
#   3) 버튼을 한 번 누르면 묻지 않고 바로 그 충전소로 경로를 잡는다
#      (지금은 플랫폼 제약으로 Pleos 내비에 경로를 넘긴다. 최종 목표는 OverRoad 화면 안에서 바로 안내)
#   판교역 충전소는 타사 차량 2대가 충전 중이라 대기가 길고, 시흥동 충전소는 비어 있다 → 더 멀어도 빨리 끝나는 곳을 고른다
# 사용: scenario_quick_charge.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
SHOT=${TMPDIR:-/tmp}/hyperconnect; mkdir -p "$SHOT"
VC="python3 -I $HC/scripts/vc.py"
M="bash $S/mark.sh"
api() { curl -s -X POST "localhost:8080$1" -H 'Content-Type: application/json' -d "$2" >/dev/null; }

bash $S/stop_fleet.sh
api /public_status '{"station": "PANGYO", "busy_since_min_ago": [3, 8], "arrivals_per_hour": 2}'
api /public_status '{"station": "SIHEUNG", "busy_since_min_ago": [], "arrivals_per_hour": 0.5}'
for p in 8088 8089; do $VC --port $p charge off >/dev/null; done
$VC --port 8088 soc 45 >/dev/null
C_GAP=14 bash $S/launch_bc.sh --ez driving_view false --es emote_pack basic
python3 -I $HC/scripts/fleet.py run --anchor B Q1:2:+11:0 Q2:2:+21:0 Q5:3:+8:0 Q6:1:-9:0 > "$SHOT/fleet.log" 2>&1 &
bash $S/wait_log.sh emulator-5554 "배터리 = 45" 20
sleep 7                                   # 5초 주기로 충전소 예측을 읽어 버튼이 뜬다
$M in; sleep 3.5                          # 1) 평소: 회색 테두리 버튼
$VC --port 8088 soc 18 >/dev/null; sleep 5   # 2) 배터리 부족: 주황 테두리
bash $S/tap.sh emulator-5554 "바로 안내"; sleep 6   # 3) 누르면 바로 경로
$M out
echo "== B"; $ADB -s emulator-5554 logcat -d | grep -E "가장 빨리|충전 예측" | sed 's/.*HyperConnect: //' | tail -3

# 정리
$ADB -s emulator-5554 shell am broadcast --user 10 -a com.hyperconnect.app.CANCEL_ROUTE >/dev/null
$VC --port 8088 soc 60 >/dev/null
api /public_status '{"station": "PANGYO", "busy_since_min_ago": [], "arrivals_per_hour": 0}'
api /public_status '{"station": "SIHEUNG", "busy_since_min_ago": [], "arrivals_per_hour": 0}'
bash $S/stop_fleet.sh
