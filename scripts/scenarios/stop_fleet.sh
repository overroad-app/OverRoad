#!/bin/bash
# 실행 중인 가상 차량 시뮬레이터만 정확히 종료한다 (python 프로세스 중 fleet.py run 인자를 가진 것).
for pid in $(pgrep -f "[Pp]ython3?( -I)? .*scripts/fleet\.py run"); do
  [ "$pid" != "$$" ] && kill "$pid" 2>/dev/null
done
exit 0
