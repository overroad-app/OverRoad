#!/bin/bash
# 기능별 시연 클립 녹화: 각 시나리오를 실행하면서 에뮬레이터 B·C 화면을 동시에 녹화하고,
# B(위)·C(아래)를 세로로 붙인 영상(숏폼 기초 자료)을 만든다. 서버는 미리 떠 있어야 한다.
#   사용: record_clips.sh <서버 로그 파일> [클립 이름 ...]   (이름을 주지 않으면 전부)
#   시나리오는 mark.sh로 볼 구간을 표시한다. 준비·대기 시간은 잘라내고 표시한 구간만 이어 붙인다.
#   결과: demo/clips/<번호_이름>/rev<개선판>/{b.mp4, c.mp4, clip.mp4, log.txt}   (저장소에는 올리지 않음)
#   개선판 번호는 scripts/clips/revisions.txt, 바뀐 내용은 docs/feature_revisions.md
# 소리: 에뮬레이터 화면 녹화(screenrecord)에는 소리가 담기지 않는다. 맥 소리 출력을 "BlackHole 2ch" 단독으로 두고
#   에뮬레이터를 켜면(에뮬레이터는 "다중 출력 기기" 같은 묶음 기기로는 소리를 내지 못한다), BlackHole로 들어오는
#   소리(경고 음성·이모트 소리, B·C가 섞인 한 트랙)를 함께 녹음해 같은 구간으로 잘라 붙인다.
#   BlackHole이 없거나 AUDIO=0이면 소리 없이 녹화한다. 녹화가 끝나면 출력을 스피커로 되돌린다.
#   AUDIO_SHIFT(초): 화면 녹화가 실제로 시작되기까지의 지연과 에뮬레이터 소리 출력 지연 보정 (기본 0.45)
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
  local apid="" ta0=""
  if [ "${AUDIO:-1}" != 0 ] && system_profiler SPAudioDataType 2>/dev/null | grep -q "BlackHole 2ch"; then
    # 녹음기: ffmpeg의 avfoundation 입력은 소리 조각을 약 12% 빠뜨려 음성이 끊겨 들린다(1kHz 시험음으로 확인).
    #   CoreAudio로 직접 받는 작은 녹음기(bhrec.swift)를 쓴다. 처음 한 번 컴파일한다.
    [ -x "$S/bhrec" ] || swiftc -O "$S/bhrec.swift" -o "$S/bhrec" 2>/dev/null
    rm -f "$dir/audio.wav" "$dir/audio_start.txt"
    "$S/bhrec" "$dir/audio.wav" 180 > "$dir/audio_start.txt" 2>&1 &
    apid=$!
  fi
  local t0=$(python3 -c "import time; print(time.time())")
  for d in emulator-5554 emulator-5556; do
    # 화면 크기를 줄여 녹화 부담을 덜고 프레임 수를 올린다 (완성 영상도 한 화면 1280x720)
    $ADB -s $d shell screenrecord --size 1280x720 --bit-rate 6000000 --time-limit 175 /sdcard/clip.mp4 &
  done
  sleep 1
  "$@" > "$dir/log.txt" 2>&1
  sleep 1
  for d in emulator-5554 emulator-5556; do $ADB -s $d shell pkill -INT screenrecord; done
  [ -n "$apid" ] && kill -INT $apid 2>/dev/null
  wait
  sleep 1
  $ADB -s emulator-5554 pull /sdcard/clip.mp4 "$dir/b.mp4" >/dev/null
  $ADB -s emulator-5556 pull /sdcard/clip.mp4 "$dir/c.mp4" >/dev/null
  # B 위, C 아래로 붙이고 제목·차량 이름을 얹는다 (가로 1280, 숏폼용 세로 구성)
  python3 -I $S/frame.py "$title" "$dir/frame.png"
  ffmpeg -v error -y -i "$dir/b.mp4" -i "$dir/c.mp4" -i "$dir/frame.png" -filter_complex "
    [0:v]fps=30,scale=1280:720,setsar=1[b];[1:v]fps=30,scale=1280:720,setsar=1[c];
    [b][c]vstack=inputs=2,pad=1280:1560:0:120:color=0x12141a[s];[s][2:v]overlay=0:0,select='$(python3 -I $S/cut.py "$CLIP_MARKS" "$t0")',setpts=N/30/TB[v]" \
    -map "[v]" -r 30 -c:v libx264 -crf 20 -pix_fmt yuv420p -shortest "$dir/video.mp4"
  if [ -s "$dir/audio.wav" ]; then
    # 소리 파일의 0초 = 녹음기가 첫 소리 조각을 받은 시각(START) − 조각 길이(4096표본 ≈ 0.085초).
    # 화면 녹화는 시작 명령보다 조금 늦게 시작되므로(AUDIO_SHIFT, 기본 0.45초: 화면 녹화 시작 지연 + 에뮬레이터 소리 출력 지연) 그만큼 소리 구간을 늦춰 화면과 맞춘다
    ta0=$(awk -v s="${AUDIO_SHIFT:-0.45}" '/^START/ {printf "%.3f", $2 - 0.085 - s}' "$dir/audio_start.txt")
    # 소리도 영상과 같은 구간만 남겨 이어 붙인다 (소리 파일의 0초 = 녹음 시작 시각)
    ffmpeg -v error -y -i "$dir/video.mp4" -i "$dir/audio.wav" -filter_complex \
      "[1:a]aselect='$(python3 -I $S/cut.py "$CLIP_MARKS" "$ta0" 2>/dev/null)',asetpts=N/SR/TB[a]" \
      -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -shortest "$dir/clip.mp4"
  else
    cp "$dir/video.mp4" "$dir/clip.mp4"
  fi
  echo "    → $dir/clip.mp4 ($(ffprobe -v error -show_entries format=duration -of csv=p=0 "$dir/clip.mp4" | cut -d. -f1)초)"
}

