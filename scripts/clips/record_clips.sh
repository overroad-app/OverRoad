#!/bin/bash
# 기능별 시연 클립 녹화: 각 시나리오를 실행하면서 에뮬레이터 B·C 화면을 동시에 녹화하고,
# B(위)·C(아래)를 세로로 붙인 영상(숏폼 기초 자료)을 만든다. 서버는 미리 떠 있어야 한다.
#   사용: record_clips.sh <서버 로그 파일> [클립 이름 ...]   (이름을 주지 않으면 전부)
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

end_guidance() {  # 이전 시나리오의 경로 안내가 남아 내비가 앞을 가리지 않게 끈다 (앱의 시연용 신호)
  for d in emulator-5554 emulator-5556; do
    $ADB -s $d shell am broadcast --user 10 -a com.hyperconnect.app.CANCEL_ROUTE >/dev/null
  done
  sleep 2
}

record() {  # record <폴더 이름> <제목> <명령...>
  local name=$1 title=$2; shift 2
  local rev=$(awk -v n="$name" '$1 == n {print $2}' "$S/revisions.txt")
  local dir=$OUT/$name/rev${rev:-1}; mkdir -p "$dir"
  title="$title · rev${rev:-1}"
  echo "=== 녹화: $name rev${rev:-1} ($title)"
  end_guidance
  for d in emulator-5554 emulator-5556; do
    $ADB -s $d shell rm -f /sdcard/clip.mp4
    $ADB -s $d shell screenrecord --bit-rate 8000000 --time-limit 170 /sdcard/clip.mp4 &
  done
  sleep 1
  "$@" > "$dir/log.txt" 2>&1
  sleep 3
  for d in emulator-5554 emulator-5556; do $ADB -s $d shell pkill -INT screenrecord; done
  wait
  sleep 1
  $ADB -s emulator-5554 pull /sdcard/clip.mp4 "$dir/b.mp4" >/dev/null
  $ADB -s emulator-5556 pull /sdcard/clip.mp4 "$dir/c.mp4" >/dev/null
  # B 위, C 아래로 붙이고 제목·차량 이름을 얹는다 (가로 1280, 숏폼용 세로 구성)
  python3 -I $S/frame.py "$title" "$dir/frame.png"
  ffmpeg -v error -y -i "$dir/b.mp4" -i "$dir/c.mp4" -i "$dir/frame.png" -filter_complex "
    [0:v]scale=1280:720,setsar=1[b];[1:v]scale=1280:720,setsar=1[c];
    [b][c]vstack=inputs=2,pad=1280:1560:0:120:color=0x12141a[s];[s][2:v]overlay=0:0[v]" \
    -map "[v]" -r 30 -c:v libx264 -crf 20 -pix_fmt yuv420p -shortest "$dir/clip.mp4"
  echo "    → $dir/clip.mp4 ($(ffprobe -v error -show_entries format=duration -of csv=p=0 "$dir/clip.mp4" | cut -d. -f1)초)"
}

consent_and_emote() {  # 동의 화면 → 동의 → (주차 중) 설정에서 이모트 팩 고르기 → 이모트 보내기: B → 60m 뒤 C
  bash $SC/stop_fleet.sh
  for p in 8088 8089; do python3 -I $HC/scripts/vc.py --port $p park >/dev/null; done
  for d in emulator-5554 emulator-5556; do $ADB -s $d shell pm grant --user 10 com.hyperconnect.app android.permission.POST_NOTIFICATIONS; $ADB -s $d logcat -c; done
  $ADB -s emulator-5556 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent true --es vehicle_id C --ei lane 2 --ed offset_m -60 >/dev/null
  $ADB -s emulator-5554 shell am start -S -n com.hyperconnect.app/.MainActivity --ez consent false --es vehicle_id B --ei lane 2 --es emote_pack basic >/dev/null
  sleep 7; bash $SC/tap.sh emulator-5554 "동의하고 시작"; sleep 5
  bash $SC/tap.sh emulator-5554 "뒤차에 고마워요"; sleep 5          # 기본 팩
  bash $SC/tap.sh emulator-5554 "⚙"; sleep 3
  bash $SC/tap.sh emulator-5554 "레트로 8비트 · 유료 예정 · 지금은 체험"; sleep 2
  bash $SC/tap.sh emulator-5554 "닫기"; sleep 2
  bash $SC/tap.sh emulator-5554 "뒤차에 고마워요"; sleep 6         # 레트로 팩으로 들린다
  bash $SC/tap.sh emulator-5554 "⚙"; sleep 3
  bash $SC/tap.sh emulator-5554 "오케스트라 · 유료 예정 · 지금은 체험"; sleep 2
  bash $SC/tap.sh emulator-5554 "닫기"; sleep 2
  bash $SC/tap.sh emulator-5554 "뒤차에 미안해요"; sleep 6
  $ADB -s emulator-5556 logcat -d | grep -E "이모트 수신" | sed 's/.*HyperConnect: //' | tail -3
}

ALL="01_hard_brake_feedback 02_secondary_accident 03_weather 05_ev_charging 06_emergency 07_lane_change 08_consent_emote"
for c in ${@:-$ALL}; do
  case $c in
    01_hard_brake_feedback) record $c "급제동 경고 · 피드백 · 내 기여" bash $SC/scenario_feedback.sh "$SL" ;;
    02_secondary_accident)  record $c "2차 사고 방지 (사고 정체 꼬리 경고)" bash $SC/scenario_close.sh "$SL" ;;
    03_weather)             record $c "기상 위험 (지역 동시 다발 · 외부 피드)" bash $SC/scenario_weather.sh "$SL" ;;
    04_zones)               echo "04_zones는 02_secondary_accident로 통합됨 (녹화 안 함)" ;;
    05_ev_charging)         record $c "EV 충전 예측 · 충전소 지도 · 별점" bash $SC/scenario_ev.sh "$SL" ;;
    06_emergency)           record $c "긴급차량 길 터주기 (경찰·소방·구급)" bash $SC/scenario_emergency.sh "$SL" ;;
    07_lane_change)         record $c "차로 변경 의도 공유 · 자동 감사 인사" bash $SC/scenario_lane.sh "$SL" ;;
    08_consent_emote)       record $c "데이터 공유 동의 · 이모트 팩" consent_and_emote ;;
  esac
done
