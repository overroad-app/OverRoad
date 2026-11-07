#!/bin/bash
# 사용: wait_log.sh <기기> <로그 문구> [최대 초] — 앱 로그에 문구가 나올 때까지 기다린다 (고정 대기 대신)
ADB=~/Library/Android/sdk/platform-tools/adb
for i in $(seq 1 $(( ${3:-60} * 2 ))); do
  $ADB -s $1 logcat -d -s HyperConnect:I | grep -qE "$2" && exit 0
  sleep 0.5
done
echo "로그를 기다리다 시간 초과: $2"; exit 1