consent_and_emote() {  # 동의 화면 → 동의 → B가 뒤차 C에게 👍 → C가 앞차 B에게 👍 (선을 따라 오가고, 보낸 버튼이 비었다가 차오른다)
  bash $SC/stop_fleet.sh
  curl -s -X POST localhost:8080/demo -H 'Content-Type: application/json' -d '{"reset": true}' >/dev/null   # 앞 장면의 이모트 제한이 남지 않게
  for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p park >/dev/null; done
  for d in emulator-5554 emulator-5556; do $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.permission.POST_NOTIFICATIONS; $ADB -s $d logcat -c; done
  $ADB -s emulator-5556 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id C --ei lane 2 --ed offset_m -22 --es emote_pack basic >/dev/null
  $ADB -s emulator-5554 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent false --es vehicle_id B --ei lane 2 --es emote_pack basic >/dev/null
  sleep 7
  M="bash $SC/mark.sh"; T="bash $SC/tap.sh emulator-5554"
  $T "동의하고 시작"; $M in -2; sleep 1.5; $M out; sleep 3
  # 주변 차 몇 대: 평소의 회색 인지 표시(멀수록 흐려지는 점선)가 보이게. B가 동의해 서버에 올라온 뒤에 놓는다
  python3 -I $HC/scripts/fleet.py run --anchor B D:1:+12:0 G:3:+30:0 H:3:-14:0 K:2:+38:0 > /dev/null 2>&1 &
  sleep 4
  $T "뒤차" last; $M in -0.4; sleep 4; $M out; sleep 1.5      # 👍 고마워요 ▼ 뒤차
  bash $SC/tap.sh emulator-5556 "앞차"; $M in -0.4; sleep 4; $M out; sleep 1   # C가 답례
  bash $SC/stop_fleet.sh
  $ADB -s emulator-5556 logcat -d | grep -E "이모트 수신" | sed 's/.*HyperConnect: //' | tail -3
}

ALL="01_hard_brake_feedback 02_secondary_accident 03_weather 05_ev_charging 06_emergency 07_lane_change 08_consent_emote 09_surround 10_surround_drive 11_emote_store 12_quick_charge 13_layers"
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
    11_emote_store)         record $c "이모트 스토어 · 특별 지급" bash $SC/scenario_store.sh "$SL" ;;
    10_surround_drive)      record $c "연결망 시각화 · 주행 상황 (상위 티어 미리보기)" bash $SC/scenario_surround_drive.sh "$SL" ;;
    09_surround)            record $c "연결망 시각화 · 정지 상황 (Tier 1 GPS 비콘)" bash $SC/scenario_surround.sh "$SL" ;;
    12_quick_charge)        record $c "전기차 · 가장 빨리 충전되는 곳 (한 번 눌러 바로 안내)" bash $SC/scenario_quick_charge.sh "$SL" ;;
    13_layers)              record $c "식별 층위 (GPS → 카메라·레이더 → UWB) · Tier 2~3 미리보기" bash $SC/scenario_layers.sh "$SL" ;;
  esac
done
