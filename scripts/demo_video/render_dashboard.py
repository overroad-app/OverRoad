"""서버 상태 타임라인(timeline.jsonl)으로 대시보드 화면을 30fps 원본 영상 프레임으로 렌더링한다.

stdout으로 RGB 프레임을 내보내고 ffmpeg가 받는다.
  python3 render_dashboard.py <video3 dir> <start τ> <end τ> | ffmpeg -f rawvideo ...
"""
import bisect
import json
import sys

from PIL import Image, ImageDraw, ImageFont

D = sys.argv[1]
T_START, T_END = float(sys.argv[2]), float(sys.argv[3])
W, H = 1180, 600
FPS = 30
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
F = lambda size, bold=False: ImageFont.truetype(FONT, size, index=6 if bold else 0)

BG, PANEL, INK, QUIET = (16, 18, 22), (27, 30, 37), (233, 236, 241), (151, 160, 173)
LANE, MARK = (43, 48, 58), (91, 99, 114)
CAR, ALERT, SRC, WX = (61, 123, 224), (229, 72, 77), (240, 160, 32), (58, 154, 217)
EMULATED = {"B", "C"}
LABEL = {"hard_brake": "급제동", "stopped_vehicle": "정차 차량", "crash": "사고",
         "heavy_rain": "폭우", "fog": "안개", "slippery": "미끄러운 노면"}
WEATHER = {"heavy_rain", "fog", "slippery"}
SHORT_REASON = {"이미 지나침(이벤트가 뒤쪽)": "이미 지나침", "다른 차로(adjacent)": "다른 차로"}

markers = json.load(open(f"{D}/markers.json"))
t0 = markers["rec_start"]
rows = [json.loads(l) for l in open(f"{D}/timeline.jsonl")]
times = [r["t"] - t0 for r in rows]
lat_ref = rows[0]["s"]["vehicles"]["B"]["lat"]
along = lambda lat: (lat - lat_ref) * 111320

LO, HI = -1720.0, 520.0
X = lambda m: 70 + (m - LO) / (HI - LO) * (W - 110)
TOP, LANES = 110, 3
LANE_H = 120
Y = lambda lane: TOP + (lane - 0.5) * LANE_H


def state_at(tau):
    """τ 시점의 상태: 차량 위치는 앞뒤 기록 사이를 선형 보간한다."""
    i = max(1, min(bisect.bisect_left(times, tau), len(rows) - 1))
    a, b = rows[i - 1], rows[i]
    ta, tb = times[i - 1], times[i]
    k = 0 if tb == ta else max(0.0, min(1.0, (tau - ta) / (tb - ta)))
    vs = {}
    for vid, vb in b["s"]["vehicles"].items():
        va = a["s"]["vehicles"].get(vid, vb)
        vs[vid] = {**vb, "lat": va["lat"] + (vb["lat"] - va["lat"]) * k,
                   "speed_kph": (va.get("speed_kph") or 0) + ((vb.get("speed_kph") or 0) - (va.get("speed_kph") or 0)) * k}
    s = b["s"] if k > 0.5 else a["s"]
    return vs, s


def text_c(d, xy, txt, font, fill):
    d.text(xy, txt, font=font, fill=fill, anchor="mm")


