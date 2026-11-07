"""이모트 효과음 합성: 외부 음원 없이 직접 만든다 (저작권 문제 없음).

  thanks : 낮고 짧은 "응" (모음 '으' → 비음 'ㅇ' 포먼트 합성)
  sorry  : 내려가는 "음~" 허밍
  hello  : 올라가는 두 음 차임

이모트 팩 (같은 세 표현을 팩마다 다른 소리로):
  retro     : 사각파 8비트 — 올라가는 아르페지오 / 미끄러져 내려가는 음 / 두 번 깜빡이는 블립
  orchestra : 현악 화음이 부풀어 오르는 장화음 / 첼로 두 음 단조 / 첼레스타 아르페지오
  electric  : (현대차 오리지널 · 차종 사운드) 부드러운 FM 전자음이 올라가는 화음 / 내려가는 글라이드 / 두 번 핑
  launch    : (특별 지급 · 출고 기념) 반짝이는 벨 아르페지오
  best      : (특별 지급 · 베스트 드라이버) 금빛 차임 화음
  emergency : (특별 지급 · 긴급차량 전용) 길을 열어 준 차에게 보내는 따뜻한 감사 — 사이렌처럼 두 음이 번갈아 울리지 않게
  gugak     : (창작마당 예시) 가야금 같은 뜯는 소리, 평조 5음 + 농현(음 끝을 흔듦)
  marimba   : (창작마당 예시) 나무 건반 마림바
모든 소리는 1.5초 이하, 사이렌·경적·차량 경고음과 닮지 않게 만든다 (창작마당 심사 기준과 같다).

  python3 scripts/make_emote_sounds.py   # app/src/main/res/raw/emote_*.wav 생성
"""
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import lfilter

SR = 44100
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"


def glottal(f0_start, f0_end, dur):
    """성대 진동을 흉내 낸 펄스열: 기본 주파수가 f0_start에서 f0_end로 바뀐다."""
    n = int(SR * dur)
    f0 = np.linspace(f0_start, f0_end, n)
    phase = np.cumsum(f0 / SR) % 1.0
    pulse = np.where(phase < 0.6, np.sin(np.pi * phase / 0.6) ** 2, 0.0)  # 열림 60%의 부드러운 펄스
    return np.diff(pulse, prepend=0.0)  # 미분해 성문 흐름 대신 방사음 형태로


def resonator(x, freq, bw):
    """2차 공명 필터 (포먼트 하나)."""
    r = np.exp(-np.pi * bw / SR)
    c = 2 * r * np.cos(2 * np.pi * freq / SR)
    return lfilter([1 - r], [1, -c, r * r], x)


def formants(x, spec):
    return sum(gain * resonator(x, f, bw) for f, bw, gain in spec)


def envelope(n, attack, release):
    env = np.ones(n)
    a, r = int(SR * attack), int(SR * release)
    env[:a] = np.linspace(0, 1, a)
    env[-r:] = np.linspace(1, 0, r) ** 2
    return env


def soften(x, cutoff=2800):
    """1차 저역 통과: 고역 배음을 줄여 거친 버즈음을 부드럽게 한다."""
    a = np.exp(-2 * np.pi * cutoff / SR)
    return lfilter([1 - a], [1, -a], x)


def normalize(x, peak=0.85):
    return (x / np.max(np.abs(x)) * peak * 32767).astype(np.int16)


def eung():
    """'응': 짧은 모음 '으' 뒤에 비음 'ㅇ'이 이어지는, 낮고 단정한 대답."""
    dur = 0.42
    src = glottal(105, 88, dur)
    vowel = formants(src, [(320, 80, 1.0), (1450, 120, 0.45), (2600, 200, 0.15)])   # '으'
    nasal = formants(src, [(260, 60, 1.0), (1000, 300, 0.08), (2200, 300, 0.05)])   # 'ㅇ' (고역이 막힘)
    t = np.linspace(0, 1, len(src))
    mix = np.clip((t - 0.25) / 0.2, 0, 1)  # 앞 25%는 모음, 이후 비음으로 넘어간다
    y = (1 - mix) * vowel + mix * nasal
    return normalize(soften(y) * envelope(len(y), 0.015, 0.12))


def hum_down():
    """'음~': 내려가는 허밍 (미안해요)."""
    dur = 0.55
    src = glottal(150, 105, dur)
    y = formants(src, [(260, 60, 1.0), (1000, 300, 0.08)])
    return normalize(soften(y, 2000) * envelope(len(y), 0.03, 0.2))


