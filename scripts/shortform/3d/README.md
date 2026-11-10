# OverRoad 숏폼 (정보 쇼츠 스타일 3D)

- `short.html`: three.js 장면 (검은 띠 제목 + 투박한 3D + 테두리 자막). `renderAt(t)`로 t초 화면을 그린다.
- `render.mjs`: 헤드리스 Chrome으로 프레임을 뽑는다.

```bash
npm install                              # three, puppeteer-core
python3 -m http.server 8765 --bind 127.0.0.1 &
node render.mjs http://127.0.0.1:8765/short.html frames 30          # 전체 프레임
node render.mjs http://127.0.0.1:8765/short.html stills 30 7.3 25.5  # 특정 시각만
ffmpeg -framerate 30 -i frames/f%05d.png -c:v libx264 -pix_fmt yuv420p OverRoad_shorts.mp4
```
장면 타이밍은 내레이션(타입캐스트 '필재') 길이에 맞춰 `renderAt`의 구간을 조정한다.
