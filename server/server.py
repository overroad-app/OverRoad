"""HyperConnect 중계 서버 (프로토타입).

모든 참여 차량을 고유 ID로 상시 추적하는 차량 레지스트리(로컬 다이내믹 맵의 씨앗)를 유지하고,
차량이 보고한 이벤트를 이벤트 종류별 규칙에 따라 영향을 받는 차량에만 전파한다.
표준 라이브러리만 사용한다.

  python3 server/server.py              # 0.0.0.0:8080
  GET  /stream?vehicle=B                  차량 구독 (Server-Sent Events)
  POST /position                          차량 상태 갱신: 위치·방향·속도·차로 (JSON)
  POST /events                            이벤트 보고 (JSON)
  POST /emote                             앞차·뒤차에게 이모트 보내기 (JSON)
  POST /feedback                          받은 경고가 맞았는지 피드백 → 보고 차량의 신뢰 점수
  POST /forget                            데이터 공유 동의 철회 → 이 차량의 위치·관측·통계 삭제
  GET  /stats?vehicle=B                   내 기여 통계·신뢰 점수·배지
  (서버가 1초마다 정체·사고 구간, EV 충전 대기 예측, 긴급차량 접근을 계산해 해당 차량에 보낸다)
  GET  /status                            차량 레지스트리·최근 이벤트·경고
  GET  /dashboard                         실시간 도로 상황 대시보드
"""

import json
import math
import threading
import time
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

PORT = 8080
VEHICLE_TTL_S = 10        # 이 시간 동안 위치 갱신이 없으면 레지스트리에서 제외
SAME_DIRECTION_DEG = 45   # 진행 방향 차이가 이 안이면 같은 방향 차량
CLUSTER_RADIUS_M = 300    # 같은 지점으로 보는 거리
REPORT_WINDOW_S = 30      # 즉시성 이벤트 합의 기간
WEATHER_WINDOW_S = 300    # 기상 관측 합의 기간
WEATHER_ZONE_M = 1000     # 같은 기상 구간으로 보는 거리

# 이벤트 종류별 전파 규칙
#   min_reports : 확정에 필요한 서로 다른 차량 수 (프로토타입은 즉시성 이벤트 1대)
#   radius_m    : 경고 대상이 되는 후방 거리
#   lanes       : "same" = 같은 차로만, "all" = 모든 차로
#   cooldown_s  : 같은 지점 같은 이벤트를 한 차량에 다시 보내지 않는 시간
#   inside_m    : 이벤트 지점을 이만큼 지나친 차량도 대상 (기상 구간은 지나쳐도 그 안에 있다)
RULES = {
    "hard_brake":      {"min_reports": 1, "radius_m": 1500, "lanes": "same", "cooldown_s": 20,  "inside_m": 0},
    "stopped_vehicle": {"min_reports": 1, "radius_m": 2000, "lanes": "all",  "cooldown_s": 60,  "inside_m": 0},
    "crash":           {"min_reports": 1, "radius_m": 3000, "lanes": "all",  "cooldown_s": 120, "inside_m": 0},
    "heavy_rain":      {"min_reports": 2, "radius_m": 5000, "lanes": "all",  "cooldown_s": 300, "inside_m": 500},
    "fog":             {"min_reports": 2, "radius_m": 5000, "lanes": "all",  "cooldown_s": 300, "inside_m": 500},
    "slippery":        {"min_reports": 2, "radius_m": 5000, "lanes": "all",  "cooldown_s": 300, "inside_m": 500},
    "congestion":      {"min_reports": 3, "radius_m": 1000, "lanes": "all",  "cooldown_s": 120, "inside_m": 0},
}

# 정체·사고 구간 알림: 느린 차량이 촘촘히 이어진 무리를 정체로 보고, 사고 증거가 있으면 사고 구간으로 알린다
JAM_SPEED_KPH = 30       # 이보다 느리면 정체 중인 차량
JAM_GAP_M = 150          # 이 간격 이내로 이어져야 같은 구간
JAM_MIN_VEHICLES = 3     # 구간으로 확정하는 최소 차량 수 (합의)
STOPPED_KPH = 2          # 이보다 느리면 멈춘 차량
ZONE_LABELS = {"accident": "사고 구간", "suspected": "사고·장애물 추정 구간", "jam": "정체 구간"}

# EV 충전소: 시연용 고정 목록 (실제로는 공공 충전소 API와 연동)
CHARGERS = [
    {"id": "PANGYO", "name": "판교역 충전소", "lat": 37.4020, "lon": 127.1080, "stalls": 2},
    {"id": "SIHEUNG", "name": "시흥동 충전소", "lat": 37.4180, "lon": 127.1000, "stalls": 2},
]
CHARGER_RADIUS_M = 80    # 이 안에 있으면 충전소에 있는 차량
BATTERY_KWH = 77.4       # 충전 시간 어림용 가정
CHARGE_KW = 100
TARGET_SOC = 80

# 긴급차량 길 터주기
EMERGENCY_ROLES = {"ambulance": ("구급차", "🚑"), "fire": ("소방차", "🚒"), "police": ("경찰차", "🚓")}
EMERGENCY_RANGE_M = 1000  # 긴급차량 앞쪽 이 거리 안의 차량에 알린다
EMERGENCY_UPDATE_S = 2    # 접근 거리 갱신 주기

