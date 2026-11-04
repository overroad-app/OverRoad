#!/bin/bash
# 경고 피드백 → 신뢰 점수 → 내 기여 화면 시나리오
#   1) B 급제동 → C에 경고 + "맞았어요/아니었어요" → C가 "맞았어요" → B 신뢰도·통계 반영
#   2) B 정차 상태에서 ⚙ 설정 열기 → 내 기여 화면 캡처
#   3) B와 멀리 떨어진 가상 차량 R이 급제동 보고 → X, Y, Z가 "아니었어요" → R의 다음 단독 보고는 "신뢰 합의 부족"
# 사용: scenario_feedback.sh <서버 로그 파일>
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
SL=$1
SHOT=${TMPDIR:-/tmp}/hyperconnect
mkdir -p "$SHOT"
FLEET="python3 -I $HC/scripts/fleet.py"

bash $S/stop_fleet.sh
bash $S/launch_bc.sh
$FLEET run --anchor B R:2:+1500:0 X:2:+1440:0 Y:2:+1380:0 Z:2:+1320:0 > "$SHOT/fleet.log" 2>&1 &
sleep 3
N=$(wc -l < $SL)

echo "--- 1) B 급제동"
bash $HC/scripts/hard_brake.sh >/dev/null
# 피드백 질문은 경고 카드가 사라진 뒤(정차·저속, 위험 순간 아님)에 나온다
for i in $(seq 1 20); do
  $ADB -s emulator-5556 shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  $ADB -s emulator-5556 exec-out cat /sdcard/ui.xml | grep -q "맞았어요" && break
  sleep 1
done
echo "피드백 질문 표시까지 약 ${i}초"
$ADB -s emulator-5556 exec-out screencap -p > "$SHOT/feedback_c.png"
bash $S/tap.sh emulator-5556 "맞았어요"
sleep 1
echo "B 통계: $(curl -s 'localhost:8080/stats?vehicle=B')"

echo "--- 2) B 설정 화면"
sleep 2
bash $S/tap.sh emulator-5554 "⚙"
sleep 2
$ADB -s emulator-5554 exec-out screencap -p > "$SHOT/settings_b.png"
bash $S/tap.sh emulator-5554 "닫기"

echo "--- 3) 신뢰 낮은 보고 차량"
$FLEET report R hard_brake
sleep 1
for v in X Y Z; do $FLEET feedback $v wrong; done
echo "R 통계: $(curl -s 'localhost:8080/stats?vehicle=R')"
sleep 1
echo "R 다시 단독 보고:"
$FLEET report R hard_brake

echo "== server"; tail -n +$((N+1)) $SL | grep -E 'feedback|신뢰|hard_brake|급제동' | head -20
echo "== C"; $ADB -s emulator-5556 logcat -d | grep -E '경고|피드백' | tail -5
bash $S/stop_fleet.sh
