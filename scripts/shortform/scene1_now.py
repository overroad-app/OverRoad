"""숏폼 장면 1 (카툰판): 줄 공책 위 펜 낙서 느낌.
장애물을 앞차가 먼저 보고 피하고, 앞차에 가려 있던 뒤차는 늦게 보고 깜짝 놀라 급히 피한다 (OverRoad 없는 지금의 도로).
1080x1920 세로, 30fps. 선은 3프레임마다 살짝 흔들려(라인 보일) 손으로 그린 느낌을 낸다."""
import math, os, random, sys
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
W, H, SS = 1080, 1920, 2
FPS, DUR = 30, 7.5
os.makedirs(OUT, exist_ok=True)

PAPER = (251, 250, 244); RULE = (196, 216, 236); MARGIN = (239, 160, 160)
INK = (28, 28, 30); RED = (226, 54, 54); GREEN = (40, 160, 80); GREY = (120, 120, 120)
PEN = "/System/Library/AssetsV2/com_apple_MobileAsset_Font8/1fb44cf128344a11e654a43ccd7a45a68026bf5d.asset/AssetData/NanumScript.ttc"
def font(px): return ImageFont.truetype(PEN, int(px * SS), index=1)

LANE_W = 240
ROAD_X0 = W / 2 - LANE_W * 1.5 + 40
def lane_x(l): return ROAD_X0 + LANE_W * (l + 0.5)
OBST = (lane_x(1), 600)

def ease(t):
    t = max(0.0, min(1.0, t)); return t * t * (3 - 2 * t)

SPEED = 330
class Car:
    def __init__(self, y0, see_dist, swerve_s, delay, hard):
        self.y0, self.see_dist, self.swerve_s, self.delay, self.hard = y0, see_dist, swerve_s, delay, hard
        self.seen = None
    def state(self, t, blocker=None):
        y = self.y0 - SPEED * t
        visible = True
        if blocker is not None and abs(blocker[0] - lane_x(1)) < LANE_W * 0.45 and blocker[1] < y:
            visible = False
        if self.seen is None and visible and y - OBST[1] <= self.see_dist:
            self.seen = t
        x, ang, brake = lane_x(1), 0.0, False
        if self.seen is not None:
            k = (t - self.seen - self.delay) / self.swerve_s
            x = lane_x(1) - LANE_W * ease(k)
            ang = math.sin(math.pi * max(0, min(1, k))) * (26 if self.hard else 18)
            brake = self.hard and 0 <= t - self.seen < self.swerve_s + 0.6
        return x, y, ang, brake

car1 = Car(1450, 430, 0.9, 0.2, False)
car2 = Car(1700, 650, 0.45, 0.12, True)

# ---------- 손그림 도구 ----------
seed_base = 0
def wob(pts, amp=2.2, closed=False, step=14):
    """점들 사이를 잘게 나누고 살짝 흔든다 (프레임마다 같은 seed_base면 같은 흔들림)."""
    rnd = random.Random(seed_base + int(sum(p[0] + p[1] for p in pts)) % 9973)
    seq = pts + ([pts[0]] if closed else [])
    out = []
    for (x0, y0), (x1, y1) in zip(seq, seq[1:]):
        n = max(1, int(math.hypot(x1 - x0, y1 - y0) / step))
        for i in range(n):
            f = i / n
            out.append((x0 + (x1 - x0) * f + rnd.uniform(-amp, amp), y0 + (y1 - y0) * f + rnd.uniform(-amp, amp)))
    out.append(seq[-1])
    return out

def S(v): return v * SS
def SP(pts): return [(x * SS, y * SS) for x, y in pts]

def ink_line(d, pts, w=4.5, col=INK, amp=2.2, closed=False):
    d.line(SP(wob(pts, amp, closed)), fill=col, width=int(S(w)), joint="curve")

def rot(cx, cy, ang, px, py):
    a = math.radians(-ang)
    return (cx + px * math.cos(a) - py * math.sin(a), cy + px * math.sin(a) + py * math.cos(a))

def round_rect_pts(w, h, r, n=5):
    pts = []
    for cx, cy, a0 in ((w / 2 - r, -h / 2 + r, -90), (w / 2 - r, h / 2 - r, 0), (-w / 2 + r, h / 2 - r, 90), (-w / 2 + r, -h / 2 + r, 180)):
        for i in range(n + 1):
            a = math.radians(a0 + 90 * i / n)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts

def ellipse_pts(cx, cy, rx, ry, n=18):
    return [(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]

# ---------- 그림 ----------
def draw_paper(d):
    for y in range(140, H, 64):
        d.line([(0, S(y)), (S(W), S(y))], fill=RULE, width=S(2))
    d.line([(S(96), 0), (S(96), S(H))], fill=MARGIN, width=S(3))

def draw_road(d, t):
    for ex in (ROAD_X0, ROAD_X0 + LANE_W * 3):
        ink_line(d, [(ex, -20), (ex, H + 20)], w=5)
    off = 0  # 카메라는 고정, 차선만 그대로
    for l in (1, 2):
        lx = ROAD_X0 + LANE_W * l
        for y in range(-40 + off, H + 60, 120):
            ink_line(d, [(lx, y), (lx, y + 52)], w=4, amp=1.6)

def draw_box(d, t):
    """도로에 떨어진 큰 상자 (테이프 X) + 쿵 표시."""
    x, y = OBST
    w, h = 150, 116
    body = [rot(x, y, -6, px, py) for px, py in round_rect_pts(w, h, 10)]
    d.polygon(SP(body), fill=(222, 184, 135))
    ink_line(d, body, w=5, closed=True)
    ink_line(d, [rot(x, y, -6, -w / 2 + 8, -h / 2 + 8), rot(x, y, -6, w / 2 - 8, h / 2 - 8)], w=3.5, col=(120, 82, 40))
    ink_line(d, [rot(x, y, -6, w / 2 - 8, -h / 2 + 8), rot(x, y, -6, -w / 2 + 8, h / 2 - 8)], w=3.5, col=(120, 82, 40))
    # 떨어진 흔적 (작은 선들)
    for a in (-150, -120, -60, -30):
        r0, r1 = 95, 120
        ink_line(d, [(x + r0 * math.cos(math.radians(a)), y + r0 * math.sin(math.radians(a)) * 0.8),
                     (x + r1 * math.cos(math.radians(a)), y + r1 * math.sin(math.radians(a)) * 0.8)], w=3.5)
    d.text((S(x + 84), S(y - 86)), "떨어진 짐", font=font(46), fill=INK)

def draw_car(d, x, y, ang, brake, surprise, look):
    w, h = 110, 180
    body = [rot(x, y, ang, px * (0.86 if py < 0 else 1.0), py) for px, py in round_rect_pts(w, h, 26)]
    # 그림자 (연필 빗금 느낌 대신 옅은 회색)
    d.polygon(SP([(px + 7, py + 9) for px, py in body]), fill=(226, 224, 216))
    d.polygon(SP(body), fill=(255, 255, 255))
    ink_line(d, body, w=5.5, closed=True)
    # 앞유리 선 + 눈 (차가 캐릭터)
    ink_line(d, [rot(x, y, ang, -40, -40), rot(x, y, ang, 0, -48), rot(x, y, ang, 40, -40)], w=4)
    er = 15 + 9 * surprise              # 놀라면 눈이 커진다
    for sx in (-24, 24):
        ex, ey = rot(x, y, ang, sx, -70)
        eye = ellipse_pts(ex, ey, er * 0.8, er)
        d.polygon(SP(eye), fill=(255, 255, 255))
        ink_line(d, eye, w=3.5, amp=1.0, closed=True)
        px_, py_ = ex + look[0] * er * 0.35, ey + look[1] * er * 0.4 - er * 0.25
        pr = 5.5 - 2 * surprise
        d.ellipse([S(px_ - pr), S(py_ - pr), S(px_ + pr), S(py_ + pr)], fill=INK)
    # 바퀴 자국 (짧은 선)
    for sx, sy in ((-60, -50), (60, -50), (-60, 50), (60, 50)):
        ink_line(d, [rot(x, y, ang, sx, sy - 18), rot(x, y, ang, sx, sy + 18)], w=7, amp=0.8)
    if brake:
        for sx in (-32, 32):
            c = rot(x, y, ang, sx, 92)
            d.ellipse([S(c[0] - 14), S(c[1] - 8), S(c[0] + 14), S(c[1] + 8)], fill=RED)
        # 끼익 선
        for k, sx in enumerate((-60, 60)):
            c = rot(x, y, ang, sx, 110)
            ink_line(d, [c, (c[0] + (k * 2 - 1) * 6, c[1] + 40), (c[0] - (k * 2 - 1) * 4, c[1] + 80)], w=4, col=GREY)

def bubble(d, x, y, text, size, col=INK):
    tw = d.textlength(text, font=font(size)) / SS
    bx0, by0, bx1, by1 = x - tw / 2 - 26, y - size * 0.9, x + tw / 2 + 26, y + size * 0.55
    pts = round_rect_pts(bx1 - bx0, by1 - by0, 26)
    pts = [(px + (bx0 + bx1) / 2, py + (by0 + by1) / 2) for px, py in pts]
    d.polygon(SP(pts), fill=(255, 255, 255))
    ink_line(d, pts, w=4, closed=True)
    d.text((S(x - tw / 2), S(y - size * 0.82)), text, font=font(size), fill=col)

def draw_reaction(d, s, since, hard):
    if since is None or since < 0 or since > 1.25: return
    x, y = s[0], s[1]
    a = since
    if a < 1.2:
        # !! 와 땀방울
        ink_line(d, [(x + 72, y - 150), (x + 64, y - 112)], w=7, col=RED)
        d.ellipse([S(x + 60), S(y - 102), S(x + 70), S(y - 92)], fill=RED)
        if hard:
            ink_line(d, [(x + 96, y - 158), (x + 88, y - 118)], w=7, col=RED)
            d.ellipse([S(x + 84), S(y - 108), S(x + 94), S(y - 98)], fill=RED)
            drop = [(x - 78, y - 120), (x - 88, y - 98), (x - 78, y - 88), (x - 68, y - 98)]
            d.polygon(SP(drop), fill=(120, 190, 240))
            ink_line(d, drop, w=3, closed=True, amp=0.8)
    text, size = (("으악!!", 64) if hard else ("어? 짐이다!", 52))
    # 말풍선: 앞차는 왼쪽 위(피해 가는 쪽), 뒤차는 오른쪽 빈 차로 — 상자와 다른 차를 가리지 않게
    if hard:
        bubble(d, x + 190, y - 30, text, size, RED)
    else:
        bubble(d, x - 175, y - 175, text, size, INK)

def swerve_trail(d, c, t):
    if c.seen is None: return
    t0 = c.seen + c.delay
    pts = []
    for i in range(0, 26):
        tt = t0 + i * 0.06
        if tt > t: break
        pts.append((lane_x(1) - LANE_W * ease((tt - c.seen - c.delay) / c.swerve_s), c.y0 - SPEED * tt + 120))
    for i in range(0, len(pts) - 1, 2):
        ink_line(d, [pts[i], pts[i + 1]], w=4.5, col=GREEN, amp=1.2)

def frame(n):
    global seed_base
    seed_base = (n // 3) * 7919          # 3프레임마다 선이 살짝 바뀐다
    t = n / FPS
    im = Image.new("RGB", (W * SS, H * SS), PAPER)
    d = ImageDraw.Draw(im)
    draw_paper(d)
    draw_road(d, t)
    draw_box(d, t)
    s1 = car1.state(t)
    s2 = car2.state(t, blocker=s1)
    swerve_trail(d, car1, t)
    swerve_trail(d, car2, t)
    def look(c):
        return (-0.9, -0.2) if c.seen is not None and t - c.seen < 1.0 else (0, -0.6)
    def surprise(c):
        if c.seen is None: return 0.0
        k = t - c.seen
        return max(0.0, 1 - max(0, k - 0.6) / 0.6) * (1.0 if c.hard else 0.6)
    draw_car(d, *s2, surprise(car2), look(car2))
    draw_car(d, *s1, surprise(car1), look(car1))
    draw_reaction(d, s1, None if car1.seen is None else t - car1.seen, False)
    draw_reaction(d, s2, None if car2.seen is None else t - car2.seen, True)
    # 머리말 (공책 위 손글씨)
    d.text((S(120), S(52)), "지금의 도로...", font=font(78), fill=INK)
    d.text((S(124), S(150)), "다들 눈으로 직접 봐야 피한다", font=font(48), fill=(70, 70, 70))
    return im.resize((W, H), Image.LANCZOS)

for n in range(int(FPS * DUR)):
    frame(n).save(f"{OUT}/f{n:04d}.png")
print("frames", int(FPS * DUR), "car1 seen", round(car1.seen, 2), "car2 seen", round(car2.seen, 2))