lock = threading.Lock()
subscribers = {}   # vehicle_id -> {"queue": list, "cond": Condition}
vehicles = {}      # vehicle_id -> {"lat", "lon", "heading", "speed_kph", "lane", "ts"}
reports = []       # 즉시성 이벤트 보고
weather_obs = {}   # vehicle_id -> 최근 기상 관측 {"lat", "lon", "rain", "fog", "slippery", "ts"}
events = []        # 확정된 이벤트 (대시보드용)
alerts = []        # 보낸 경고 (대시보드용)
sent = {}          # (vehicle_id, type, zone_key) -> 마지막 전송 시각
emotes = []        # 보낸 이모트 (대시보드용)
last_emote = {}    # vehicle_id -> 마지막 이모트 전송 시각

# 이모트: 정해진 긍정·중립 표현만 허용한다 (자유 입력은 시비·보복운전에 악용될 수 있다)
EMOTES = {"thanks": "👍", "sorry": "🙏", "hello": "👋"}
EMOTE_RANGE_M = 150       # 이 거리 안의 바로 앞차·뒤차에게만 보낸다
EMOTE_COOLDOWN_S = 5      # 한 차량이 이모트를 보낼 수 있는 최소 간격
OTHER_LANE_PENALTY_M = 30 # 같은 거리면 같은 차로 차량을 우선한다

# 기여 통계와 신뢰 점수: 경고 피드백으로 보고 차량의 신뢰도를 갱신하고, 합의 기준에 반영한다
stats = defaultdict(lambda: {"reports": 0, "helped": 0, "emotes": {k: 0 for k in EMOTES},
                             "correct": 0, "incorrect": 0})
event_reporters = {}      # event_id -> 보고 차량들 (피드백을 누구에게 반영할지)
feedback_given = set()    # (피드백한 차량, event_id) — 같은 경고에 한 번만
LOW_TRUST = 0.3           # 피드백 3건 이상에서 이보다 낮으면 혼자서는 확정되지 않는다
MIN_FEEDBACK = 3
MIN_RANKED = 5            # 이 대수 이상 참여해야 상위 %를 보여준다

# 차로 변경 의도 공유
MERGE_BEHIND_M = 60       # 진입하려는 차로에서 내 뒤 이 거리 안의 차량만 대상
MERGE_ALONGSIDE_M = 5     # 바로 옆(사각지대)에 있는 차량도 대상
MERGE_WINDOW_S = 10       # 깜빡이 후 이 시간 안에 차로가 바뀌면 진입 성공
lane_intents = {}         # 깜빡이 켠 차량 -> {"dir", "from_lane", "target", "since", "notified": set}


# ---------- 위치 계산 ----------

def distance_m(lat1, lon1, lat2, lon2):
    r = 6371000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def relative(observer, lat, lon):
    """관찰 차량 기준 지점의 (전방 거리, 측방 거리) m. 전방 거리가 양수면 앞쪽."""
    dy = (lat - observer["lat"]) * 111320
    dx = (lon - observer["lon"]) * 111320 * math.cos(math.radians(observer["lat"]))
    h = math.radians(observer["heading"])
    along = dx * math.sin(h) + dy * math.cos(h)
    cross = dx * math.cos(h) - dy * math.sin(h)
    return along, cross


def same_direction(a, b):
    return abs((a - b + 180) % 360 - 180) <= SAME_DIRECTION_DEG


def lane_relation(a, b):
    if a is None or b is None:
        return "unknown"
    return "same" if a == b else ("adjacent" if abs(a - b) == 1 else "other")


def live_vehicles(now):
    return {k: v for k, v in vehicles.items() if now - v["ts"] <= VEHICLE_TTL_S}


# ---------- 신뢰 점수 ----------

def trust(vid):
    """경고가 맞았다는 피드백 비율 (라플라스 보정: 피드백이 없으면 0.5)."""
    s = stats[vid]
    return (s["correct"] + 1) / (s["correct"] + s["incorrect"] + 2)


def trust_weight(vid):
    """합의 계산에서 이 차량의 보고가 차지하는 무게 (0.5~1.5, 기본 1.0)."""
    return 0.5 + trust(vid)


def low_trust(vid):
    s = stats[vid]
    return s["correct"] + s["incorrect"] >= MIN_FEEDBACK and trust(vid) < LOW_TRUST


def consensus_met(reporters, min_reports):
    """신뢰 가중치 합이 필요한 대수 이상이어야 확정. 신뢰가 낮은 차량 혼자서는 확정되지 않는다."""
    if sum(trust_weight(r) for r in reporters) < min_reports - 1e-9:
        return False
    if min_reports == 1 and len(reporters) == 1 and low_trust(next(iter(reporters))):
        return False
    return True


# ---------- 전파 ----------

def push(vid, item):
    sub = subscribers.get(vid)
    if not sub:
        return False
    with sub["cond"]:
        sub["queue"].append(item)
        sub["cond"].notify()
    return True


