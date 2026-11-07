#!/bin/bash
# Virtual Console API로 차량 A의 급제동을 재현한다.
#   D 기어 → 80km/h까지 가속 → 브레이크를 밟고 약 1.5초 만에 20km/h로 감속 → 위험을 피한 뒤 다시 60km/h로 주행 (멈추지 않는다)
# 사용법: scripts/hard_brake.sh [Virtual Console 주소, 기본 http://127.0.0.1:8088]
set -euo pipefail
VC=${1:-http://127.0.0.1:8088}

controls() { curl -sf -X PUT "$VC/vehicle/controls?speed=$1&accelerator=$2&brake=$3" > /dev/null; }

# Virtual Console은 브라우저 세션 하트비트(3초 타임아웃)가 있어야 제어를 받는다
heartbeat() { while true; do curl -sf -X POST "$VC/browser/session?client=hard-brake-script" > /dev/null || true; sleep 1; done; }
heartbeat &
trap 'kill $! 2>/dev/null' EXIT
sleep 1

echo "D 기어"
curl -sf -X PUT "$VC/vehicle/gear?gear=Drive" > /dev/null

echo "80km/h까지 가속"
for v in 10 20 30 40 50 60 70 80; do controls "$v" 40 0; sleep 0.3; done
sleep 1.5

echo "급제동: 80 → 20km/h"
echo "T0 $(perl -MTime::HiRes=time -e 'printf "%.3f", time')"  # 지연시간 측정용: 브레이크 명령 시각
for v in 72 62 52 42 32 22 20; do controls "$v" 0 90; sleep 0.2; done
sleep 1

echo "다시 가속: 60km/h로 주행 계속"
for v in 25 30 35 40 45 50 55 60; do controls "$v" 30 0; sleep 0.4; done
echo "완료"
