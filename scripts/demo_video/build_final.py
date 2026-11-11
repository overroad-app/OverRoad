"""OverRoad 최종 시연 영상 조립.

장면 영상(에뮬레이터 녹화) 12개 + 내레이션 한 파일 → 1920x1080 영상 하나.
- 내레이션은 무음 구간으로 14묶음(시작, 장면 12개, 마무리)으로 나눈다.
- 앱이 직접 내는 경고 음성과 겹치지 않도록, 내레이션은 장면의 첫 화면을 멈춘 채 먼저 들려주고
  (앱 소리가 늦게 나오는 장면은 그만큼 장면 위에 겹친다) 그다음 장면을 원래 소리로 재생한다.
- 왼쪽 패널에 장면 번호·제목·자막을 그린다(ffmpeg에 글자 필터가 없어 PIL로 그림을 만들어 얹는다).

사용: python3 build_final.py <장면 폴더> <내레이션.mp3> <네 층위 표.png> <작업 폴더> <출력.mp4>
"""
import re
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SRC, NARR, TIER_PNG, WORK, OUT = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), Path(sys.argv[5])
WORK.mkdir(parents=True, exist_ok=True)

W, H, FPS = 1920, 1080, 30
CROP_TOP = 146                 # 장면 영상 위쪽의 녹화용 제목 줄(내부 rev 표기)을 잘라 낸다
CLIP_W = round(1280 * H / (1560 - CROP_TOP) / 2) * 2   # 높이 1080에 맞춘 장면 폭
PANEL_W = W - CLIP_W
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
F = lambda size, bold=False: ImageFont.truetype(FONT, size, index=6 if bold else 0)
BG, INK, QUIET, TEAL, LINE = (15, 23, 42), (241, 245, 249), (148, 163, 184), (45, 212, 191), (51, 65, 85)
LEAD = 0.3                     # 장면이 바뀐 뒤 내레이션이 시작하기까지
TAIL = 0.3                     # 내레이션이 끝난 뒤 앱 소리가 나오기까지 두는 여유

# 장면 뒤쪽의 변화 없는 구간을 잘라 낼 끝 시각(초). 화면 변화와 앱 소리가 끝난 뒤 1초쯤 남긴다
CUT = {"01": 5.6, "02": 12.3, "03": 7.0, "07": 6.0, "08": 11.6, "09": 11.5, "10": 25.3, "12": 15.0, "11": 21.6, "13": 11.5}
END_PAD = 0.15

