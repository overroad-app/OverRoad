#!/bin/bash
# 기능별 시연 클립 녹화: 각 시나리오를 실행하면서 에뮬레이터 B·C 화면을 동시에 녹화하고,
# B(위)·C(아래)를 세로로 붙인 영상(숏폼 기초 자료)을 만든다. 서버는 미리 떠 있어야 한다.
#   사용: record_clips.sh <서버 로그 파일> [클립 이름 ...]   (이름을 주지 않으면 전부)
#   시나리오는 mark.sh로 볼 구간을 표시한다. 준비·대기 시간은 잘라내고 표시한 구간만 이어 붙인다.
#   결과: demo/clips/<번호_이름>/rev<개선판>/{b.mp4, c.mp4, clip.mp4, log.txt}   (저장소에는 올리지 않음)
#   개선판 번호는 scripts/clips/revisions.txt, 바뀐 내용은 docs/feature_revisions.md
# 참고: 에뮬레이터 화면 녹화(screenrecord)에는 소리가 담기지 않는다. 경고 음성·이모트 소리는 편집 때 따로 넣는다.
S=$(cd "$(dirname "$0")" && pwd)
HC=$(cd "$S/../.." && pwd)
SC=$HC/scripts/scenarios
ADB=~/Library/Android/sdk/platform-tools/adb
SL=$1; shift
OUT=$HC/demo/clips
mkdir -p "$OUT"

end_guidance() {  # 이전 시나리오의 경로 안내가 남지 않게 내비를 다시 시작한다 (에뮬레이터 시연 정리용)
  local nav=ai.umos.maps.android.navigation.app
  for d in emulator-5554 emulator-5556; do
    $ADB -s $d shell am force-stop --user 10 $nav
    $ADB -s $d shell am start --user 10 -n $nav/.MainActivity >/dev/null
  done
  sleep 6
}

record() {  # record <폴더 이름> <제목> <명령...>
  local name=$1 title=$2; shift 2
  local rev=$(awk -v n="$name" '$1 == n {print $2}' "$S/revisions.txt")
  local dir=$OUT/$name/rev${rev:-1}; mkdir -p "$dir"
  title="$title · rev${rev:-1}"
  echo "=== 녹화: $name rev${rev:-1} ($title)"
  end_guidance
  export CLIP_MARKS=$dir/marks.txt; rm -f "$CLIP_MARKS"
  for d in emulator-5554 emulator-5556; do $ADB -s $d shell rm -f /sdcard/clip.mp4; done
  local t0=$(python3 -c "import time; print(time.time())")
  for d in emulator-5554 emulator-5556; do
    # 화면 크기를 줄여 녹화 부담을 덜고 프레임 수를 올린다 (완성 영상도 한 화면 1280x720)
    $ADB -s $d shell screenrecord --size 1280x720 --bit-rate 6000000 --time-limit 175 /sdcard/clip.mp4 &
  done
  sleep 1
  "$@" > "$dir/log.txt" 2>&1
  sleep 1
  for d in emulator-5554 emulator-5556; do $ADB -s $d shell pkill -INT screenrecord; done
  wait
  sleep 1
  $ADB -s emulator-5554 pull /sdcard/clip.mp4 "$dir/b.mp4" >/dev/null
  $ADB -s emulator-5556 pull /sdcard/clip.mp4 "$dir/c.mp4" >/dev/null
  # B 위, C 아래로 붙이고 제목·차량 이름을 얹는다 (가로 1280, 숏폼용 세로 구성)
  python3 -I $S/frame.py "$title" "$dir/frame.png"
  ffmpeg -v error -y -i "$dir/b.mp4" -i "$dir/c.mp4" -i "$dir/frame.png" -filter_complex "
    [0:v]fps=30,scale=1280:720,setsar=1[b];[1:v]fps=30,scale=1280:720,setsar=1[c];
    [b][c]vstack=inputs=2,pad=1280:1560:0:120:color=0x12141a[s];[s][2:v]overlay=0:0,select='$(python3 -I $S/cut.py "$CLIP_MARKS" "$t0")',setpts=N/30/TB[v]" \
    -map "[v]" -r 30 -c:v libx264 -crf 20 -pix_fmt yuv420p -shortest "$dir/clip.mp4"
  echo "    → $dir/clip.mp4 ($(ffprobe -v error -show_entries format=duration -of csv=p=0 "$dir/clip.mp4" | cut -d. -f1)초)"
}

consent_and_emote() {  # 동의 화면 → 동의 → 이모트 보내기: B → 60m 뒤 C
  bash $SC/stop_fleet.sh
  for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p park >/dev/null; done
  for d in emulator-5554 emulator-5556; do $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.permission.POST_NOTIFICATIONS; $ADB -s $d logcat -c; done
  $ADB -s emulator-5556 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id C --ei lane 2 --ed offset_m -60 >/dev/null
  $ADB -s emulator-5554 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent false --es vehicle_id B --ei lane 2 --es emote_pack basic >/dev/null
  sleep 7
  M="bash $SC/mark.sh"; T="bash $SC/tap.sh emulator-5554"
  $T "동의하고 시작"; $M in -2; sleep 1; $M out; sleep 3
  $T "뒤차에 고마워요"; $M in -0.3; sleep 3; $M out; sleep 2
  # 이모트 팩 고르기·구매는 설정에서 뺐다 (나중에 별도 상점 화면)
  $T "뒤차에 미안해요"; $M in -0.3; sleep 3; $M out; sleep 1
  $ADB -s emulator-5556 logcat -d | grep -E "이모트 수신" | sed 's/.*HyperConnect: //' | tail -3
}

ALL="01_hard_brake_feedback 02_secondary_accident 03_weather 05_ev_charging 06_emergency 07_lane_change 08_consent_emote 09_surround 10_surround_drive 11_emote_store"
for c in ${@:-$ALL}; do
  case $c in
    01_hard_brake_feedback) record $c "급제동 경고 (주행 중)" bash $SC/scenario_feedback.sh "$SL" ;;
    02_secondary_accident)  record $c "2차 사고 방지 (사고 정체 꼬리 경고)" bash $SC/scenario_close.sh "$SL" ;;
    03_weather)             record $c "기상 위험 (지역 동시 다발 · 외부 피드)" bash $SC/scenario_weather.sh "$SL" ;;
    04_zones)               echo "04_zones는 02_secondary_accident로 통합됨 (녹화 안 함)" ;;
    05_ev_charging)         record $c "EV 충전 예측 · 충전소 지도 · 별점" bash $SC/scenario_ev.sh "$SL" ;;
    06_emergency)           record $c "긴급차량 길 터주기 (경찰·소방·구급)" bash $SC/scenario_emergency.sh "$SL" ;;
    07_lane_change)         record $c "차로 변경 의도 공유 · 자동 감사 인사" bash $SC/scenario_lane.sh "$SL" ;;
    08_consent_emote)       record $c "데이터 공유 동의 · 이모트" consent_and_emote ;;
    11_emote_store)         record $c "이모트 상점 · 특별 지급" bash $SC/scenario_store.sh "$SL" ;;
    10_surround_drive)      record $c "연결망 시각화 · 주행 상황 (상위 티어 미리보기)" bash $SC/scenario_surround_drive.sh "$SL" ;;
    09_surround)            record $c "연결망 시각화 · 정지 상황 (Tier 1 GPS 비콘)" bash $SC/scenario_surround.sh "$SL" ;;
  esac
done
