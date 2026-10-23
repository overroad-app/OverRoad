"""가상 차량 시뮬레이터: 에뮬레이터 대신 여러 대의 차량을 서버에 참여시킨다.

기준 차량(에뮬레이터)의 현재 위치·방향을 기준으로 가상 차량을 배치하고,
0.5초마다 위치를 갱신하면서 받은 경고를 출력한다.

  python3 scripts/fleet.py run --anchor B A:2:+250 D:1:-120 [--jam +800:9:10:merge]
      ID:차로:기준 차량 대비 전방 거리(m)[:속도 km/h[:방향지시등 L|R]][:키=값 ...]
        role=ambulance|fire|police   긴급차량
        soc=35                      배터리 잔량 %
        dest=PANGYO[,eta=300]        목적지 충전소 (eta 생략 시 거리·속도로 계산)
        at=PANGYO[,charging=1]       충전소에 있는 차량 (charging=1이면 충전 중)
      --jam 시작 거리:대수:속도[:merge] → J1..Jn을 1~3차로에 30m 간격으로 배치
                                         (merge면 3차로가 끝나 3차로 차량이 왼쪽 깜빡이)
  python3 scripts/fleet.py weather A --rain 2 --fog
      가상 차량 A가 현재 위치에서 기상 관측을 보고 (run 중인 차량이어야 한다)
  python3 scripts/fleet.py report J9 crash
      가상 차량이 현재 위치에서 이벤트(crash, stopped_vehicle, hard_brake)를 보고
  python3 scripts/fleet.py emote D thanks ahead
      가상 차량 D가 바로 앞차에게 이모트(thanks, sorry, hello)를 보낸다
"""

import argparse
import json
import math
import threading
import time
import urllib.request

SERVER = "http://127.0.0.1:8080"
LANE_WIDTH_M = 3.5


def call(path, body=None):
    req = urllib.request.Request(SERVER + path, method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())


def offset(lat, lon, heading, forward_m, right_m):
    h = math.radians(heading)
    north = forward_m * math.cos(h) - right_m * math.sin(h)
    east = forward_m * math.sin(h) + right_m * math.cos(h)
    return lat + north / 111320, lon + east / (111320 * math.cos(math.radians(lat)))


def listen(vid):
    """경고 구독: 받은 경고를 출력한다."""
    while True:
        try:
            with urllib.request.urlopen(f"{SERVER}/stream?vehicle={vid}", timeout=30) as r:
                for raw in r:
                    line = raw.decode().strip()
                    if line.startswith("data: "):
                        w = json.loads(line[6:])
                        if w.get("kind") == "emote":
                            who = "앞차" if w["from"] == "ahead" else "뒤차"
                            print(f"[{vid}] {w['symbol']} 이모트 수신: {who}가 {w['emote']}", flush=True)
                        else:
                            print(f"[{vid}] ⚠ 경고 수신: {w['type']} {w['distance_m']}m 앞 ({w['lane']})", flush=True)
        except Exception:
            time.sleep(1)