# (장면 파일 앞 번호, 제목, 아래 설명, 자막 문장들, 소리 키움 dB)
SCENES = [
    ("01", "급제동 경고", "Pleos Connect 에뮬레이터 2대 · 실제 동작 화면",
     ["앞차가 급하게 브레이크를 밟으면,\n곧 그 자리에 닿을 뒤차에게만 바로 알립니다.",
      "브레이크부터 뒤차 화면까지,\n평균 0.64초입니다."], 0),
    ("02", "2차 사고 방지", "Pleos Connect 에뮬레이터 2대 · 실제 동작 화면",
     ["사고가 나면 멀리서 먼저 알려 주고,",
      "2차 사고가 실제로 나는 정체 구간의 맨 뒤에\n다가오면, 음성으로 한 번 더 경고합니다."], 0),
    ("03", "기상 위험", "Pleos Connect 에뮬레이터 2대 · 실제 동작 화면",
     ["차 한 대가 와이퍼를 켠 것으로는\n판단하지 않습니다.",
      "한 지역의 여러 차가 거의 동시에 켤 때만\n폭우 구간으로 확정하고, 뒤따르는 차에게 알립니다."], 0),
    ("06", "긴급차량 길 터주기", "Pleos Connect 에뮬레이터 2대 · 실제 동작 화면",
     ["구급차와 소방차, 경찰차가 다가오면\n사이렌이 들리기 전에 알려 줍니다.",
      "같은 차로면 비켜 달라고,\n다른 차로면 그대로 가 달라고 안내합니다."], 20),   # rev7 녹화는 음량이 낮게 잡혀(최대 -23dB) 다른 장면 수준으로 키운다
    ("07", "차로 변경 의도 공유", "Pleos Connect 에뮬레이터 2대 · 실제 동작 화면",
     ["깜빡이를 켜면,\n옆 차로에서 다가오는 차에게만 알립니다.",
      "들어가는 데 성공하면, 양보해 준 뒤차에게\n감사 인사가 자동으로 전해집니다."], 0),
    ("08", "데이터 공유 동의 · 소리 이모트", "Pleos Connect 에뮬레이터 2대 · 실제 동작 화면",
     ["처음 켜면 무엇을 보내는지 알리고,\n동의한 뒤에만 연결됩니다.",
      "바로 앞차와 뒤차에게는 버튼 한 번으로\n고마움과 미안함을 소리로 전할 수 있습니다.",
      "같은 차에게는 3분에 한 번만 보낼 수 있습니다."], 12),
    ("09", "OverRoad 기본 화면 · 정지", "Pleos Connect 에뮬레이터 2대 · 실제 동작 화면",
     ["이 연결을 눈으로 보여 주는 화면입니다.",
      "연결된 차들이 내 차에서 뻗는 선으로 보입니다.\n가까운 차는 진하게, 먼 차는 옅게.",
      "참여한 차들이 1초에 5번 보내는\n위치와 깜빡이, 감속 상태를 그대로 그렸습니다."], 12),
    ("10", "OverRoad 기본 화면 · 주행", "Pleos Connect 에뮬레이터 2대 · 위 기본 화면, 아래 내비",
     ["경고와 인사는 보낸 차에서 내 차로,\n선을 따라 들어옵니다.",
      "급제동한 앞차에서, 지나간 구급차에서.",
      "어느 차가 보냈는지 한눈에 알 수 있습니다."], 0),
    ("05", "EV 충전 예측", "Pleos Connect 에뮬레이터 2대 · 충전소 상태는 시연값",
     ["충전소에 도착했을 때 내가 몇 번째인지,\n언제 충전이 끝나는지 미리 알려 줍니다.",
      "충전을 마친 차의 기록과 별점이\n다음 운전자의 예측에 쓰입니다."], 0),
    ("12", "가장 빨리 충전되는 곳", "Pleos Connect 에뮬레이터 2대 · 충전소 상태는 시연값",
     ["목적지를 정하지 않아도, 충전이 가장 빨리 끝나는\n근처 충전소를 늘 계산해 둡니다.",
      "배터리가 부족할 때,\n한 번만 누르면 바로 그곳으로 안내합니다."], 0),
    ("11", "이모트 스토어", "Pleos Connect 에뮬레이터 2대 · 결제는 SDK 출시 후",
     ["이모트 팩마다 소리와 색이 다르고,\n받는 차는 보낸 차의 팩 소리를 듣습니다.",
      "길을 열어 준 차에게는, 지나간 구급차의\n감사 인사가 자동으로 전해집니다."], 17),   # rev3 녹화는 음량이 낮게 잡혔다(최대 -29dB)
    ("13", "다음 단계 미리보기 · Tier 2~3", "가상 센서 데이터로 확인한 미리보기 화면",
     ["지금은 위치 정보만 쓰지만,\n카메라와 레이더, UWB가 더해지면",
      "앱에 참여하지 않은 차까지 화면에 나타나고,\n서로를 차로 단위로 알아보게 됩니다."], 0),
]
# 장면에 덧붙이는 화면 설명 (왼쪽 패널 아래쪽에 계속 떠 있다): (머리말, 본문) 목록
EXTRA = {
    "10": [("위 · 차량 B", "OverRoad 기본 화면. 주행 중에도 켜 둔 상위 Tier 미리보기입니다."),
           ("아래 · 차량 C", "같은 순간의 내비 화면. 지금(Tier 1)의 실제 주행 모습이며, 경고는 내비 위 상단 알림으로 뜹니다."),
           ("왜 나눠 보여 주나", "지금의 Pleos Connect는 화면에 앱을 하나만 띄울 수 있어, 내비와 기본 화면을 함께 보여 줄 수 없습니다. "
                             "이 기술적 제약 때문에 임시로 두 화면을 나눠 구성했습니다. Tier 2에서는 내비 위에 떠서 알려 주고, Tier 3에서 내비와 하나로 통합합니다.")],
}
INTRO = ["지금까지 차는 저마다 혼자 보고,\n혼자 판단했습니다.",
         "OverRoad는 도로 위의 차들을 하나의 군집으로 잇는\n초연결 아키텍처입니다.",
         "국내 신차 네 대 중 세 대가\n현대차그룹 차량이기에 가능한 일입니다.",
         "최종 지향점은, 이 연결망이\n자율주행을 이루는 한 축이 되는 것입니다.",
         "그중 지금의 차량 신호와 이동통신만으로 구현할 수 있는 것이\nTier 1이어서, 오늘은 이것을 보여 드립니다."]
