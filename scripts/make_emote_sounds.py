"""이모트 효과음 합성: 외부 음원 없이 직접 만든다 (저작권 문제 없음).

  thanks : 낮고 짧은 "응" (모음 '으' → 비음 'ㅇ' 포먼트 합성)
  sorry  : 내려가는 "음~" 허밍
  hello  : 올라가는 두 음 차임

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


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, data in (("thanks", eung()), ("sorry", hum_down()), ("hello", chime_up())):
        wavfile.write(OUT / f"emote_{name}.wav", SR, data)
        print(OUT / f"emote_{name}.wav", f"{len(data) / SR:.2f}s")