def run(args):
    anchor = call("/status")["vehicles"].get(args.anchor)
    if not anchor:
        raise SystemExit(f"기준 차량 {args.anchor}이 서버에 없습니다")
    chargers = {c["id"]: c for c in call("/status").get("chargers", [])}
    fleet = []
    for spec in args.vehicles:
        vid, lane, fwd, *rest = spec.split(":")
        opts = dict(kv.split("=", 1) for part in rest if "=" in part for kv in part.split(","))
        rest = [r for r in rest if "=" not in r]
        speed = float(rest[0]) if rest and rest[0] else 0.0
        turn = {"L": "LEFT", "R": "RIGHT"}.get(rest[1]) if len(rest) > 1 else None
        fleet.append({"id": vid, "lane": int(lane), "fwd": float(fwd), "speed": speed, "turn": turn,
                      "role": opts.get("role"), "soc": int(opts["soc"]) if "soc" in opts else None,
                      "dest": chargers.get(opts.get("dest")), "eta": float(opts["eta"]) if "eta" in opts else None,
                      "at": chargers.get(opts.get("at")), "charging": opts.get("charging") == "1"})
    if args.jam:
        start, count, speed, *kind = args.jam.split(":")
        for i in range(int(count)):
            lane = i % 3 + 1
            turn = "LEFT" if kind == ["merge"] and lane == 3 else None
            fleet.append({"id": f"J{i + 1}", "lane": lane, "fwd": float(start) + i * 30, "speed": float(speed), "turn": turn,
                          "role": None, "soc": None, "dest": None, "eta": None, "at": None, "charging": False})
    for v in fleet:
        threading.Thread(target=listen, args=(v["id"],), daemon=True).start()
        extra = " ".join(x for x in (
            v["turn"] and f"깜빡이 {v['turn']}", v["role"] and f"[{v['role']}]",
            v["soc"] is not None and f"SoC {v['soc']}%", v["dest"] and f"→ {v['dest']['name']}",
            v["at"] and f"@ {v['at']['name']}{' 충전 중' if v['charging'] else ''}") if x)
        print(f"가상 차량 {v['id']}: {v['lane']}차로, {args.anchor} 대비 {v['fwd']:+.0f}m, {v['speed']:.0f}km/h {extra}", flush=True)

    base_lane = anchor.get("lane") or 1
    start = time.time()
    while True:
        t = time.time() - start
        for v in fleet:
            if v["at"]:  # 충전소에 서 있는 차량
                lat, lon = v["at"]["lat"], v["at"]["lon"]
            else:
                right = (v["lane"] - base_lane) * LANE_WIDTH_M
                lat, lon = offset(anchor["lat"], anchor["lon"], anchor["heading"], v["fwd"] + v["speed"] / 3.6 * t, right)
            dest = None
            if v["dest"]:
                d = v["dest"]
                eta = v["eta"] if v["eta"] is not None else (
                    math.dist((lat * 111320, lon * 88000), (d["lat"] * 111320, d["lon"] * 88000)) / max(v["speed"], 30) * 3.6)
                dest = {"lat": d["lat"], "lon": d["lon"], "name": d["name"], "eta_s": max(0, round(eta - t))}
            call("/position", {"vehicle": v["id"], "lat": lat, "lon": lon, "heading": anchor["heading"],
                               "speed_kph": 0 if v["at"] else v["speed"], "lane": v["lane"], "turn": v["turn"],
                               "role": v["role"], "soc": v["soc"], "charging": v["charging"], "dest": dest})
        time.sleep(0.5)


def report(args):
    v = call("/status")["vehicles"].get(args.vehicle)
    if not v:
        raise SystemExit(f"차량 {args.vehicle}이 서버에 없습니다")
    print(call("/events", {"vehicle": args.vehicle, "type": args.type, "lat": v["lat"], "lon": v["lon"],
                           "heading": v["heading"], "lane": v["lane"], "speed_kph": v.get("speed_kph")}))


def weather(args):
    v = call("/status")["vehicles"].get(args.vehicle)
    if not v:
        raise SystemExit(f"차량 {args.vehicle}이 서버에 없습니다")
    obs = {"vehicle": args.vehicle, "type": "weather_obs", "lat": v["lat"], "lon": v["lon"],
           "heading": v["heading"], "rain": args.rain, "fog": args.fog, "slippery": args.slippery}
    print(call("/events", obs))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    sp = p.add_subparsers(dest="cmd", required=True)
    r = sp.add_parser("run")
    r.add_argument("--anchor", required=True)
    r.add_argument("vehicles", nargs="*")
    r.add_argument("--jam")
    w = sp.add_parser("weather")
    w.add_argument("vehicle")
    w.add_argument("--rain", type=int, default=0)
    w.add_argument("--fog", action="store_true")
    w.add_argument("--slippery", action="store_true")
    ev = sp.add_parser("report")
    ev.add_argument("vehicle")
    ev.add_argument("type", choices=["crash", "stopped_vehicle", "hard_brake"])
    e = sp.add_parser("emote")
    e.add_argument("vehicle")
    e.add_argument("emote", choices=["thanks", "sorry", "hello"])
    e.add_argument("target", choices=["ahead", "behind"])
    a = p.parse_args()
    if a.cmd == "run":
        run(a)
    elif a.cmd == "weather":
        weather(a)
    elif a.cmd == "report":
        report(a)
    else:
        print(call("/emote", {"vehicle": a.vehicle, "emote": a.emote, "target": a.target}))
