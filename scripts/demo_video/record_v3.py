"""v3 시연 녹화: 에뮬레이터 2대 화면 + 서버 상태 타임라인 + 시나리오 시각을 같은 시계로 기록한다."""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

OUT = sys.argv[1]
HC = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 저장소 루트
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
DURATION = 58

markers = {}
stop = threading.Event()


def mark(name):
    markers[name] = time.time()
    print(f"{markers[name] - t0:6.2f} {name}", flush=True)


def poll():
    with open(f"{OUT}/timeline.jsonl", "w") as f:
        while not stop.is_set():
            try:
                s = json.loads(urllib.request.urlopen("http://127.0.0.1:8080/status", timeout=1).read())
                f.write(json.dumps({"t": time.time(), "s": s}, ensure_ascii=False) + "\n")
            except Exception:
                pass
            time.sleep(0.15)


def vc(port, *args):
    subprocess.run([sys.executable, "-I", f"{HC}/scripts/vc.py", "--port", str(port), *args], check=True,
                   stdout=subprocess.DEVNULL)


recs = [subprocess.Popen([ADB, "-s", dev, "shell", "screenrecord", "--time-limit", str(DURATION), f"/sdcard/v3_{n}.mp4"])
        for dev, n in (("emulator-5554", "b"), ("emulator-5556", "c"))]
t0 = time.time()
markers["rec_start"] = t0
threading.Thread(target=poll, daemon=True).start()

time.sleep(6)
mark("brake_script")
p = subprocess.Popen([f"{HC}/scripts/hard_brake.sh"], stdout=subprocess.PIPE, text=True)
for line in p.stdout:
    if "급제동" in line:
        mark("brake_start")
    if "정차" in line:
        mark("stopping")
p.wait()

time.sleep(max(0, 28 - (time.time() - t0)))
mark("rain_b")
vc(8088, "wiper", "High")
time.sleep(6)
mark("rain_c")
vc(8089, "wiper", "High")

time.sleep(max(0, DURATION - 1 - (time.time() - t0)))
stop.set()
for r in recs:
    r.wait()
json.dump(markers, open(f"{OUT}/markers.json", "w"))
for n in "bc":
    dev = "emulator-5554" if n == "b" else "emulator-5556"
    subprocess.run([ADB, "-s", dev, "pull", f"/sdcard/v3_{n}.mp4", f"{OUT}/{n}.mp4"], check=True, stdout=subprocess.DEVNULL)
for port in (8088, 8089):
    vc(port, "wiper", "Off")
    vc(port, "park")
print("done", flush=True)