def chime_up():
    """올라가는 두 음 (안녕)."""
    def tone(freq, dur):
        t = np.arange(int(SR * dur)) / SR
        x = np.sin(2 * np.pi * freq * t) + 0.3 * np.sin(2 * np.pi * 2 * freq * t)
        return x * np.exp(-t * 6) * envelope(len(t), 0.006, 0.03)  # 음마다 페이드해 이음매 클릭 제거
    y = np.concatenate([tone(660, 0.16), tone(880, 0.3)])
    return normalize(y * envelope(len(y), 0.005, 0.05), peak=0.6)


def note(freq):
    return 440 * 2 ** ((freq - 69) / 12)  # MIDI 번호 → Hz


def square(freq, dur, duty=0.5, slide_to=None):
    n = int(SR * dur)
    f = np.linspace(freq, slide_to or freq, n)
    phase = np.cumsum(f / SR) % 1.0
    return np.where(phase < duty, 1.0, -1.0) * envelope(n, 0.003, min(0.03, dur / 3))


def retro_thanks():
    """레트로 감사: 짧게 올라가는 4음 아르페지오 + 마지막 음 길게."""
    y = np.concatenate([square(note(m), 0.07) for m in (72, 76, 79)] + [square(note(84), 0.28, duty=0.25)])
    return normalize(soften(y, 6000) * envelope(len(y), 0.002, 0.12), peak=0.5)


def retro_sorry():
    """레트로 미안: 미끄러져 내려가는 음 두 번."""
    y = np.concatenate([square(note(76), 0.18, slide_to=note(72)), np.zeros(int(SR * 0.04)),
                        square(note(72), 0.32, slide_to=note(64))])
    return normalize(soften(y, 5000) * envelope(len(y), 0.002, 0.15), peak=0.5)


def retro_hello():
    """레트로 안녕: 블립 두 번과 떨림."""
    trill = np.concatenate([square(note(m), 0.035, duty=0.25) for m in (79, 83) * 4])
    y = np.concatenate([square(note(79), 0.06), np.zeros(int(SR * 0.05)), square(note(79), 0.06),
                        np.zeros(int(SR * 0.05)), trill])
    return normalize(soften(y, 6000) * envelope(len(y), 0.002, 0.08), peak=0.5)


def strings(midis, dur, attack=0.25):
    """현악 느낌: 배음을 가진 톱니파를 살짝 떨게(비브라토) 하고 고역을 깎아 겹친다."""
    n = int(SR * dur)
    t = np.arange(n) / SR
    y = np.zeros(n)
    for m in midis:
        f = note(m) * (1 + 0.004 * np.sin(2 * np.pi * 5.2 * t))
        phase = np.cumsum(f / SR)
        y += sum(np.sin(2 * np.pi * k * phase) / k for k in range(1, 8))
    return soften(soften(y, 2400), 2400) * envelope(n, attack, dur * 0.35)


def celesta(midi, dur):
    t = np.arange(int(SR * dur)) / SR
    f = note(midi)
    x = np.sin(2 * np.pi * f * t) + 0.4 * np.sin(2 * np.pi * 4 * f * t) * np.exp(-t * 18)
    return x * np.exp(-t * 5) * envelope(len(t), 0.003, 0.03)


def orchestra_thanks():
    """오케스트라 감사: 장화음이 부풀었다 가라앉는다."""
    return normalize(strings((60, 64, 67, 72), 1.0, attack=0.3), peak=0.7)


def orchestra_sorry():
    """오케스트라 미안: 낮은 현 두 음, 내려가는 단조."""
    y = np.concatenate([strings((51, 55), 0.45, 0.08), strings((50, 53), 0.7, 0.05)])
    return normalize(y, peak=0.7)


def orchestra_hello():
    """오케스트라 안녕: 첼레스타 아르페지오."""
    y = np.concatenate([celesta(m, 0.14) for m in (79, 83, 86)] + [celesta(91, 0.6)])
    return normalize(y, peak=0.6)


# ---------- 상점 팩 (2026-11-08) ----------

def pluck(midi, dur, bend=0.0):
    """가야금 같은 뜯는 소리 (Karplus-Strong). bend는 끝에서 음을 흔드는 정도(반음 단위)."""
    n = int(SR * dur)
    f = note(midi)
    period = int(SR / f)
    rng = np.random.default_rng(midi)
    buf = rng.uniform(-1, 1, period)
    y = np.zeros(n)
    for i in range(n):
        y[i] = buf[i % period]
        buf[i % period] = 0.5 * (buf[i % period] + buf[(i + 1) % period]) * 0.996
    if bend:
        t = np.arange(n) / SR
        shift = bend * np.clip((t - dur * 0.35) / (dur * 0.6), 0, 1) * np.sin(2 * np.pi * 5 * t)
        idx = np.clip(np.arange(n) * (2 ** (shift / 12)), 0, n - 1)
        y = np.interp(idx, np.arange(n), y)
    return soften(y, 4000) * envelope(n, 0.002, 0.06)


