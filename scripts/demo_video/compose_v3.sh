#!/bin/bash
# v3 영상 합성: 대시보드 렌더 + 에뮬레이터 B·C 화면 + 자막·강조·효과음. 이벤트 시각은 timeline에서 읽는다.
set -e
S=$(cd "$(dirname "$0")" && pwd)
V=$1
cd $V

# 이벤트 시각 (녹화 시작 기준 τ) → 본편 시각 (τ - 2)
read T_BRAKE T_STOP T_RAIN <<< $(python3 -I -c "
import json
m=json.load(open('markers.json'));t0=m['rec_start']
last=[json.loads(l) for l in open('timeline.jsonl')][-1]['s']
first=lambda typ: min(a['ts'] for a in last['alerts'] if a['type']==typ)-t0-2
print(round(first('hard_brake'),2), round(first('stopped_vehicle'),2), round(first('heavy_rain'),2))")
echo "본편 이벤트: 급제동 $T_BRAKE, 정차 $T_STOP, 폭우 $T_RAIN"

# 에뮬레이터 녹화와 τ의 차이: C 화면에 첫 경고 배너가 뜬 시각으로 맞춘다
rm -rf fc && mkdir fc && ffmpeg -v error -ss 8 -t 7 -i c.mp4 -vf fps=30,scale=320:-1 fc/%04d.png
OFF=$(python3 -I -c "
from PIL import Image; import glob
for f in sorted(glob.glob('fc/*.png')):
    im=Image.open(f).convert('RGB'); w,h=im.size
    px=[im.getpixel((x,int(h*0.18))) for x in range(int(w*0.4),w,8)]
    if sum(1 for r,g,b in px if r>150 and g<80 and b<80)/len(px)>0.5:
        print(round(8+(int(f[3:7])-1)/30-($T_BRAKE+2),3)); break")
SS=$(python3 -c "print(round(2+$OFF,3))")
echo "녹화 오프셋 $OFF → 시작 $SS"

python3 -I $S/render_dashboard.py . 2 56 | ffmpeg -v error -y -f rawvideo -pix_fmt rgb24 -s 1180x600 -r 30 -i - -c:v libx264 -crf 18 -pix_fmt yuv420p dash.mp4
rm -rf ov && mkdir ov && python3 -I $S/make_overlays_v3.py ov >/dev/null

C3_END=$(python3 -c "print(round($T_RAIN-7.2,2))")   # ④ 폭우 관측 자막 시작
beep() { echo "between(t\,$1\,$(python3 -c "print($1+0.25)"))+between(t\,$(python3 -c "print($1+0.4)")\,$(python3 -c "print($1+0.65)"))"; }
ffmpeg -v error -y -i dash.mp4 -ss $SS -t 54 -i b.mp4 -ss $SS -t 54 -i c.mp4 -loop 1 -t 54 -i ov/bg.png \
 -loop 1 -i ov/c0.png -loop 1 -i ov/c1.png -loop 1 -i ov/c2.png -loop 1 -i ov/c3.png -loop 1 -i ov/c4.png -loop 1 -i ov/c5.png -loop 1 -i ov/c6.png -loop 1 -i ov/hl_c.png \
 -f lavfi -t 54 -i "aevalsrc=exprs='0.3*sin(2*PI*880*t)*($(beep $T_BRAKE)+$(beep $T_STOP)+$(beep $T_RAIN))':s=44100:c=stereo" \
 -filter_complex "\
[3:v]fps=30[bg];[0:v]fps=30,setpts=PTS-STARTPTS[dash];\
[1:v]fps=30,setpts=PTS-STARTPTS,crop=1680:945:855:184,scale=668:376[b];[2:v]fps=30,setpts=PTS-STARTPTS,crop=1680:945:855:184,scale=668:376[c];\
[bg][dash]overlay=24:118:shortest=1[v0];[v0][b]overlay=1228:150[v1];[v1][c]overlay=1228:580[v2];\
[v2][4:v]overlay=0:0:enable='lt(t,4)'[v3];[v3][5:v]overlay=0:0:enable='between(t,4,$T_BRAKE)'[v4];\
[v4][6:v]overlay=0:0:enable='between(t,$T_BRAKE,$T_STOP)'[v5];[v5][7:v]overlay=0:0:enable='between(t,$T_STOP,$C3_END)'[v6];\
[v6][8:v]overlay=0:0:enable='between(t,$C3_END,$T_RAIN)'[v7];[v7][9:v]overlay=0:0:enable='between(t,$T_RAIN,44)'[v8];\
[v8][10:v]overlay=0:0:enable='gte(t,44)'[v9];\
[v9][11:v]overlay=0:0:enable='between(t,$T_BRAKE,$T_BRAKE+6)+between(t,$T_STOP,$T_STOP+8)',format=yuv420p[out]" \
 -map "[out]" -map 12:a -t 54 -r 30 -c:v libx264 -crf 19 -c:a aac -b:a 128k main.mp4
for c in intro outro; do
  ffmpeg -v error -y -loop 1 -i ov/$c.png -f lavfi -i anullsrc=r=44100:cl=stereo -t 3 -r 30 -vf format=yuv420p -c:v libx264 -crf 19 -c:a aac -b:a 128k -shortest $c.mp4
done
printf "file 'intro.mp4'\nfile 'main.mp4'\nfile 'outro.mp4'\n" > list.txt
ffmpeg -v error -y -f concat -safe 0 -i list.txt -c copy HyperConnect_시연_v3.mp4
ffprobe -v error -show_entries format=duration,size -of csv=p=0 HyperConnect_시연_v3.mp4