OUTRO = ["이 기능들은 도로 위 차들의 초연결에서\n자연스럽게 나왔습니다.",
         "지금은 따로 여는 앱이지만, 내비게이션과 합쳐지고,\n마지막에는 자율주행과 하나가 됩니다.",
         "그 첫 단계는 새 장비 없이,\n지금 달리는 차들만으로 시작할 수 있습니다."]


def run(*args, capture=False):
    r = subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True)
    return r.stderr if capture else None


def duration(path):
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                                check=True, capture_output=True, text=True).stdout)


def silences(path, noise, d):
    err = run("ffmpeg", "-hide_banner", "-i", path, "-af", f"silencedetect=noise={noise}dB:d={d}", "-f", "null", "-", capture=True)
    starts = [float(x) for x in re.findall(r"silence_start: ([0-9.]+)", err)]
    ends = [float(x) for x in re.findall(r"silence_end: ([0-9.]+)", err)]
    return list(zip(starts, ends + [duration(path)] * (len(starts) - len(ends))))


# ── 1. 내레이션을 묶음과 문장으로 나눈다 ───────────────────────────────
total = duration(NARR)
sil = [(s, e) for s, e in silences(NARR, -38, 0.25) if s > 0.05]
long_gaps = [(s, e) for s, e in sil if e - s >= 1.0 and e < total - 0.05]
assert len(long_gaps) == 13, f"묶음 사이 쉼이 13개여야 하는데 {len(long_gaps)}개"
edges = [0.0] + [t for g in long_gaps for t in g] + [min(total, max(s for s, e in sil if e >= total - 0.05) if any(e >= total - 0.05 for s, e in sil) else total)]
blocks = [(edges[2 * i], edges[2 * i + 1]) for i in range(14)]
PAD = 0.12
narr = []   # 묶음마다 (파일, 길이, 문장 시작 시각들)
for i, (a, b) in enumerate(blocks):
    a2, b2 = max(0, a - PAD), min(total, b + PAD)
    f = WORK / f"n{i:02d}.wav"
    run("ffmpeg", "-v", "error", "-y", "-ss", f"{a2:.3f}", "-to", f"{b2:.3f}", "-i", NARR, "-af", "volume=3dB,alimiter=limit=0.95", "-ar", "48000", "-ac", "2", f)
    inner = [(s + e) / 2 - a2 for s, e in sil if a < s and e < b and e - s < 1.0]
    narr.append((f, b2 - a2, [0.0] + inner))
captions = [INTRO] + [s[3] for s in SCENES] + [OUTRO]
for i, (n, c) in enumerate(zip(narr, captions)):
    assert len(n[2]) == len(c), f"묶음 {i}: 쉼으로 나눈 문장 {len(n[2])}개, 자막 {len(c)}개"
print("내레이션 묶음 길이:", " ".join(f"{n[1]:.1f}" for n in narr))


# ── 2. 그림 ───────────────────────────────────────────────────────────
def wrap(d, text, font, width):
    """낱말 단위로 줄을 바꾼다."""
    lines, cur = [], ""
    for w in text.split(" "):
        t = (cur + " " + w).strip()
        if d.textlength(t, font=font) <= width:
            cur = t
        else:
            lines.append(cur)
            cur = w
    return lines + [cur]