def propagate(event, reporters, now, verbose=True):
    """확정된 이벤트를 규칙에 맞는 후방 차량에 보낸다. 호출 시 lock을 잡고 있어야 한다.
    (경고받은 차량, 차량별 판단 사유)를 돌려준다."""
    rule = RULES[event["type"]]
    zone_key = event.get("zone_key") or (round(event["lat"], 3), round(event["lon"], 3))
    notified, decisions = [], {}
    for vid, v in live_vehicles(now).items():
        if vid in reporters:
            continue
        along, cross = relative(v, event["lat"], event["lon"])
        lanes = lane_relation(v.get("lane"), event.get("lane"))
        if event.get("heading") is not None and not same_direction(v["heading"], event["heading"]):
            decisions[vid] = "반대 방향"
            continue
        if along <= -rule["inside_m"]:
            decisions[vid] = "이미 지나침(이벤트가 뒤쪽)"
            continue
        if along > rule["radius_m"]:
            decisions[vid] = f"반경 밖 {round(along)}m"
            continue
        if rule["lanes"] == "same" and lanes not in ("same", "unknown"):
            decisions[vid] = f"다른 차로({lanes})"
            continue
        key = (vid, event["type"], zone_key)
        if now - sent.get(key, 0) < rule["cooldown_s"]:
            decisions[vid] = "최근 전송함"
            continue
        sent[key] = now
        item = {
            "type": event["type"], "distance_m": round(along), "lane": lanes,
            "reporters": len(reporters), "speed_kph": event.get("speed_kph"), "ts": now,
            "event_id": event.get("event_id"),
            **{k: event[k] for k in ("zone", "label", "length_m") if k in event},
        }
        if push(vid, item):
            notified.append(vid)
            alerts.append({"to": vid, **item})
            decisions[vid] = "경고"
    del alerts[:-50]
    for r in reporters:  # 내 보고로 경고를 받은 차량 수 = 기여
        stats[r]["helped"] += len(notified)
    if verbose or notified:
        for vid, why in decisions.items():
            print(f"    {vid}: {why}", flush=True)
    return notified, decisions


def handle_report(event):
    """즉시성 이벤트 보고: 합의되면 전파한다."""
    now = time.time()
    event["ts"] = now
    with lock:
        me = vehicles.get(event["vehicle"])
        if me:
            event.setdefault("heading", me["heading"])
            event.setdefault("lane", me.get("lane"))
        stats[event["vehicle"]]["reports"] += 1
        reports.append(event)
        reports[:] = [r for r in reports if now - r["ts"] <= REPORT_WINDOW_S]
        nearby = [r for r in reports if r["type"] == event["type"]
                  and distance_m(r["lat"], r["lon"], event["lat"], event["lon"]) <= CLUSTER_RADIUS_M]
        reporters = {r["vehicle"] for r in nearby}
        if not consensus_met(reporters, RULES[event["type"]]["min_reports"]):
            print(f"  신뢰 합의 부족 (보고 {len(reporters)}대) → 확정 대기", flush=True)
            return False, []
        event["event_id"] = f"{event['type']}-{event['vehicle']}-{int(now * 1000)}"
        event_reporters[event["event_id"]] = set(reporters)
        record = {k: event.get(k) for k in ("type", "vehicle", "lat", "lon", "lane", "ts", "event_id")}
        events.append(record)
        del events[:-30]
        notified, record["decisions"] = propagate(event, reporters, now)
        return True, notified


def handle_weather(obs):
    """기상 관측: 같은 구간에서 서로 다른 차량 N대가 같은 위험을 관측하면 확정·전파한다."""
    now = time.time()
    obs["ts"] = now
    confirmed = []
    with lock:
        weather_obs[obs["vehicle"]] = obs
        if obs.get("rain", 0) >= 3 or obs.get("fog") or obs.get("slippery"):
            stats[obs["vehicle"]]["reports"] += 1
        recent = [o for o in weather_obs.values() if now - o["ts"] <= WEATHER_WINDOW_S]
        for hazard, test in (("heavy_rain", lambda o: o.get("rain", 0) >= 3),
                             ("fog", lambda o: o.get("fog")),
                             ("slippery", lambda o: o.get("slippery"))):
            if not test(obs):
                continue
            seen = [o for o in recent if test(o)
                    and distance_m(o["lat"], o["lon"], obs["lat"], obs["lon"]) <= WEATHER_ZONE_M]
            reporters = {o["vehicle"] for o in seen}
            if not consensus_met(reporters, RULES[hazard]["min_reports"]):
                print(f"[weather] {hazard} 관측 {len(reporters)}대 (확정 대기)", flush=True)
                continue
            # 관측 차량들의 중심을 위험 구간 위치로 쓴다
            event = {
                "type": hazard, "vehicle": obs["vehicle"],
                "lat": sum(o["lat"] for o in seen) / len(seen),
                "lon": sum(o["lon"] for o in seen) / len(seen),
                "heading": obs.get("heading"), "lane": None, "ts": now,
                "event_id": f"{hazard}-{int(now * 1000)}",
            }
            event_reporters[event["event_id"]] = set(reporters)
            print(f"[weather] {hazard} 확정 ({len(reporters)}대)", flush=True)
            notified, decisions = propagate(event, reporters, now)
            if not any(e["type"] == hazard and now - e["ts"] < 60 for e in events):
                events.append({**{k: event.get(k) for k in ("type", "vehicle", "lat", "lon", "lane", "ts")},
                               "reporters": sorted(reporters), "decisions": decisions})
                del events[:-30]
            confirmed.append((hazard, notified))
    return confirmed


