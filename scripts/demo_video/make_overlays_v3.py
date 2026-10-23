import sys
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
W, H = 1920, 1080
BG, INK, QUIET, BLUE, ACCENT = (16, 18, 22), (235, 238, 242), (150, 156, 166), (80, 150, 255), (230, 60, 60)
F = lambda size, bold=False: ImageFont.truetype(FONT, size, index=6 if bold else 0)

# 배경: 제목 + 오른쪽 패널 라벨
bg = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(bg)
d.text((24, 26), "HyperConnect 프로토타입 · 차량 간 도로 정보 공유", font=F(40, True), fill=INK)
d.text((24, 78), "Pleos Connect Emulator 2대(B, C) + 가상 차량 3대(A, D, E) + 중계 서버", font=F(24), fill=QUIET)
d.text((1228, 112), "차량 B 앱 화면 (에뮬레이터 · 2차로)", font=F(24, True), fill=INK)
d.text((1228, 542), "차량 C 앱 화면 (에뮬레이터 · 2차로 · B 뒤 300m)", font=F(24, True), fill=INK)
bg.save(f"{OUT}/bg.png")

captions = {
    "c0": ("모든 차량을 고유 ID로 상시 추적합니다", "위치·속도·차로를 1초마다 공유 → 서버가 차량 레지스트리(LDM)를 유지"),
    "c1": ("① 차량 B, 80km/h 주행 중 급제동", "B의 앱이 속도·브레이크 신호로 급제동을 감지해 서버에 보고"),
    "c2": ("② 같은 차로의 뒤차 C에만 경고", "앞차 A(이미 지남) · 옆 차로 D · 1.5km 밖 E는 제외"),
    "c3": ("③ B가 멈춘 채 3초 → 2차 사고 위험 자동 보고", "정차 차량은 모든 차로에 위험 → C·D·E에 경고"),
    "c4": ("④ B의 와이퍼 최고 단계 = 폭우 관측", "1대만의 관측은 오탐일 수 있어 확정을 기다립니다"),
    "c5": ("⑤ C도 폭우 관측 → 2대 합의로 '폭우 구간' 확정", "구간 안의 A·D와 1.4km 뒤에서 접근 중인 E에 경고"),
    "c6": ("한 대가 감지하면, 주변 모두가 압니다", "목표: 시야가 없어도 도로를 인지하는 합성 시계(Synthetic Vision)"),
}
for name, (main, sub) in captions.items():
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((24, 736, 1204, 1050), radius=20, fill=(30, 33, 40, 255))
    d.text((60, 790), main, font=F(44, True), fill=INK)
    d.text((60, 870), sub, font=F(28), fill=QUIET)
    im.save(f"{OUT}/{name}.png")

# 경고를 받은 C 패널 강조
hl = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(hl).rectangle((1222, 574, 1902, 958), outline=ACCENT, width=6)
hl.save(f"{OUT}/hl_c.png")


def card(name, title, lines):
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((W // 2, 400), title, font=F(72, True), fill=INK, anchor="mm")
    for i, (text, color) in enumerate(lines):
        d.text((W // 2, 520 + i * 64), text, font=F(36), fill=color, anchor="mm")
    im.save(f"{OUT}/{name}.png")


card("intro", "HyperConnect", [
    ("도로 위 모든 차량이 서로의 눈이 됩니다", QUIET),
    ("시연: 급제동 · 2차 사고 · 폭우 구간 공유", BLUE),
])
card("outro", "Tier 1 → 합성 시계(Synthetic Vision)", [
    ("지금: Pleos SDK 차량 신호로 만드는 도로 정보 공유망", QUIET),
    ("다음: 센서 공유 · V2X 저지연 통신 · 협력 자율주행", QUIET),
])
print("ok")
