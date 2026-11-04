"""Virtual Console 제어 도구: 시연 시나리오에 필요한 차량 상태를 바꾼다.

Virtual Console은 브라우저 세션 하트비트가 있어야 제어를 받으므로 매 요청 전에 보낸다.

  python3 scripts/vc.py [--port 8088] hazard on|off
  python3 scripts/vc.py telltale Airbag On|Blink|Off      (ABS, ESC, FrontFogLamp ...)
  python3 scripts/vc.py wiper Off|Auto|Low|High
  python3 scripts/vc.py turn left|right|off                방향지시등
  python3 scripts/vc.py drive 60                           D 기어로 해당 속도 유지
  python3 scripts/vc.py stop                               0km/h, D 기어 유지
  python3 scripts/vc.py park
  python3 scripts/vc.py soc 35                             배터리 잔량(%)
  python3 scripts/vc.py charge on|off                      충전 스위치 (충전 시작·종료)
"""

import argparse
import urllib.parse
import urllib.request


def put(base, path, **params):
    urllib.request.urlopen(urllib.request.Request(
        f"{base}/browser/session?client=vc-script", method="POST", data=b""), timeout=3).read()
    url = f"{base}{path}?{urllib.parse.urlencode(params)}"
    with urllib.request.urlopen(urllib.request.Request(url, method="PUT"), timeout=5) as r:
        r.read()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8088)
    p.add_argument("cmd")
    p.add_argument("args", nargs="*")
    a = p.parse_args()
    base = f"http://127.0.0.1:{a.port}"

    if a.cmd == "hazard":
        put(base, "/vehicle/lights", feature="hazard", value=str(a.args[0] == "on").lower())
    elif a.cmd == "telltale":
        put(base, "/vehicle/telltales/simulator", type=a.args[0], state=a.args[1], color="Red")
    elif a.cmd == "turn":
        put(base, "/vehicle/turn-signal", mode={"left": "BlinkLeft", "right": "BlinkRight", "off": "Off"}[a.args[0]])
    elif a.cmd == "wiper":
        put(base, "/vehicle/body", front_wiper=a.args[0])
    elif a.cmd == "drive":
        put(base, "/vehicle/gear", gear="Drive")
        put(base, "/vehicle/controls", speed=a.args[0], accelerator=30, brake=0)
    elif a.cmd == "stop":
        put(base, "/vehicle/controls", speed=0, accelerator=0, brake=60)
    elif a.cmd == "soc":
        put(base, "/vehicle/ev-battery", field="soc", value=a.args[0])
    elif a.cmd == "charge":
        put(base, "/vehicle/ev-battery", field="charge_switch", value=str(a.args[0] == "on").lower())
    elif a.cmd == "park":
        put(base, "/vehicle/controls", speed=0, accelerator=0, brake=0)
        put(base, "/vehicle/gear", gear="Park")
    else:
        raise SystemExit(f"알 수 없는 명령: {a.cmd}")
    print("ok")