# ---------- HTTP ----------

def handle_emote(body):
    """보낸 차량 기준 바로 앞차 또는 뒤차를 찾아 이모트를 전달한다. 받는 쪽에는 상대 ID를 알리지 않는다."""
    now = time.time()
    with lock:
        me = live_vehicles(now).get(body["vehicle"])
        if me is None:
            return {"delivered": False, "reason": "위치 정보 없음"}
        if body["emote"] not in EMOTES or body["target"] not in ("ahead", "behind"):
            return {"delivered": False, "reason": "허용되지 않은 이모트"}
        if now - last_emote.get(body["vehicle"], 0) < EMOTE_COOLDOWN_S:
            return {"delivered": False, "reason": "잠시 후 다시 보내주세요"}

        want_ahead = body["target"] == "ahead"
        best, best_score = None, None
        for vid, v in live_vehicles(now).items():
            if vid == body["vehicle"] or not same_direction(me["heading"], v["heading"]):
                continue
            along, _ = relative(me, v["lat"], v["lon"])
            if (along > 0) != want_ahead or abs(along) > EMOTE_RANGE_M:
                continue
            score = abs(along) + (0 if lane_relation(me.get("lane"), v.get("lane")) in ("same", "unknown")
                                  else OTHER_LANE_PENALTY_M)
            if best_score is None or score < best_score:
                best, best_score = vid, score
        if best is None:
            return {"delivered": False, "reason": f"{EMOTE_RANGE_M}m 안에 {'앞' if want_ahead else '뒤'}차가 없음"}

        last_emote[body["vehicle"]] = now
        # 받는 차 입장에서 보낸 차의 위치: 내가 뒤차에게 보냈다면 받는 쪽에서 나는 앞차다
        deliver_emote(body["vehicle"], best, body["emote"], "ahead" if not want_ahead else "behind", now)
        return {"delivered": True, "target": body["target"]}


def deliver_emote(sender, receiver, emote, from_side, now, auto=False):
    """이모트 하나를 전달하고 기록한다. 호출 시 lock을 잡고 있어야 한다."""
    push(receiver, {"kind": "emote", "type": "emote", "emote": emote, "symbol": EMOTES[emote],
                    "from": from_side, "auto": auto, "ts": now})
    stats[receiver]["emotes"][emote] += 1
    emotes.append({"from": sender, "to": receiver, "emote": emote, "auto": auto, "ts": now})
    del emotes[:-20]
    print(f"[emote] {sender} -> {receiver}: {EMOTES[emote]} {emote}{' (자동)' if auto else ''}", flush=True)


def handle_feedback(body):
    """받은 경고가 맞았는지 피드백: 그 경고를 보고한 차량들의 신뢰 점수에 반영한다."""
    key = (body["vehicle"], body["event_id"])
    with lock:
        reporters = event_reporters.get(body["event_id"])
        if reporters is None:
            return {"ok": False, "reason": "알 수 없는 경고"}
        if body["vehicle"] in reporters or key in feedback_given:
            return {"ok": False, "reason": "이미 반영됨"}
        feedback_given.add(key)
        for r in reporters:
            stats[r]["correct" if body["correct"] else "incorrect"] += 1
        print(f"[feedback] {body['vehicle']} -> {sorted(reporters)}: {'맞음' if body['correct'] else '아님'}", flush=True)
        return {"ok": True}


def forget(vid):
    """데이터 공유 동의 철회: 이 차량의 위치·관측·통계를 지운다. (보고 이력은 익명 이벤트로만 남는다)"""
    with lock:
        removed = [name for name, table in (("위치", vehicles), ("기상 관측", weather_obs), ("통계", stats),
                                            ("차로 변경", lane_intents)) if table.pop(vid, None) is not None]
    print(f"[forget] {vid} 데이터 삭제: {', '.join(removed) or '없음'}", flush=True)
    return {"ok": True, "removed": removed}


def score(vid):
    """베스트 드라이버 점수: 도운 차량 수와 받은 감사, 경고 정확도를 함께 본다 (👍 몰아주기만으로는 오르기 어렵게)."""
    s = stats[vid]
    return (s["helped"] + 2 * s["emotes"]["thanks"] + s["emotes"]["hello"]) * (0.5 + trust(vid))


