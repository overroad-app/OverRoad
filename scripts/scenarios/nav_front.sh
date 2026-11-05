#!/bin/bash
# 사용: nav_front.sh <기기> — 아래 독의 내비 아이콘을 눌러 내비를 앞에 띄운다 (주행 중 화면처럼). 앞에 올 때까지 다시 시도한다.
ADB=~/Library/Android/sdk/platform-tools/adb
for i in 1 2 3 4; do
  $ADB -s $1 shell dumpsys activity activities | grep -m1 "topResumedActivity" | grep -q navigation && exit 0
  $ADB -s $1 shell input tap 1154 1368
  sleep 2
done