def marimba(midi, dur):
    t = np.arange(int(SR * dur)) / SR
    f = note(midi)
    x = np.sin(2 * np.pi * f * t) * np.exp(-t * 7) + 0.25 * np.sin(2 * np.pi * 4 * f * t) * np.exp(-t * 30)
    return x * envelope(len(t), 0.002, 0.03)


def fm(midi, dur, glide_to=None, index=1.5):
    n = int(SR * dur)
    t = np.arange(n) / SR
    f = np.linspace(note(midi), note(glide_to) if glide_to else note(midi), n)
    phase = np.cumsum(f / SR)
    mod = index * np.exp(-t * 4) * np.sin(2 * np.pi * 2 * phase)
    return np.sin(2 * np.pi * phase + mod) * envelope(n, 0.02, dur * 0.4)


def bell(midi, dur):
    t = np.arange(int(SR * dur)) / SR
    f = note(midi)
    x = sum(a * np.sin(2 * np.pi * f * r * t) * np.exp(-t * d) for r, a, d in ((1, 1, 4), (2.76, 0.4, 7), (5.4, 0.2, 12)))
    return x * envelope(len(t), 0.002, 0.04)


def seq(parts):
    return np.concatenate(parts)


SHOP_PACKS = {
    "electric_thanks": lambda: normalize(seq([fm(m, 0.12) for m in (64, 68, 71)] + [fm(76, 0.5)]), 0.55),
    "electric_sorry": lambda: normalize(fm(71, 0.6, glide_to=64, index=0.8), 0.55),
    "electric_hello": lambda: normalize(seq([fm(79, 0.14, index=2), np.zeros(int(SR * 0.05)), fm(83, 0.3, index=2)]), 0.5),
    "launch_thanks": lambda: normalize(seq([bell(m, 0.1) for m in (72, 76, 79, 84)] + [bell(88, 0.6)]), 0.55),
    "launch_sorry": lambda: normalize(seq([bell(76, 0.25), bell(72, 0.5)]), 0.5),
    "launch_hello": lambda: normalize(seq([bell(84, 0.12), bell(88, 0.45)]), 0.5),
    "best_thanks": lambda: normalize(strings((67, 71, 74, 79), 0.6, 0.05) + 0.6 * np.pad(seq([bell(m, 0.12) for m in (86, 91)]), (0, int(SR * 0.6) - int(SR * 0.24))), 0.65),
    "best_sorry": lambda: normalize(strings((62, 67), 0.7, 0.08), 0.6),
    "best_hello": lambda: normalize(seq([bell(79, 0.12), bell(86, 0.5)]), 0.55),
    "emergency_thanks": lambda: normalize(strings((60, 64, 67), 0.9, 0.12) + 0.5 * np.pad(seq([celesta(m, 0.2) for m in (72, 76, 79)]), (0, int(SR * 0.9) - int(SR * 0.6))), 0.65),
    "gugak_thanks": lambda: normalize(seq([pluck(m, 0.16) for m in (67, 69, 72)] + [pluck(74, 0.7, bend=0.6)]), 0.7),
    "gugak_sorry": lambda: normalize(seq([pluck(69, 0.3, bend=0.4), pluck(64, 0.6, bend=0.8)]), 0.7),
    "gugak_hello": lambda: normalize(seq([pluck(72, 0.14), pluck(74, 0.5)]), 0.7),
    "marimba_thanks": lambda: normalize(seq([marimba(m, 0.12) for m in (72, 76, 79)] + [marimba(84, 0.45)]), 0.6),
    "marimba_sorry": lambda: normalize(seq([marimba(76, 0.22), marimba(72, 0.45)]), 0.6),
    "marimba_hello": lambda: normalize(seq([marimba(79, 0.1), marimba(79, 0.1), marimba(84, 0.4)]), 0.6),
}


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, data in (("retro_thanks", retro_thanks()), ("retro_sorry", retro_sorry()), ("retro_hello", retro_hello()),
                       ("orchestra_thanks", orchestra_thanks()), ("orchestra_sorry", orchestra_sorry()),
                       ("orchestra_hello", orchestra_hello())):
        wavfile.write(OUT / f"emote_{name}.wav", SR, data)
        print(OUT / f"emote_{name}.wav", f"{len(data) / SR:.2f}s")
    for name, make in SHOP_PACKS.items():
        data = make()
        assert len(data) / SR <= 1.5, name
        wavfile.write(OUT / f"emote_{name}.wav", SR, data)
        print(OUT / f"emote_{name}.wav", f"{len(data) / SR:.2f}s")
    for name, data in (("thanks", eung()), ("sorry", hum_down()), ("hello", chime_up())):
        wavfile.write(OUT / f"emote_{name}.wav", SR, data)
        print(OUT / f"emote_{name}.wav", f"{len(data) / SR:.2f}s")
