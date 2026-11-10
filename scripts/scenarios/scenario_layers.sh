#!/bin/bash
# 13 식별 층위 (Tier 2~3 미리보기): 주변 참여 차가 어떤 센서로 보고하느냐에 따라 기본 화면 왼쪽 위 표시가 켜진다
#   1) GPS 비콘만 (Tier 1): 참여 차만 보인다
#   2) 앞차가 카메라·레이더로 본 물체를 보고 → "카메라", "레이더·라이다" 점등 + 비연동 차량(점선)이 화면에 나타난다
#   3) 라이다·UWB까지 → "UWB" 점등, "Tier 3 미리보기"
#   지금 Pleos SDK에는 센서 물체 API가 없어서, 가상 차량이 센서 보고를 대신한다 (서버 등록·화면 표시 경로는 실제 코드)
# 사용: scenario_layers.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
SHOT=${TMPDIR:-/tmp}/hyperconnect; mkdir -p "$SHOT"
FLEET="python3 -I $HC/scripts/fleet.py"
M="bash $S/mark.sh"
CARS="D:1:+10:0 F:2:-18:0 E:3:-6:0 G:3:+45:0 H:1:-30:0 X1:1:+35:0:member=0 X2:3:+22:0:member=0"

bash $S/stop_fleet.sh
C_GAP=300 bash $S/launch_bc.sh --ez driving_view false --es emote_pack basic
$FLEET run --anchor B A:2:+25:0 $CARS > "$SHOT/fleet.log" 2>&1 &
sleep 5
$M in; sleep 4                              # 1) GPS 비콘만
bash $S/stop_fleet.sh
$FLEET run --anchor B A:2:+25:0:sensor=1 $CARS > "$SHOT/fleet.log" 2>&1 &
sleep 6                                     # 2) 카메라·레이더 + 비연동 차량
bash $S/stop_fleet.sh
$FLEET run --anchor B A:2:+25:0:sensor=camera+radar+lidar+uwb $CARS > "$SHOT/fleet.log" 2>&1 &
sleep 6                                     # 3) UWB까지
$M out
curl -s "localhost:8080/surroundings?vehicle=B" | python3 -c "import sys,json; d=json.load(sys.stdin); print('층위', d.get('sources'), '· 비연동', d.get('observed'))"
bash $S/stop_fleet.sh
