"""mark.sh가 남긴 구간 표시를 ffmpeg select 식으로 바꾼다.  cut.py <marks 파일> <녹화 시작 시각> [앞뒤 여유 없음]"""
import sys
t0 = float(sys.argv[2])
segs, start = [], None
for line in open(sys.argv[1]):
    kind, t = line.split()
    t = float(t) - t0
    if kind == "in":
        start = t
    elif start is not None:
        segs.append((max(0, start), t))
        start = None
print("+".join(f"between(t,{a:.2f},{b:.2f})" for a, b in segs) or "1")
print(f"{sum(b - a for a, b in segs):.1f}", file=sys.stderr)