def vehicle_stats(vid):
    """내 기여 화면에 보여줄 통계와 배지. 순위표는 공개하지 않고 내 위치(상위 %)만 알려준다."""
    with lock:
        s = stats[vid]
        active = [v for v in stats if score(v) > 0]
        mine = score(vid)
        better = sum(1 for v in active if score(v) > mine)
        # 참여 차량이 적으면 "상위 100%"처럼 어색한 값이 나오므로 순위 %는 숨긴다
        top_pct = (round(100 * (better + 1) / len(active))
                   if len(active) >= MIN_RANKED and mine > 0 else None)
        feedback = s["correct"] + s["incorrect"]
        badges = []
        if mine > 0 and (better == 0 or (top_pct or 100) <= 10) and trust(vid) >= 0.6:
            badges.append("베스트 드라이버")
        if s["helped"] >= 10:
            badges.append("정보 기여왕")
        if s["emotes"]["thanks"] >= 3:
            badges.append("매너 드라이버")
        return {
            "vehicle": vid, "reports": s["reports"], "helped": s["helped"], "emotes": dict(s["emotes"]),
            "accuracy": round(100 * s["correct"] / feedback) if feedback else None,
            "trust": round(trust(vid), 2), "top_pct": top_pct, "badges": badges,
        }


def detect_zones(now):
    """느린 차량 무리(정체)를 찾고, 사고 증거가 있으면 사고 구간으로 구분한다. 호출 시 lock을 잡고 있어야 한다.

    - accident  : 구간 안이나 바로 앞에서 사고(crash) 보고가 있었다 (확정)
    - suspected : 정차 차량 보고가 있거나, 다른 차는 움직이는데 한 지점에 멈춘 차량이 있다 (추정)
    - jam       : 그 외 단순 정체
    """
    live = live_vehicles(now)
    if not live:
        return []
    ref = next(iter(live.values()))
    pos = {vid: relative(ref, v["lat"], v["lon"])[0] for vid, v in live.items()
           if same_direction(ref["heading"], v["heading"]) and v.get("role") not in EMERGENCY_ROLES}
    slow = sorted((a, vid) for vid, a in pos.items() if (live[vid].get("speed_kph") or 0) < JAM_SPEED_KPH)

    groups, cur = [], []
    for a, vid in slow:
        if cur and a - cur[-1][0] > JAM_GAP_M:
            groups.append(cur)
            cur = []
        cur.append((a, vid))
    if cur:
        groups.append(cur)

    def near(e, lo, hi):
        return lo <= relative(ref, e["lat"], e["lon"])[0] <= hi

    zones = []
    for g in groups:
        members = [vid for _, vid in g]
        if len(members) < JAM_MIN_VEHICLES:
            continue
        (tail_a, tail), (head_a, head) = g[0], g[-1]
        speeds = [live[m].get("speed_kph") or 0 for m in members]
        if any(e["type"] == "crash" and now - e["ts"] < 600 and near(e, tail_a - 50, head_a + 300) for e in events):
            kind = "accident"
        elif (any(e["type"] == "stopped_vehicle" and now - e["ts"] < 600 and near(e, tail_a - 50, head_a + 300)
                  for e in events)
              or (min(speeds) < STOPPED_KPH and sum(s > 5 for s in speeds) >= len(speeds) / 2)):
            kind = "suspected"
        else:
            kind = "jam"
        zones.append({
            "members": members, "tail": tail, "head": head, "kind": kind, "label": ZONE_LABELS[kind],
            "tail_lat": live[tail]["lat"], "tail_lon": live[tail]["lon"], "head_lat": live[head]["lat"],
            "heading": live[tail]["heading"], "length_m": round(head_a - tail_a),
            "avg_kph": round(sum(speeds) / len(speeds)),
        })
    return zones


def charge_seconds(soc):
    """목표 충전량까지 걸리는 시간 (시연용 고정 출력·용량 가정)."""
    return max(0.0, TARGET_SOC - (soc or 0)) / 100 * BATTERY_KWH / CHARGE_KW * 3600


def charger_state(charger, live, extra=None):
    """충전소별 현재 충전·대기 차량과, 이곳으로 향하는 차량들의 도착 시점 대기를 예측한다.

    extra = (vid, eta_s, soc) 이면 그 차량이 이 충전소로 간다고 가정해 함께 계산한다 (대안 충전소 비교용).
    """
    at, coming = [], []
    for vid, v in live.items():
        if distance_m(v["lat"], v["lon"], charger["lat"], charger["lon"]) <= CHARGER_RADIUS_M:
            at.append(vid)
        elif v.get("dest") and distance_m(v["dest"]["lat"], v["dest"]["lon"], charger["lat"], charger["lon"]) <= CHARGER_RADIUS_M:
            coming.append((v["dest"].get("eta_s") or 0, vid, v.get("soc")))
    charging = [vid for vid in at if live[vid].get("charging")]
    waiting = [vid for vid in at if not live[vid].get("charging")]
    if extra and extra[0] not in at:
        coming = [c for c in coming if c[1] != extra[0]] + [(extra[1], extra[0], extra[2])]

    # 충전기마다 비는 시각(초). 충전 중인 차량은 남은 충전 시간 뒤에 빈다.
    free = sorted(charge_seconds(live[v].get("soc")) for v in charging[:charger["stalls"]])
    free += [0.0] * (charger["stalls"] - len(free))
    queue = [(0.0, v, live[v].get("soc")) for v in charging[charger["stalls"]:] + waiting] + sorted(coming)

    forecast, starts = {}, []
    for eta, vid, soc in queue:
        free.sort()
        start = max(eta, free[0])
        free[0] = start + charge_seconds(soc)
        # 내가 도착했을 때 아직 충전을 시작하지 못하고 기다리는 앞 차량 수
        ahead = sum(1 for s, e in starts if s > eta and e <= eta)
        forecast[vid] = {"wait_s": round(start - eta), "ahead": ahead, "eta_s": round(eta)}
        starts.append((start, eta))
    return {"id": charger["id"], "name": charger["name"], "stalls": charger["stalls"],
            "charging": len(charging), "waiting": len(waiting), "coming": len(coming), "forecast": forecast}


