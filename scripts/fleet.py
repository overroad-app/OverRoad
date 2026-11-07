"""가상 차량 시뮬레이터: 에뮬레이터 대신 여러 대의 차량을 서버에 참여시킨다.

기준 차량(에뮬레이터)의 현재 위치·방향을 기준으로 가상 차량을 배치하고,
0.5초마다 위치를 갱신하면서 받은 경고를 출력한다.

  python3 scripts/fleet.py run --anchor B A:2:+250 D:1:-120 [--jam +800:9:10:merge]
      ID:차로:기준 차량 대비 전방 거리(m)[:속도 km/h[:방향지시등 L|R]][:키=값 ...]
        role=ambulance|fire|police   긴급차량
        soc=35                      배터리 잔량 %
        dest=PANGYO[,eta=300]        목적지 충전소 (eta 생략 시 거리·속도로 계산)
        at=PANGYO[,charging=1]       충전소에 있는 차량 (charging=1이면 충전 중)
        turn=L@3                     3초에 왼쪽 깜빡이 켬 (L|R)
        lane=1@6                     6초에 1차로로 이동 시작 (깜빡이를 켜고 약 3초에 걸쳐 옆으로 옮긴 뒤 끔)
        speed=30@5                   5초부터 30km/h로 감속·가속 (감속은 최대 3m/s², 여러 번 쓸 수 있다: speed=30@5,speed=60@9)
      모든 가상 차량은 앞차(에뮬레이터 B·C 포함)와 간격을 지키며 따라가고 앞차를 뚫고 지나가지 않는다.
      긴급차량이 같은 차로 뒤에서 다가오면 옆 차로가 비어 있을 때 비켜 주고, 길이 막히면 긴급차량이 따라가며 기다린다.
        auto=1                       자동 감사 인사 켬
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
                            print(f"[{vid}] {w['symbol']} 이모트 수신: {who}가 {w['emote']}"
                                  f"{' (자동 감사 인사)' if w.get('auto') else ''}", flush=True)
                        elif w.get("kind") in ("lane", "merge_done", "charging", "emergency"):
                            print(f"[{vid}] {w['kind']} {w}", flush=True)
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
        rest_all = list(rest)
        opts = dict(kv.split("=", 1) for part in rest if "=" in part for kv in part.split(","))
        rest = [r for r in rest if "=" not in r]
        speed = float(rest[0]) if rest and rest[0] else 0.0
        turn = {"L": "LEFT", "R": "RIGHT"}.get(rest[1]) if len(rest) > 1 else None
        fleet.append({"id": vid, "lane": int(lane), "fwd": float(fwd), "speed": speed, "turn": turn,
                      "role": opts.get("role"), "soc": int(opts["soc"]) if "soc" in opts else None,
                      "dest": chargers.get(opts.get("dest")), "eta": float(opts["eta"]) if "eta" in opts else None,
                      "at": chargers.get(opts.get("at")), "charging": opts.get("charging") == "1",
                      "turn_at": (opts["turn"].split("@") if "turn" in opts else None),
                      "lane_at": (opts["lane"].split("@") if "lane" in opts else None),
                      "speed_at": sorted(([float(x) for x in kv[6:].split("@")[::-1]] for part in rest_all for kv in part.split(",")
                                          if kv.startswith("speed=")), key=lambda a: a[0]),  # [(시각, 속도), ...]
                      "auto": opts.get("auto") == "1"})
    if args.jam:
        start, count, speed, *kind = args.jam.split(":")
        for i in range(int(count)):
            lane = i % 3 + 1
            turn = "LEFT" if kind == ["merge"] and lane == 3 else None
            fleet.append({"id": f"J{i + 1}", "lane": lane, "fwd": float(start) + i * 30, "speed": float(speed), "turn": turn,
                          "role": None, "soc": None, "dest": None, "eta": None, "at": None, "charging": False,
                          "turn_at": None, "lane_at": None, "speed_at": [], "auto": False})
    for v in fleet:
        threading.Thread(target=listen, args=(v["id"],), daemon=True).start()
        extra = " ".join(x for x in (
            v["turn"] and f"깜빡이 {v['turn']}", v["role"] and f"[{v['role']}]",
            v["soc"] is not None and f"SoC {v['soc']}%", v["dest"] and f"→ {v['dest']['name']}",
            v["at"] and f"@ {v['at']['name']}{' 충전 중' if v['charging'] else ''}") if x)
        print(f"가상 차량 {v['id']}: {v['lane']}차로, {args.anchor} 대비 {v['fwd']:+.0f}m, {v['speed']:.0f}km/h {extra}", flush=True)

    base_lane = anchor.get("lane") or 1
    lat0, lon0, h0 = anchor["lat"], anchor["lon"], anchor["heading"]
    ids = {v["id"] for v in fleet}
    for v in fleet:  # 움직이는 상태: 위치(기준점에서 앞으로 m), 현재·목표 속도, 옆 위치(m)
        v["pos"], v["cur"], v["target"] = v["fwd"], v["speed"], v["speed"]
        v["side"] = (v["lane"] - base_lane) * LANE_WIDTH_M
        v["to_lane"] = None
        v["yielded"] = False

    def on_road(lat, lon):
        """기준점·진행 방향 기준 (앞 m, 오른쪽 m)."""
        dy = (lat - lat0) * 111320
        dx = (lon - lon0) * 111320 * math.cos(math.radians(lat0))
        h = math.radians(h0)
        return dx * math.sin(h) + dy * math.cos(h), dx * math.cos(h) - dy * math.sin(h)

    def lane_of(side):
        return base_lane + round(side / LANE_WIDTH_M)

    def lane_free(lane, pos, skip):
        """그 차로의 pos 앞뒤 12m가 비어 있는지 (차로를 바꿔도 되는지)."""
        return all(abs(o["pos"] - pos) > 12 for o in others if o is not skip and o["lane_now"] == lane)

    start = last = time.time()
    while True:
        now = time.time()
        t, dt = now - start, min(0.5, now - last)
        last = now
        # 장애물: 다른 가상 차량 + 서버에 있는 실제 차량(에뮬레이터 B·C)
        others = []
        for v in fleet:
            if not v["at"]:
                v["lane_now"] = lane_of(v["side"])
                others.append({"id": v["id"], "pos": v["pos"], "cur": v["cur"], "lane_now": v["lane_now"],
                               "lane_to": v["to_lane"], "role": v["role"], "v": v})
        try:
            for oid, o in call("/status")["vehicles"].items():
                if oid in ids or now - o.get("ts", 0) > 3:
                    continue
                pos, side = on_road(o["lat"], o["lon"])
                if abs(side) < 15:
                    others.append({"id": oid, "pos": pos, "cur": o.get("speed_kph") or 0,
                                   "lane_now": o.get("lane") or lane_of(side), "lane_to": None, "role": o.get("role"), "v": None})
        except Exception:
            pass
        for v in fleet:
            if v["at"]:
                continue
            # 예약된 깜빡이·차로 변경·속도 변경
            if v["turn_at"] and t >= float(v["turn_at"][1]) and not v.get("turned"):
                v["turn"], v["turned"] = {"L": "LEFT", "R": "RIGHT"}[v["turn_at"][0]], True
                print(f"[{v['id']}] 깜빡이 {v['turn']}", flush=True)
            if v["lane_at"] and t >= float(v["lane_at"][1]) and not v.get("moved"):
                v["to_lane"], v["moved"] = int(v["lane_at"][0]), True
                v["turn"] = "LEFT" if v["to_lane"] < v["lane_now"] else "RIGHT"
                print(f"[{v['id']}] {v['to_lane']}차로로 이동 시작", flush=True)
            while v["speed_at"] and t >= v["speed_at"][0][0]:
                v["target"] = v["speed_at"].pop(0)[1]
                print(f"[{v['id']}] 목표 속도 {v['target']:.0f}km/h", flush=True)
            # 긴급차량 양보: 같은 차로 뒤 150m 안에서 더 빠르게 다가오면 빈 옆 차로로 비킨다
            if not v["role"] and v["to_lane"] is None and not v["yielded"]:
                for o in others:
                    if o["role"] in ("ambulance", "fire", "police") and o["lane_now"] == v["lane_now"] \
                            and 0 < v["pos"] - o["pos"] < 150 and o["cur"] > v["cur"] + 5:
                        for lane in (v["lane_now"] + 1, v["lane_now"] - 1):
                            if 1 <= lane <= 3 and lane_free(lane, v["pos"], None):
                                v["to_lane"], v["yielded"] = lane, True
                                v["turn"] = "LEFT" if lane < v["lane_now"] else "RIGHT"
                                print(f"[{v['id']}] 긴급차량에 양보 → {lane}차로", flush=True)
                                break
                        break
            # 차로 변경: 약 3초에 걸쳐 옆으로
            if v["to_lane"] is not None:
                goal = (v["to_lane"] - base_lane) * LANE_WIDTH_M
                step = 1.2 * dt
                v["side"] = goal if abs(goal - v["side"]) <= step else v["side"] + math.copysign(step, goal - v["side"])
                if v["side"] == goal:
                    v["lane"], v["to_lane"], v["turn"] = v["to_lane"], None, None
                    print(f"[{v['id']}] {v['lane']}차로 진입", flush=True)
            # 앞차 따라가기: 내 차로(바꾸는 중이면 두 차로 모두)의 가장 가까운 앞차와 간격을 지킨다
            lanes = {v["lane_now"]} | ({v["to_lane"]} if v["to_lane"] else set())
            ahead = [o for o in others if o["v"] is not v and (o["lane_now"] in lanes or o["lane_to"] in lanes)
                     and o["pos"] > v["pos"]]
            want = v["target"]
            if ahead:
                lead = min(ahead, key=lambda o: o["pos"])
                gap = lead["pos"] - v["pos"] - 5.0
                if v["role"] and gap < 60 and v["to_lane"] is None:  # 긴급차량: 막히면 빈 옆 차로로
                    for lane in (v["lane_now"] - 1, v["lane_now"] + 1):
                        if 1 <= lane <= 3 and lane_free(lane, v["pos"], v):
                            v["to_lane"] = lane
                            break
                safe = 6.0 + v["cur"] / 3.6 * 1.5     # 정지 간격 6m + 1.5초
                if gap < safe:
                    want = min(want, max(0.0, lead["cur"] - (safe - gap) * 1.5))
            dv = want - v["cur"]
            v["cur"] = max(0.0, v["cur"] + max(-6.0 * 3.6 * dt if ahead and want < v["cur"] - 15 else -3.0 * 3.6 * dt,
                                               min(2.0 * 3.6 * dt, dv)))
            v["pos"] += v["cur"] / 3.6 * dt
        for v in fleet:
            if v["at"]:
                lat, lon = v["at"]["lat"], v["at"]["lon"]
            else:
                lat, lon = offset(lat0, lon0, h0, v["pos"], v["side"])
            dest = None
            if v["dest"]:
                d = v["dest"]
                eta = v["eta"] if v["eta"] is not None else (
                    math.dist((lat * 111320, lon * 88000), (d["lat"] * 111320, d["lon"] * 88000)) / max(v["speed"], 30) * 3.6)
                dest = {"lat": d["lat"], "lon": d["lon"], "name": d["name"], "eta_s": max(0, round(eta - t))}
            call("/position", {"vehicle": v["id"], "lat": lat, "lon": lon, "heading": h0,
                               "speed_kph": 0 if v["at"] else round(v["cur"], 1), "lane": v["lane"], "turn": v["turn"],
                               "role": v["role"], "soc": v["soc"], "charging": v["charging"], "dest": dest,
                               "auto_thanks": v["auto"]})
        time.sleep(0.25)


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