def panel(idx, title, note, text, extra=None):
    """왼쪽 패널 한 장 (장면 번호, 제목, 자막, 화면 설명, 아래 설명)."""
    im = Image.new("RGB", (PANEL_W, H), BG)
    d = ImageDraw.Draw(im)
    x = 64
    d.text((x, 70), "OverRoad", font=F(34, True), fill=TEAL)
    d.text((PANEL_W - x, 76), f"{idx:02d} / {len(SCENES)}", font=F(28), fill=QUIET, anchor="ra")
    d.line((x, 132, PANEL_W - x, 132), fill=LINE, width=2)
    size = 60
    while d.textlength(title, font=F(size, True)) > PANEL_W - 2 * x:
        size -= 2
    d.text((x, 190), title, font=F(size, True), fill=INK)
    if text:
        size = 38
        while max(d.textlength(l, font=F(size)) for l in text.split("\n")) > PANEL_W - 2 * x:
            size -= 1
        d.multiline_text((x, 400), text, font=F(size), fill=INK, spacing=22)
    if extra:
        blocks = [(h, wrap(d, b, F(25), PANEL_W - 2 * x - 40)) for h, b in extra]
        hgt = sum(42 + 36 * len(ls) + 16 for _, ls in blocks) + 24
        y = H - 150 - hgt
        d.rounded_rectangle((x, y, PANEL_W - x, y + hgt), radius=16, fill=(30, 41, 59))
        y += 20
        for h, ls in blocks:
            d.text((x + 20, y), h, font=F(26, True), fill=TEAL)
            y += 42
            for l in ls:
                d.text((x + 20, y), l, font=F(25), fill=(203, 213, 225))
                y += 36
            y += 16
    d.line((x, H - 128, PANEL_W - x, H - 128), fill=LINE, width=2)
    d.text((x, H - 100), note, font=F(25), fill=QUIET)
    return im