def charger_of(dest):
    if not dest:
        return None
    return next((c for c in CHARGERS
                 if distance_m(dest["lat"], dest["lon"], c["lat"], c["lon"]) <= CHARGER_RADIUS_M), None)


current_zones = []     # 대시보드용
current_chargers = []  # 대시보드용
charging_sent = {}     # vehicle_id -> 마지막으로 보낸 충전 예측 (바뀔 때만 다시 보낸다)
emergency_sent = {}    # (긴급차량, 차량) -> 마지막 전송 시각


def zone_notices(now):
    for z in current_zones:
        event = {
            "type": "congestion", "lat": z["tail_lat"], "lon": z["tail_lon"], "heading": z["heading"], "lane": None,
            "zone": z["kind"], "label": z["label"], "length_m": z["length_m"],
            # 정체 꼬리는 계속 움직이므로 지점 대신 구간 머리·종류로 재전송을 막는다 (종류가 바뀌면 다시 알림)
            "zone_key": ("zone", z["head"], z["kind"]),
        }
        notified, _ = propagate(event, set(z["members"]), now, verbose=False)
        if notified:
            print(f"[zone] {z['label']} {len(z['members'])}대 {z['length_m']}m -> {notified}", flush=True)


def charging_forecasts(now):
    global current_chargers
    live = live_vehicles(now)
    current_chargers = [charger_state(c, live) for c in CHARGERS]
    for vid, v in live.items():
        c = charger_of(v.get("dest"))
        if not c:
            charging_sent.pop(vid, None)
            continue
        state = next(s for s in current_chargers if s["id"] == c["id"])
        f = state["forecast"].get(vid)
        if not f:
            continue
        # 다른 충전소로 가면 얼마나 기다릴지: 거리 비율로 도착 시간을 어림한다
        alt = None
        for other in CHARGERS:
            if other["id"] == c["id"]:
                continue
            ratio = (distance_m(v["lat"], v["lon"], other["lat"], other["lon"])
                     / max(1.0, distance_m(v["lat"], v["lon"], c["lat"], c["lon"])))
            alt_f = charger_state(other, live, (vid, f["eta_s"] * ratio, v.get("soc")))["forecast"][vid]
            if alt_f["wait_s"] + 300 < f["wait_s"] and (alt is None or alt_f["wait_s"] < alt["wait_min"] * 60):
                alt = {"name": other["name"], "wait_min": round(alt_f["wait_s"] / 60)}
        item = {"kind": "charging", "type": "charging", "station": c["name"], "ahead": f["ahead"],
                # 향하는 EV 수에서 자기 자신은 뺀다
                "wait_min": round(f["wait_s"] / 60), "coming": max(0, state["coming"] - 1), "alt": alt, "ts": now}
        signature = (item["station"], item["ahead"], item["wait_min"], item["coming"], json.dumps(alt))
        if charging_sent.get(vid) != signature and push(vid, item):
            charging_sent[vid] = signature
            print(f"[charging] {vid} -> {c['name']} 앞 {f['ahead']}대, 대기 {item['wait_min']}분"
                  f"{', 대안 ' + alt['name'] if alt else ''}", flush=True)


def emergency_yield(now):
    """긴급차량 앞쪽 차량들에 접근 거리를 계속 알리고, 지나가면 통과를 알린다."""
    live = live_vehicles(now)
    for em_id, em in live.items():
        role = em.get("role")
        if role not in EMERGENCY_ROLES:
            continue
        label, symbol = EMERGENCY_ROLES[role]
        for vid, v in live.items():
            if vid == em_id or not same_direction(v["heading"], em["heading"]):
                continue
            behind = -relative(v, em["lat"], em["lon"])[0]  # 긴급차량이 내 뒤 몇 m에 있는가
            key = (em_id, vid)
            item = {"kind": "emergency", "type": "emergency", "role": role, "label": label, "symbol": symbol,
                    "distance_m": round(behind), "lane": lane_relation(v.get("lane"), em.get("lane")), "ts": now}
            if 0 < behind <= EMERGENCY_RANGE_M:
                if now - emergency_sent.get(key, 0) >= EMERGENCY_UPDATE_S:
                    if push(vid, {**item, "status": "approaching"}) and key not in emergency_sent:
                        print(f"[emergency] {label} {em_id} -> {vid} 뒤 {round(behind)}m ({item['lane']})", flush=True)
                    emergency_sent[key] = now
            elif key in emergency_sent and behind <= 0:
                push(vid, {**item, "status": "passed"})
                print(f"[emergency] {label} {em_id} 통과 -> {vid}", flush=True)
                del emergency_sent[key]


