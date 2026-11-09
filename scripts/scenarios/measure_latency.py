"""급제동 경고 지연시간 실측 (기능 D).

각 회차마다 B·C 앱을 기준 위치로 다시 띄우고 B를 급제동시킨 뒤, 세 시각을 비교한다.
  t0  브레이크 명령 시각 (호스트)                   scripts/hard_brake.sh 의 "T0" 줄
  t1  B 앱이 급제동을 감지한 시각 (B 기기)          logcat "마지막 보고 = 급제동"
  t2  C 앱이 경고를 화면에 띄운 시각 (C 기기)       logcat "경고 수신"
기기 시계는 adb로 호스트 시계와의 차이를 재서 보정한다 (왕복 시간이 가장 짧은 표본 사용, 오차 수십 ms).

  python3 scripts/scenarios/measure_latency.py [회차 수, 기본 10] [결과 md 경로]
"""
import os
import re
import subprocess
import sys
import time
from pathlib import Path

HC = Path(__file__).resolve().parents[2]
ADB = str(Path.home() / "Library/Android/sdk/platform-tools/adb")
B, C = "emulator-5554", "emulator-5556"
COOLDOWN_S = 22  # 서버의 같은 지점 급제동 재전송 제한(20초)보다 길게


def sh(*cmd):
    return subprocess.run(cmd, capture_output=True, text=True).stdout


def clock_offset(dev):
    """기기 시계 - 호스트 시계 (초)."""
    best = None
    for _ in range(7):
        h0 = time.time()
        d = float(sh(ADB, "-s", dev, "shell", "date +%s.%N").strip())
        h1 = time.time()
        if best is None or h1 - h0 < best[0]:
            best = (h1 - h0, d - (h0 + h1) / 2)
    return best[1], best[0]


def log_time(dev, pattern):
    for line in sh(ADB, "-s", dev, "logcat", "-d", "-v", "epoch").splitlines():
        if re.search(pattern, line):
            return float(line.split()[0])
    return None


def run_once():
    os.environ["C_GAP"] = "90"  # 급제동은 정차 차량에 최소 100m 안에서만 간다
    sh("bash", str(HC / "scripts/scenarios/launch_bc.sh"))
    time.sleep(2)
    offs = {dev: clock_offset(dev) for dev in (B, C)}
    out = sh("bash", str(HC / "scripts/hard_brake.sh"))
    t0 = float(re.search(r"T0 ([\d.]+)", out).group(1))
    time.sleep(3)
    t1 = log_time(B, r"마지막 보고 = 급제동")
    t2 = log_time(C, r"경고 수신: .*급제동")
    if t1 is None or t2 is None:
        return None
    t1 -= offs[B][0]
    t2 -= offs[C][0]
    rtt = max(offs[B][1], offs[C][1])
    return {"detect": (t1 - t0) * 1000, "deliver": (t2 - t1) * 1000, "total": (t2 - t0) * 1000, "rtt": rtt * 1000}


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    out_md = Path(sys.argv[2]) if len(sys.argv) > 2 else HC / "docs/latency.md"
    rows = []
    for i in range(n):
        start = time.time()
        r = run_once()
        print(f"{i + 1}/{n}: {r and {k: round(v) for k, v in r.items()}}", flush=True)
        if r:
            rows.append(r)
        time.sleep(max(0, COOLDOWN_S - (time.time() - start)))
    if not rows:
        raise SystemExit("측정 실패")

    def stat(k):
        v = sorted(r[k] for r in rows)
        return f"{sum(v) / len(v):.0f} | {v[len(v) // 2]:.0f} | {v[0]:.0f} | {v[-1]:.0f}"

    out_md.parent.mkdir(exist_ok=True)
    out_md.write_text(f"""# 급제동 경고 지연시간 실측

측정일 {time.strftime('%Y-%m-%d')} · {len(rows)}/{n}회 성공 · `scripts/scenarios/measure_latency.py`

환경: Pleos Connect 에뮬레이터 2대(B 급제동 차량, C 300m 뒤 차량) + 같은 PC의 중계 서버.
실제 차량은 이동통신망을 거치므로 전달 구간에 수십~수백 ms가 더해질 수 있다.

| 구간 (ms) | 평균 | 중앙값 | 최소 | 최대 |
|---|---|---|---|---|
| ① 감지: 브레이크 명령 → B 앱 급제동 판정 | {stat('detect')} |
| ② 전달: B 판정 → 서버 → C 화면 경고 | {stat('deliver')} |
| 전체: 브레이크 명령 → C 화면 경고 | {stat('total')} |

- ①은 감속이 기준(감속도·브레이크 입력)을 넘을 때까지 기다리는 시간이 대부분이다.
- ②는 같은 PC 안에서 오가므로 매우 짧다. 보정 오차보다 작은 값은 "수 ms 수준"으로만 해석한다.
- 기기 시계는 adb로 호스트 시계와의 차이를 재 보정했다 (보정 오차 최대 약 {max(r['rtt'] for r in rows) / 2:.0f}ms).
- 비교: 기존 내비 교통정보는 GPS 프로브를 모아 주기적으로 갱신한다(갱신 주기는 기획서 인용 전 출처 확인). OverRoad는 돌발 상황을 1초 안에 뒤차 화면까지 전한다.
""")
    print(out_md.read_text())


if __name__ == "__main__":
    main()