def caption_bar(im, text, y0):
    d = ImageDraw.Draw(im)
    d.multiline_text((W // 2, (y0 + H) // 2), text, font=F(40), fill=INK, anchor="mm", align="center", spacing=14)


def title_card(text):
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((W // 2, 360), "OverRoad", font=F(150, True), fill=INK, anchor="mm")
    d.text((W // 2, 500), "도로 위 차들의 초연결", font=F(50), fill=TEAL, anchor="mm")
    d.line((W // 2 - 220, 590, W // 2 + 220, 590), fill=LINE, width=2)
    if text:
        caption_bar(im, text, 640)
    return im


def tier_card(text):
    im = Image.new("RGB", (W, H), BG)
    bar = 150
    t = Image.open(TIER_PNG).convert("RGB")
    h = H - bar
    w = round(t.width * h / t.height)
    im.paste(t.resize((w, h), Image.LANCZOS), ((W - w) // 2, 0))
    caption_bar(im, text, h)
    return im


def stills_video(frames, audio, out, tail):
    """(그림, 시작 시각) 목록과 소리 하나로 영상 한 토막을 만든다."""
    total_d = audio[1] + LEAD + tail
    args, filt = ["ffmpeg", "-v", "error", "-y"], []
    for k, (img, start) in enumerate(frames):
        p = WORK / f"{out.stem}_{k}.png"
        img.save(p)
        end = frames[k + 1][1] if k + 1 < len(frames) else total_d
        args += ["-loop", "1", "-framerate", FPS, "-t", f"{end - start:.3f}", "-i", p]
        filt.append(f"[{k}:v]format=yuv420p,setsar=1[v{k}]")
    n = len(frames)
    args += ["-i", audio[0]]
    filt.append("".join(f"[v{k}]" for k in range(n)) + f"concat=n={n}:v=1:a=0[v]")
    filt.append(f"[{n}:a]adelay={int(LEAD * 1000)}:all=1,apad=whole_dur={total_d:.3f}[a]")
    args += ["-filter_complex", ";".join(filt), "-map", "[v]", "-map", "[a]", "-t", f"{total_d:.3f}",
             "-r", FPS, "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", out]
    run(*args)
    return total_d


parts, report = [], []

# 시작: 제목(문장 1~3) → 네 층위 표(문장 4~5)
f, d, t = narr[0]
frames = [(title_card(""), 0.0)] + [(title_card(INTRO[k]), LEAD + t[k]) for k in range(3)] \
    + [(tier_card(INTRO[k]), LEAD + t[k]) for k in (3, 4)]
p = WORK / "part00.mp4"
report.append(("시작", stills_video(frames, (f, d), p, 0.5)))
parts.append(p)

# 장면
for i, (num, title, note, caps, gain) in enumerate(SCENES, 1):
    clip = next(SRC.glob(f"{num}_*.mp4"))
    cd = min(duration(clip), CUT.get(num, 1e9))
    has_audio = bool(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", str(clip)],
                                    capture_output=True, text=True).stdout.strip())
    # 앱 소리가 처음 나는 시각 (소리 없는 장면은 끝까지)
    first = cd
    if has_audio:
        sl = silences(clip, -40, 0.3)
        first = 0.0 if not sl or sl[0][0] > 0.05 else sl[0][1]
        if first >= cd - 0.05:
            first = cd
    f, nd, t = narr[i]
    overlap = max(0.0, min(first - TAIL, nd))            # 장면 위에 겹쳐도 되는 내레이션 길이
    pre = max(0.6, LEAD + nd - overlap)                  # 첫 화면을 멈춰 두는 시간
    start_n = pre + overlap - nd                         # 내레이션 시작 시각 (>= LEAD)
    total_d = pre + cd + END_PAD
    ex = EXTRA.get(num)
    imgs = [(panel(i, title, note, "", ex), 0.0)] + [(panel(i, title, note, caps[k], ex), start_n + t[k]) for k in range(len(caps))] \
        + [(panel(i, title, note, "", ex), start_n + nd + 0.6)]
    args, filt = ["ffmpeg", "-v", "error", "-y", "-t", f"{cd:.3f}", "-i", clip], []
    for k, (img, _) in enumerate(imgs):
        pth = WORK / f"s{i:02d}_{k}.png"
        img.save(pth)
        args += ["-loop", "1", "-framerate", FPS, "-t", f"{total_d:.3f}", "-i", pth]
    args += ["-i", f]
    ni = len(imgs) + 1
    filt.append(f"[0:v]crop=1280:{1560 - CROP_TOP}:0:{CROP_TOP},scale={CLIP_W}:{H},fps={FPS},"
                f"tpad=start_mode=clone:start_duration={pre:.3f}:stop_mode=clone:stop_duration={END_PAD},setsar=1[c]")
    filt.append(f"color=c=0x0f172a:s={W}x{H}:r={FPS}:d={total_d:.3f}[bg]")
    filt.append(f"[bg][c]overlay={PANEL_W}:0[b0]")
    for k, (_, st) in enumerate(imgs):
        en = imgs[k + 1][1] if k + 1 < len(imgs) else total_d + 1
        filt.append(f"[b{k}][{k + 1}:v]overlay=0:0:enable='between(t,{st:.3f},{en - 0.001:.3f})'[b{k + 1}]")
    filt.append(f"[{ni}:a]adelay={int(start_n * 1000)}:all=1[na]")
    if has_audio:
        filt.append(f"[0:a]aresample=48000,volume={gain}dB,adelay={int(pre * 1000)}:all=1[ca]")
        filt.append(f"[na][ca]amix=inputs=2:duration=longest:normalize=0,alimiter=limit=0.95,apad=whole_dur={total_d:.3f}[a]")
    else:
        filt.append(f"[na]apad=whole_dur={total_d:.3f}[a]")
    p = WORK / f"part{i:02d}.mp4"
    args += ["-filter_complex", ";".join(filt), "-map", f"[b{len(imgs)}]", "-map", "[a]", "-t", f"{total_d:.3f}",
             "-r", FPS, "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", p]
    run(*args)
    parts.append(p)
    report.append((f"{i:02d} {title}", total_d, f"멈춤 {pre:.1f}s, 겹침 {overlap:.1f}s, 앱 소리 시작 {first:.1f}s, 장면 {cd:.1f}s"))

# 마무리: 네 층위 표(문장 1~2) → 제목(문장 3)
f, d, t = narr[13]
frames = [(tier_card(OUTRO[0]), 0.0), (tier_card(OUTRO[1]), LEAD + t[1]), (title_card(OUTRO[2]), LEAD + t[2])]
p = WORK / "part13.mp4"
report.append(("마무리", stills_video(frames, (f, d), p, 1.5)))
parts.append(p)

# ── 3. 잇기 ───────────────────────────────────────────────────────────
lst = WORK / "list.txt"
lst.write_text("".join(f"file '{p}'\n" for p in parts), encoding="utf-8")
run("ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", OUT)
for r in report:
    print(f"{r[1]:6.1f}s  {r[0]}  {r[2] if len(r) > 2 else ''}")
print(f"전체 {duration(OUT):.1f}s → {OUT}")