CLOSE_ALERT_M = 300  # 사고·정차 지점에 이만큼 가까워지면 한 번 더 (앱에서는 빨간 경고로) 알린다
close_sent = set()   # (차량, 이벤트 시각) — 가까워짐 알림을 이미 보낸 조합


def close_alerts(now):
    """멀리서 카드로 알린 사고·정차 지점에 가까워진 차량에 다시 알린다."""
    live = live_vehicles(now)
    for e in events:
        if e["type"] not in ("crash", "stopped_vehicle") or now - e["ts"] > 600:
            continue
        for vid, v in live.items():
            key = (vid, e["ts"])
            if vid == e["vehicle"] or key in close_sent:
                continue
            along, _ = relative(v, e["lat"], e["lon"])
            if 0 < along <= CLOSE_ALERT_M:
                close_sent.add(key)
                item = {"type": e["type"], "distance_m": round(along),
                        "lane": lane_relation(v.get("lane"), e.get("lane")), "reporters": 1, "ts": now}
                if push(vid, item):
                    alerts.append({"to": vid, **item})
                    print(f"[close] {vid}: {e['type']} {round(along)}m", flush=True)


def lane_changes(now):
    """깜빡이를 켠 차량과, 그 차로 바로 뒤에서 다가오는 차량에 서로 알린다. 진입에 성공하면 감사 인사를 연결한다.

    대상은 엄격하게 고른다: 깜빡이 방향의 바로 옆 차로, 같은 진행 방향, 내 뒤 60m 안(바로 옆 포함),
    그리고 나보다 빠르게 다가오는 차량 중 가장 가까운 한 대.
    """
    live = live_vehicles(now)
    for vid, intent in list(lane_intents.items()):
        me = live.get(vid)
        if me is None or now - intent["since"] > MERGE_WINDOW_S + 5:
            del lane_intents[vid]
            continue
        if me.get("lane") == intent["target"]:  # 진입 성공
            del lane_intents[vid]
            merge_done(vid, me, live, now)
            continue
        if now - intent["since"] > MERGE_WINDOW_S:
            continue
        best, best_gap = None, None
        for oid, o in live.items():
            if oid == vid or o.get("lane") != intent["target"] or not same_direction(me["heading"], o["heading"]):
                continue
            ahead_of_me = relative(me, o["lat"], o["lon"])[0]  # 음수 = 내 뒤
            if not -MERGE_BEHIND_M <= ahead_of_me <= MERGE_ALONGSIDE_M:
                continue
            closing = (o.get("speed_kph") or 0) > (me.get("speed_kph") or 0) or abs(ahead_of_me) <= MERGE_ALONGSIDE_M
            if closing and (best_gap is None or abs(ahead_of_me) < best_gap):
                best, best_gap = oid, abs(ahead_of_me)
        if best and best not in intent["notified"]:
            intent["notified"].add(best)
            gap = round(best_gap)
            push(vid, {"kind": "lane", "type": "lane", "role": "merging", "distance_m": gap,
                       "side": intent["dir"], "ts": now})
            push(best, {"kind": "lane", "type": "lane", "role": "yielding", "distance_m": gap,
                        "side": intent["dir"], "ts": now})
            print(f"[lane] {vid} → {intent['target']}차로 진입 의도, 뒤 {gap}m {best}에 알림", flush=True)


def merge_done(vid, me, live, now):
    """진입 성공: 새 차로에서 바로 뒤에 있는 차량(양보해 준 차)에게 감사 인사를 연결한다."""
    yielder, gap = None, None
    for oid, o in live.items():
        if oid == vid or o.get("lane") != me.get("lane") or not same_direction(me["heading"], o["heading"]):
            continue
        behind = -relative(me, o["lat"], o["lon"])[0]
        if 0 < behind <= MERGE_BEHIND_M and (gap is None or behind < gap):
            yielder, gap = oid, behind
    if yielder is None:
        print(f"[lane] {vid} 진입 성공 (뒤차 없음)", flush=True)
        return
    if me.get("auto_thanks"):
        # 운전자 조작 없이 자동으로 감사 인사: 차로 변경 직후는 화면을 보면 안 되는 순간이다
        deliver_emote(vid, yielder, "thanks", "ahead", now, auto=True)
        push(vid, {"kind": "merge_done", "type": "merge_done", "auto": True, "ts": now})
    else:
        push(vid, {"kind": "merge_done", "type": "merge_done", "auto": False, "ts": now})
    print(f"[lane] {vid} 진입 성공, 양보한 뒤차 {yielder} ({round(gap)}m)", flush=True)


def road_loop():
    """1초마다 정체·사고 구간, 충전 대기 예측, 긴급차량 접근을 갱신해 해당 차량에 보낸다."""
    global current_zones
    while True:
        time.sleep(1)
        now = time.time()
        with lock:
            current_zones = detect_zones(now)
            zone_notices(now)
            charging_forecasts(now)
            emergency_yield(now)
            close_alerts(now)
            lane_changes(now)