def frame(tau):
    im = Image.new("RGB", (W, H), PANEL)
    d = ImageDraw.Draw(im)
    vs, s = state_at(tau)
    now = t0 + tau

    d.text((24, 22), "실시간 도로 상황 · 차량 레지스트리(LDM)", font=F(26, True), fill=INK)
    d.text((24, 60), "진행 방향 →     ■ 에뮬레이터(실제 앱)   □ 가상 차량", font=F(18), fill=QUIET)

    # 기상 구간 (확정)
    for e in s["events"]:
        if e["type"] in WEATHER and e["ts"] <= now:
            c = along(e["lat"])
            band = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            ImageDraw.Draw(band).rectangle((X(c - 500), TOP, X(c + 500), TOP + LANES * LANE_H), fill=WX + (55,))
            im.paste(Image.alpha_composite(im.convert("RGBA"), band).convert("RGB"))
            d = ImageDraw.Draw(im)
            text_c(d, (X(c), TOP - 16), f"{LABEL[e['type']]} 구간 · {len(e.get('reporters', []))}대 관측으로 확정",
                   F(19, True), WX)

    # 차로
    for i in range(LANES + 1):
        y = TOP + i * LANE_H
        if i in (0, LANES):
            d.line((20, y, W - 20, y), fill=QUIET, width=2)
        else:
            for x in range(20, W - 20, 30):
                d.line((x, y, x + 16, y), fill=MARK, width=2)
    for i in range(1, LANES + 1):
        d.text((26, TOP + (i - 1) * LANE_H + 8), f"{i}차로", font=F(15), fill=QUIET)
    # 거리 눈금
    for m in range(-1500, 501, 250):
        d.line((X(m), TOP + LANES * LANE_H, X(m), TOP + LANES * LANE_H + 8), fill=MARK, width=1)
        text_c(d, (X(m), TOP + LANES * LANE_H + 24), f"{m:+d}m" if m else "0", F(14), QUIET)

    # 확정 대기 중인 기상 관측
    for o in s.get("weather", []):
        if o.get("rain", 0) >= 3 and not any(e["type"] == "heavy_rain" for e in s["events"]):
            v = vs.get(o["vehicle"])
            if v:
                text_c(d, (X(along(v["lat"])), Y(v["lane"]) + 54), "폭우 관측 · 1대 (확정 대기)", F(16, True), WX)

    # 즉시성 이벤트 지점
    recent_events = [e for e in s["events"] if 0 <= now - e["ts"] < 6]
    for e in recent_events:
        if e["type"] in WEATHER:
            continue
        x, y = X(along(e["lat"])), Y(e["lane"] or 2)
        col = SRC if e["type"] == "hard_brake" else ALERT
        ring = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(ring).ellipse((x - 40, y - 40, x + 40, y + 40), fill=col + (70,))
        im.paste(Image.alpha_composite(im.convert("RGBA"), ring).convert("RGB"))
        d = ImageDraw.Draw(im)

    warned = {a["to"]: a for a in s["alerts"] if 0 <= now - a["ts"] < 6}
    latest = recent_events[-1] if recent_events else None
    for vid, v in sorted(vs.items()):
        x, y = X(along(v["lat"])), Y(v["lane"] or 2)
        source = latest is not None and latest["vehicle"] == vid and latest["type"] not in WEATHER
        col = ALERT if vid in warned else SRC if source else CAR
        if vid in EMULATED:
            d.rounded_rectangle((x - 30, y - 18, x + 30, y + 18), radius=8, fill=col)
            text_c(d, (x, y), vid, F(22, True), (255, 255, 255))
        else:
            d.rounded_rectangle((x - 30, y - 18, x + 30, y + 18), radius=8, outline=col, width=3, fill=PANEL)
            text_c(d, (x, y), vid, F(22, True), col)
        text_c(d, (x, y + 32), f"{round(v['speed_kph'] or 0)}km/h", F(15), INK)
        if vid in warned:
            a = warned[vid]
            where = f"{a['distance_m']}m 앞" if a["distance_m"] > 0 else "현재 구간"
            text_c(d, (x, y - 34), f"경고 · {LABEL[a['type']]} {where}", F(18, True), ALERT)
        elif latest and latest.get("decisions", {}).get(vid) and latest["decisions"][vid] != "경고":
            why = latest["decisions"][vid]
            text_c(d, (x, y - 34), "제외 · " + SHORT_REASON.get(why, why.replace("반경 밖", "반경 밖")), F(16), QUIET)
        if source:
            text_c(d, (x, y + 54), f"{LABEL[latest['type']]} 보고", F(18, True), SRC)
        wx = next((e for e in reversed(s["events"]) if e["type"] in WEATHER and e["ts"] <= now), None)
        if wx and vid in wx.get("reporters", []):
            text_c(d, (x, y + 54), f"{LABEL[wx['type']]} 관측", F(17, True), WX)
    return im


n = int((T_END - T_START) * FPS)
out = sys.stdout.buffer
for i in range(n):
    out.write(frame(T_START + i / FPS).tobytes())
