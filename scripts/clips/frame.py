"""클립용 투명 오버레이(PNG): 위쪽 제목 띠, 차량 B·C 라벨. ffmpeg에 글자 그리기(drawtext)가 없어 그림으로 얹는다.
  python3 frame.py "<제목>" <out.png>
"""
import sys
from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
W, TOP, H = 1280, 120, 720  # 가로, 제목 띠 높이, 차량 화면 하나의 높이
title, out = sys.argv[1], sys.argv[2]
img = Image.new("RGBA", (W, TOP + 2 * H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rectangle((0, 0, W, TOP), fill=(18, 20, 26, 255))
f_title = ImageFont.truetype(FONT, 48, index=6)
tw = d.textlength(title, font=f_title)
d.text(((W - tw) / 2, 32), title, font=f_title, fill="white")
f_label = ImageFont.truetype(FONT, 34, index=6)
for y, label in ((TOP, "차량 B"), (TOP + H, "차량 C (B 뒤)")):
    lw = d.textlength(label, font=f_label)
    d.rounded_rectangle((16, y + 16, 16 + lw + 28, y + 16 + 56), radius=10, fill=(0, 0, 0, 170))
    d.text((30, y + 24), label, font=f_label, fill="white")
img.save(out)