DASHBOARD = Path(__file__).with_name("dashboard.html")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # 요청 로그 대신 이벤트 로그만 출력

    def _send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, body):
        self._send(code, json.dumps(body, ensure_ascii=False).encode(), "application/json; charset=utf-8")

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == "/stream":
            self._stream(q.get("vehicle"))
        elif url.path == "/status":
            now = time.time()
            with lock:
                body = {
                    "now": now,
                    "vehicles": live_vehicles(now),
                    "events": events[-10:],
                    "alerts": alerts[-20:],
                    "emotes": emotes[-10:],
                    "stats": {v: {**stats[v], "trust": round(trust(v), 2)} for v in list(stats)},
                    "lane_intents": {v: {k: i[k] for k in ("dir", "target", "since")} for v, i in lane_intents.items()},
                    "zones": current_zones,
                    "chargers": [{**s, **next(c for c in CHARGERS if c["id"] == s["id"])} for s in current_chargers],
                    "weather": [o for o in weather_obs.values() if now - o["ts"] <= WEATHER_WINDOW_S],
                }
            self._json(200, body)
        elif url.path == "/stats":
            if not q.get("vehicle"):
                self._json(400, {"error": "vehicle required"})
            else:
                self._json(200, vehicle_stats(q["vehicle"]))
        elif url.path == "/dashboard":
            self._send(200, DASHBOARD.read_bytes(), "text/html; charset=utf-8")
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        required = {
            "/position": ("vehicle", "lat", "lon", "heading"),
            "/events": ("vehicle", "type", "lat", "lon"),
            "/emote": ("vehicle", "emote", "target"),
            "/feedback": ("vehicle", "event_id", "correct"),
            "/forget": ("vehicle",),
        }.get(path)
        if required is None:
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length))
            for key in required:
                if key not in body:
                    raise ValueError(f"missing {key}")
        except (ValueError, json.JSONDecodeError) as e:
            self._json(400, {"error": str(e)})
            return

        if path == "/forget":
            self._json(200, forget(body["vehicle"]))
            return

        if path == "/position":
            with lock:
                prev = vehicles.get(body["vehicle"], {})
                turn, lane = body.get("turn"), body.get("lane")
                # 깜빡이가 새로 켜지면 진입하려는 차로를 기록한다 (1차로가 가장 왼쪽)
                if turn in ("LEFT", "RIGHT") and turn != prev.get("turn") and lane:
                    target = lane - 1 if turn == "LEFT" else lane + 1
                    if target >= 1:
                        lane_intents[body["vehicle"]] = {"dir": turn, "from_lane": lane, "target": target,
                                                         "since": time.time(), "notified": set()}
                vehicles[body["vehicle"]] = {
                    "lat": body["lat"], "lon": body["lon"], "heading": body["heading"],
                    "speed_kph": body.get("speed_kph"), "lane": body.get("lane"), "turn": body.get("turn"),
                    "role": body.get("role"), "soc": body.get("soc"), "charging": body.get("charging"),
                    "dest": body.get("dest"), "auto_thanks": body.get("auto_thanks", False), "ts": time.time(),
                }
            self._json(200, {"ok": True})
        elif path == "/emote":
            self._json(200, handle_emote(body))
        elif path == "/feedback":
            self._json(200, handle_feedback(body))
        elif body["type"] == "weather_obs":
            confirmed = handle_weather(body)
            self._json(200, {"confirmed": [{"type": t, "notified": n} for t, n in confirmed]})
        elif body["type"] in RULES:
            print(f"[event] {body['vehicle']} {body['type']} lane={body.get('lane')} "
                  f"speed={body.get('speed_kph')}", flush=True)
            confirmed, notified = handle_report(body)
            print(f"  -> confirmed={confirmed} notified={notified}", flush=True)
            self._json(200, {"confirmed": confirmed, "notified": notified})
        else:
            self._json(400, {"error": f"unknown type {body['type']}"})

    def _stream(self, vid):
        if not vid:
            self._json(400, {"error": "vehicle required"})
            return
        sub = {"queue": [], "cond": threading.Condition()}
        with lock:
            subscribers[vid] = sub
        print(f"[subscribe] {vid}", flush=True)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        try:
            self.wfile.write(b": connected\n\n")
            self.wfile.flush()
            while True:
                with sub["cond"]:
                    sub["cond"].wait_for(lambda: sub["queue"], timeout=15)
                    items, sub["queue"] = sub["queue"], []
                if not items:
                    self.wfile.write(b": ping\n\n")  # 연결 유지
                for item in items:
                    self.wfile.write(f"data: {json.dumps(item, ensure_ascii=False)}\n\n".encode())
                    if item.get("kind") not in ("emote", "charging", "emergency", "lane", "merge_done"):
                        print(f"[notify] -> {vid}: {item['type']} {item['distance_m']}m ({item['lane']})", flush=True)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            with lock:
                if subscribers.get(vid) is sub:
                    del subscribers[vid]
            print(f"[unsubscribe] {vid}", flush=True)


if __name__ == "__main__":
    print(f"HyperConnect server on 0.0.0.0:{PORT}", flush=True)
    threading.Thread(target=road_loop, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
