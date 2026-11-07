#!/bin/bash
# 사용: mark.sh in|out [초 보정] — 영상에 남길 구간의 시작·끝을 표시한다 (record_clips.sh가 이 구간만 이어 붙인다)
[ -n "$CLIP_MARKS" ] || exit 0
python3 -c "import time,sys; print(sys.argv[1], time.time() + float(sys.argv[2]))" "$1" "${2:-0}" >> "$CLIP_MARKS"
