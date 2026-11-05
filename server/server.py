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
  POST /rating                            충전 후 충전소 별점 (1~5)
  POST /public_status                     공공 충전기 상태 입력 (환경공단 API 대체, 시연용)
  POST /demo                              시연 설정 (charge_time_scale: 충전 시간 배속)
  GET  /stats?vehicle=B                   내 기여 통계·신뢰 점수·배지
  GET  /chargers?vehicle=B                충전소 지도: 모든 충전소의 별점·혼잡·이 차량의 예상 완료 시각
  GET  /feed/weather[?format=geojson]     외부 제공용 기상 위험 구간 피드 (현대차 내비·외부 서비스가 가져간다)
  (서버가 1초마다 사고 정체 구간, 기상 구간, EV 충전 대기 예측, 긴급차량 접근을 계산해 해당 차량에 보낸다)
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
WEATHER_WINDOW_S = 300    # 기상 관측이 유효한 기간 (차량은 상태가 바뀔 때·주기적으로 다시 보낸다)
WEATHER_ZONE_M = 1000     # 같은 기상 구간으로 보는 거리

# 기상 확정 (rev2): "2대 합의"가 아니라 한 지역의 많은 차량이 거의 동시에 와이퍼를 켜는 순간(동시 다발)을 잡는다.
#   min_onsets : BURST_WINDOW_S 안에 새로 켠(꺼짐 → 켜짐) 서로 다른 차량 수 (신뢰 가중)
#   share      : 그 지역(SHARE_RADIUS_M) 참여 차량 중 지금 같은 관측을 하는 비율
#   한 대의 세차·워셔액, 터널 출구 한두 대는 걸러지고, 실제 소나기는 지역 차량이 몇십 초 안에 함께 반응한다.
BURST_WINDOW_S = 90
SHARE_RADIUS_M = 500
WEATHER_RULES = {
    "heavy_rain": {"min_onsets": 5, "share": 0.6, "test": lambda o: o.get("rain", 0) >= 3},
    "fog":        {"min_onsets": 5, "share": 0.6, "test": lambda o: bool(o.get("fog"))},
    "slippery":   {"min_onsets": 3, "share": 0.5, "test": lambda o: bool(o.get("slippery"))},  # ABS·ESC 개입은 더 드물고 확실하다
}
WEATHER_END_SHARE = 0.3   # 확정된 구간은 관측 비율이 이 아래로 떨어지면 해제 (켜짐·꺼짐이 깜빡이지 않게 문턱을 다르게)
WEATHER_LABELS = {"heavy_rain": "폭우", "fog": "안개", "slippery": "미끄러운 노면"}
weather_onsets = {}       # (차량, 위험) -> 꺼짐에서 켜짐으로 바뀐 시각
weather_zones = {}        # 구간 ID -> 확정된 기상 구간 (외부 피드로도 내보낸다)

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
    # 기상은 min_reports 대신 WEATHER_RULES의 동시 다발 기준으로 확정한다
    "heavy_rain":      {"min_reports": 5, "radius_m": 5000, "lanes": "all",  "cooldown_s": 300, "inside_m": 500},
    "fog":             {"min_reports": 5, "radius_m": 5000, "lanes": "all",  "cooldown_s": 300, "inside_m": 500},
    "slippery":        {"min_reports": 3, "radius_m": 5000, "lanes": "all",  "cooldown_s": 300, "inside_m": 500},
    "congestion":      {"min_reports": 3, "radius_m": 1000, "lanes": "all",  "cooldown_s": 120, "inside_m": 0},
}

# 사고 정체 (2차 사고 방지 rev2, 옛 04 기능을 통합): 느린 차량이 촘촘히 이어진 무리를 찾고,
# 사고·정차 증거가 있을 때만 알린다. 2차 사고는 사고 지점보다 그 뒤 "정체 꼬리"에서 난다 → 꼬리 위치를 알린다.
# 사고 증거가 없는 단순 정체는 알리지 않는다 (내비 실시간 교통이 이미 하는 일).
JAM_SPEED_KPH = 30       # 이보다 느리면 정체 중인 차량
JAM_GAP_M = 150          # 이 간격 이내로 이어져야 같은 구간
JAM_MIN_VEHICLES = 3     # 구간으로 확정하는 최소 차량 수 (합의)
STOPPED_KPH = 2          # 이보다 느리면 멈춘 차량
ZONE_LABELS = {"accident": "사고 정체", "suspected": "사고·장애물 추정 정체", "jam": "정체"}

# EV 충전소 (rev2): 시연용 고정 목록. 실제로는 환경공단 충전소 정보 API의 위치·충전기 수·정격 출력을 쓴다
CHARGERS = [
    {"id": "PANGYO", "name": "판교역 충전소", "lat": 37.4020, "lon": 127.1080, "stalls": 2, "kw": 100},
    {"id": "SIHEUNG", "name": "시흥동 충전소", "lat": 37.4180, "lon": 127.1000, "stalls": 2, "kw": 100},
]
CHARGER_RADIUS_M = 80    # 이 안에 있으면 충전소에 있는 차량
BATTERY_KWH = 77.4       # 충전 시간 어림용 가정 (차종별 용량은 Tier 2: 차량 정보 연동)
TARGET_SOC = 80
KW_BEFORE_MEASURED = 0.8 # 실측 기록이 없을 때 정격 출력의 80%로 어림한다
# 급속 1회 평균 이용 시간: 서울 공공 급속충전기 실이용 분석 47.2분 (한국ITS학회논문지, 2024). 실측이 쌓이면 대체된다
AVG_SESSION_PRIOR_S = 47.2 * 60
PRIOR_WEIGHT = 3         # 실측 기록 3건이 쌓이면 사전값과 같은 무게
KM_PER_KWH = 5.5         # 도착 시 배터리 어림용 전비
ROAD_FACTOR = 1.3        # 직선거리 → 도로 거리 어림
PUBLIC_STALE_S = 600     # 공공 충전기 상태가 이보다 오래되면 쓰지 않는다
public_status = {}       # 충전소 -> {"busy_since": [ts...], "arrivals_per_hour": float, "updated": ts}
                         #   환경공단 충전기 상태(충전 중·충전 시작 시각)로 타사 차량까지 반영. 시연에서는 POST /public_status로 넣는다
station_log = defaultdict(lambda: {"sessions": [], "ratings": {}})  # 충전소 -> HyperConnect 차량의 충전 기록·별점
charge_track = {}        # 충전 중인 차량 -> {"station", "start", "soc0", "soc", "last"}
just_finished = {}       # 방금 충전을 마친 차량 -> 충전소 (자리를 비키기 전까지 대기 차량으로 세지 않는다)
demo = {"charge_time_scale": 1.0}  # 시연에서만: 충전 시간을 빠르게 흘려 실측 출력을 계산한다 (POST /demo)

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
# 이모트 팩: 같은 세 가지 표현을 팩마다 다른 소리·그림으로 전한다. 받는 쪽은 보낸 차의 팩 소리를 듣는다
# (받아 본 소리가 구매로 이어지는 구조). 유료 팩도 주행 중에는 소리만, 판매는 Pleos 결제 SDK 출시 후.
EMOTE_PACKS = {"basic": "기본", "retro": "레트로 8비트", "orchestra": "오케스트라"}
EMOTE_RANGE_M = 150       # 이 거리 안의 바로 앞차·뒤차에게만 보낸다
EMOTE_COOLDOWN_S = 5      # 한 차량이 이모트를 보낼 수 있는 최소 간격
OTHER_LANE_PENALTY_M = 30 # 같은 거리면 같은 차로 차량을 우선한다

# 기여 통계와 신뢰 점수: 경고 피드백으로 보고 차량의 신뢰도를 갱신하고, 합의 기준에 반영한다
stats = defaultdict(lambda: {"reports": 0, "helped": 0, "emotes": {k: 0 for k in EMOTES},
                             "correct": 0, "incorrect": 0, "charge_reports": 0})
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
    """기상 관측 (rev2): 차량별 최신 관측과 켜진 시각을 기록하고, 동시 다발 기준을 넘으면 기상 구간을 확정·전파한다."""
    now = time.time()
    obs["ts"] = now
    confirmed = []
    with lock:
        prev = weather_obs.get(obs["vehicle"], {})
        weather_obs[obs["vehicle"]] = obs
        for hazard, rule in WEATHER_RULES.items():
            on, was = rule["test"](obs), rule["test"](prev) if prev else False
            if on and not was:
                weather_onsets[(obs["vehicle"], hazard)] = now
                stats[obs["vehicle"]]["reports"] += 1
            elif not on:
                weather_onsets.pop((obs["vehicle"], hazard), None)
            if on and not active_weather_zone(hazard, obs["lat"], obs["lon"]):
                zone = try_confirm_weather(hazard, rule, obs, now)
                if zone:
                    confirmed.append((hazard, propagate_weather(zone, now)))
    return confirmed


def active_weather_zone(hazard, lat, lon):
    return next((z for z in weather_zones.values() if z["type"] == hazard and z["status"] == "active"
                 and distance_m(z["lat"], z["lon"], lat, lon) <= WEATHER_ZONE_M), None)


def weather_share(hazard, lat, lon, now):
    """이 지점 SHARE_RADIUS_M 안 참여 차량 중 지금 이 위험을 관측하는 비율과 그 차량들."""
    test = WEATHER_RULES[hazard]["test"]
    near = [vid for vid, v in live_vehicles(now).items()
            if v.get("role") not in EMERGENCY_ROLES and distance_m(v["lat"], v["lon"], lat, lon) <= SHARE_RADIUS_M]
    seeing = [vid for vid in near if (o := weather_obs.get(vid)) and now - o["ts"] <= WEATHER_WINDOW_S and test(o)]
    return (len(seeing) / len(near) if near else 0.0), seeing


def try_confirm_weather(hazard, rule, obs, now):
    """동시 다발 판단. 호출 시 lock을 잡고 있어야 한다."""
    onset = {vid for (vid, h), ts in weather_onsets.items()
             if h == hazard and now - ts <= BURST_WINDOW_S and (o := weather_obs.get(vid))
             and distance_m(o["lat"], o["lon"], obs["lat"], obs["lon"]) <= WEATHER_ZONE_M}
    if not onset:
        return None
    lat = sum(weather_obs[v]["lat"] for v in onset) / len(onset)
    lon = sum(weather_obs[v]["lon"] for v in onset) / len(onset)
    share, seeing = weather_share(hazard, lat, lon, now)
    weight = sum(trust_weight(v) for v in onset)
    if weight < rule["min_onsets"] - 1e-9 or share < rule["share"]:
        print(f"[weather] {hazard} 동시 다발 대기: {BURST_WINDOW_S}초 안 새로 켠 차량 {len(onset)}대"
              f"(필요 {rule['min_onsets']}), 지역 관측 비율 {share:.0%}(필요 {rule['share']:.0%})", flush=True)
        return None
    zid = f"{hazard}-{int(now * 1000)}"
    zone = {"id": zid, "type": hazard, "label": WEATHER_LABELS[hazard], "status": "active",
            "lat": lat, "lon": lon, "radius_m": WEATHER_ZONE_M, "heading": obs.get("heading"),
            "confirmed_at": now, "updated_at": now, "ended_at": None,
            "onset_vehicles": len(onset), "observing": len(seeing), "share": round(share, 2)}
    weather_zones[zid] = zone
    event_reporters[zid] = set(onset)
    print(f"[weather] {hazard} 확정: {BURST_WINDOW_S}초 안 {len(onset)}대가 새로 켬, 지역 관측 비율 {share:.0%}", flush=True)
    events.append({"type": hazard, "vehicle": obs["vehicle"], "lat": lat, "lon": lon, "lane": None, "ts": now,
                   "reporters": sorted(onset)})
    del events[:-30]
    return zone


def propagate_weather(zone, now, verbose=True):
    event = {"type": zone["type"], "vehicle": None, "lat": zone["lat"], "lon": zone["lon"],
             "heading": zone["heading"], "lane": None, "ts": now, "event_id": zone["id"], "zone_key": zone["id"]}
    notified, _ = propagate(event, set(event_reporters.get(zone["id"], ())), now, verbose=verbose)
    return notified


def weather_loop(now):
    """확정된 기상 구간을 갱신한다: 관측 비율이 떨어지면 해제하고, 새로 다가오는 차량에도 알린다."""
    for z in list(weather_zones.values()):
        if z["status"] != "active":
            if now - z["ended_at"] > 1800:
                del weather_zones[z["id"]]
            continue
        share, seeing = weather_share(z["type"], z["lat"], z["lon"], now)
        z.update(updated_at=now, observing=len(seeing), share=round(share, 2))
        if share < WEATHER_END_SHARE:
            z.update(status="ended", ended_at=now)
            print(f"[weather] {z['type']} 구간 해제 (관측 비율 {share:.0%})", flush=True)
            continue
        notified = propagate_weather(z, now, verbose=False)
        if notified:
            print(f"[weather] {z['label']} 구간 → {notified}", flush=True)


def weather_feed(fmt=None):
    """외부 제공용 기상 위험 피드. 차량 ID·개별 위치는 담지 않고 구간 단위로만 내보낸다."""
    with lock:
        zones = [dict(z) for z in weather_zones.values()]
    now = time.time()
    items = [{"id": z["id"], "hazard": z["type"], "label": z["label"], "status": z["status"],
              "lat": round(z["lat"], 5), "lon": round(z["lon"], 5), "radius_m": z["radius_m"],
              "confirmed_at": round(z["confirmed_at"]), "updated_at": round(z["updated_at"]),
              "ended_at": z["ended_at"] and round(z["ended_at"]),
              "observing_vehicles": z["observing"], "observing_share": z["share"],
              "basis": "vehicle_signals_burst"} for z in zones]
    if fmt == "geojson":
        return {"type": "FeatureCollection", "generated_at": round(now), "features": [
            {"type": "Feature", "id": i["id"], "geometry": {"type": "Point", "coordinates": [i["lon"], i["lat"]]},
             "properties": {k: v for k, v in i.items() if k not in ("lat", "lon", "id")}} for i in items]}
    return {"source": "HyperConnect", "generated_at": round(now), "zones": items}


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
    """이모트 하나를 전달하고 기록한다. 보낸 차의 팩으로 들린다. 호출 시 lock을 잡고 있어야 한다."""
    pack = (vehicles.get(sender) or {}).get("emote_pack") or "basic"
    push(receiver, {"kind": "emote", "type": "emote", "emote": emote, "symbol": EMOTES[emote],
                    "pack": pack, "pack_name": EMOTE_PACKS[pack], "from": from_side, "auto": auto, "ts": now})
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
        for key in [k for k in weather_onsets if k[0] == vid]:
            del weather_onsets[key]
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
            "charge_reports": s["charge_reports"],
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


def charge_seconds(soc, kw):
    """목표 충전량(80%)까지 걸리는 시간."""
    return max(0.0, TARGET_SOC - (soc or 0)) / 100 * BATTERY_KWH / max(kw, 1.0) * 3600


def station_profile(c):
    """충전소 정보: 실측 출력·평균 충전 시간·별점. HyperConnect 차량의 충전 기록이 쌓일수록 정확해진다."""
    log = station_log[c["id"]]
    sessions, ratings = log["sessions"], list(log["ratings"].values())
    n = len(sessions)
    kw = sum(x["kw"] for x in sessions) / n if n else c["kw"] * KW_BEFORE_MEASURED
    avg_s = (AVG_SESSION_PRIOR_S * PRIOR_WEIGHT + sum(x["minutes"] * 60 for x in sessions)) / (PRIOR_WEIGHT + n)
    return {"kw": round(kw), "kw_measured": n > 0, "rated_kw": c["kw"], "sessions": n,
            "avg_session_min": round(avg_s / 60), "avg_session_s": avg_s,
            "rating": round(sum(ratings) / len(ratings), 1) if ratings else None, "rating_n": len(ratings)}


def soc_on_arrival(v, c):
    """도착할 때의 배터리 잔량 어림: 남은 도로 거리만큼 소모."""
    km = distance_m(v["lat"], v["lon"], c["lat"], c["lon"]) / 1000 * ROAD_FACTOR
    return max(0.0, (v.get("soc") or 50) - km / KM_PER_KWH / BATTERY_KWH * 100)


def charger_state(charger, live, now, extra=None):
    """충전소의 지금 상태와, 이곳으로 향하는 차량별 도착 순번·대기·충전 완료 시각을 예측한다 (rev2).

    - HyperConnect 차량: 배터리 잔량으로 남은 충전 시간을 정확히 계산
    - 타사 차량: 공공 충전기 상태(충전 시작 시각)와 평균 충전 시간으로 남은 시간을 어림, 앞으로 올 차량은 시간당 도착 수로 어림
    extra = (vid, eta_s, soc) 이면 그 차량이 이 충전소로 간다고 가정해 함께 계산한다 (대안 충전소 비교용).
    """
    prof = station_profile(charger)
    kw, avg_s = prof["kw"], prof["avg_session_s"]
    at, coming = [], []
    for vid, v in live.items():
        if distance_m(v["lat"], v["lon"], charger["lat"], charger["lon"]) <= CHARGER_RADIUS_M:
            at.append(vid)
        elif charger_of(v.get("dest")) is charger:
            coming.append((v["dest"].get("eta_s") or 0, vid, soc_on_arrival(v, charger)))
    charging = [vid for vid in at if live[vid].get("charging")]
    waiting = [vid for vid in at if not live[vid].get("charging") and just_finished.get(vid) != charger["id"]]
    if extra and extra[0] not in at:
        coming = [c for c in coming if c[1] != extra[0]] + [(extra[1], extra[0], extra[2])]

    pub = public_status.get(charger["id"])
    if pub and now - pub["updated"] > PUBLIC_STALE_S:
        pub = None
    other_busy = pub["busy_since"] if pub else []

    # 지금 충전기를 쓰는 차량과 남은 시간
    occupants = [charge_seconds(live[v].get("soc"), kw) for v in charging]
    occupants += [max(avg_s - (now - since), 300.0) for since in other_busy]
    occupants.sort()
    free = occupants[:charger["stalls"]] + [0.0] * max(0, charger["stalls"] - len(occupants))
    queue = [(0.0, 0, None, d) for d in occupants[charger["stalls"]:]]
    queue += [(0.0, 1, v, charge_seconds(live[v].get("soc"), kw)) for v in waiting]
    queue += [(eta, 2, vid, charge_seconds(soc, kw)) for eta, vid, soc in coming]
    # 앞으로 올 타사 차량 (통계적 어림): 시간당 도착 수만큼 고르게 도착한다고 본다
    rate = pub["arrivals_per_hour"] if pub else 0.0
    horizon = max([eta for eta, *_ in coming], default=0.0)
    expected_other = 0
    if rate > 0:
        t = 1800 / rate
        while t < horizon:
            queue.append((t, 3, None, avg_s))
            expected_other += 1
            t += 3600 / rate
    queue.sort(key=lambda q: (q[0], q[1]))

    forecast, entries = {}, []
    for eta, _, vid, dur in queue:
        free.sort()
        start = max(eta, free[0])
        free[0] = start + dur
        entries.append((eta, start))
        if vid is not None:
            # 내가 도착했을 때 아직 충전을 시작하지 못하고 기다리는 앞 차량 수 (+1 하면 내 순번)
            ahead = sum(1 for e, st in entries[:-1] if e <= eta and st > eta)
            forecast[vid] = {"eta_s": round(eta), "wait_s": round(start - eta), "ahead": ahead,
                             "charge_s": round(dur), "start_ts": now + start, "done_ts": now + start + dur}
    return {"id": charger["id"], "name": charger["name"], "stalls": charger["stalls"], **prof,
            "charging": len(charging) + len(other_busy), "charging_network": len(charging),
            "charging_other": len(other_busy), "waiting": len(waiting), "coming": len(coming),
            "expected_other": expected_other, "public": pub is not None, "forecast": forecast}


def track_charging(now, live):
    """HyperConnect 차량의 충전 기록을 만든다: 충전이 끝나면 실측 출력·시간이 그 충전소 정보가 된다."""
    for vid, v in live.items():
        c = next((c for c in CHARGERS if distance_m(v["lat"], v["lon"], c["lat"], c["lon"]) <= CHARGER_RADIUS_M), None)
        if c is None:
            just_finished.pop(vid, None)
        t = charge_track.get(vid)
        if c and v.get("charging") and v.get("soc") is not None:
            if t is None or t["station"] != c["id"]:
                charge_track[vid] = {"station": c["id"], "start": now, "soc0": v["soc"], "soc": v["soc"], "last": now}
                just_finished.pop(vid, None)
                print(f"[charge] {vid} {c['name']} 충전 시작 (SoC {v['soc']}%)", flush=True)
            else:
                t["soc"], t["last"] = v["soc"], now
        elif t is not None:
            finish_session(vid, now)
    for vid in [v for v in charge_track if v not in live]:
        finish_session(vid, now)


def finish_session(vid, now):
    t = charge_track.pop(vid)
    c = next(c for c in CHARGERS if c["id"] == t["station"])
    minutes = (t["last"] - t["start"]) * demo["charge_time_scale"] / 60
    gain = t["soc"] - t["soc0"]
    if minutes < 1 or gain < 1:
        return
    kw = gain / 100 * BATTERY_KWH / (minutes / 60)
    station_log[c["id"]]["sessions"].append({"vehicle": vid, "minutes": round(minutes, 1), "kw": round(kw, 1),
                                             "soc_from": t["soc0"], "soc_to": t["soc"], "ts": now})
    stats[vid]["charge_reports"] += 1
    just_finished[vid] = c["id"]
    print(f"[charge] {vid} {c['name']} 충전 완료 {minutes:.0f}분, SoC {t['soc0']}→{t['soc']}%, 실측 {kw:.0f}kW → 충전소 정보에 반영",
          flush=True)
    push(vid, {"kind": "charge_done", "type": "charge_done", "station": c["name"], "station_id": c["id"],
               "minutes": round(minutes), "kw": round(kw), "soc_from": t["soc0"], "soc_to": t["soc"], "ts": now})


def handle_rating(body):
    """충전 후 별점 (1~5). 차량당 한 번, 다시 매기면 바뀐다."""
    stars = int(body["stars"])
    if not 1 <= stars <= 5 or not any(c["id"] == body["station"] for c in CHARGERS):
        return {"ok": False}
    with lock:
        station_log[body["station"]]["ratings"][body["vehicle"]] = stars
    print(f"[rating] {body['vehicle']} -> {body['station']} ★{stars}", flush=True)
    return {"ok": True}


def handle_public_status(body):
    """공공 충전기 상태 입력 (환경공단 충전기 상태 API 대체): 충전 중인 충전기의 충전 시작 시각과 시간당 도착 수."""
    now = time.time()
    with lock:
        public_status[body["station"]] = {
            "busy_since": [now - m * 60 for m in body.get("busy_since_min_ago", [])],
            "arrivals_per_hour": float(body.get("arrivals_per_hour", 0)), "updated": now}
    return {"ok": True}


def charger_of(dest):
    if not dest:
        return None
    return next((c for c in CHARGERS
                 if distance_m(dest["lat"], dest["lon"], c["lat"], c["lon"]) <= CHARGER_RADIUS_M), None)


def charger_map(vid):
    """충전소 지도 (EV rev3): 모든 충전소의 위치·별점·혼잡과, 이 차량이 그곳으로 갈 때의 예상 완료 시각."""
    now = time.time()
    with lock:
        live = live_vehicles(now)
        me = live.get(vid) if vid else None
        dest = charger_of(me.get("dest")) if me else None
        out = []
        for c in CHARGERS:
            extra = None
            if me:
                km = distance_m(me["lat"], me["lon"], c["lat"], c["lon"]) / 1000 * ROAD_FACTOR
                eta = (me["dest"].get("eta_s") if dest is c and me.get("dest") else None) or km / 40 * 3600
                extra = (vid, eta, soc_on_arrival(me, c))
            s = charger_state(c, live, now, extra)
            f = s["forecast"].get(vid) if vid else None
            out.append({"id": c["id"], "name": c["name"], "lat": c["lat"], "lon": c["lon"], "stalls": c["stalls"],
                        "rating": s["rating"], "rating_n": s["rating_n"], "kw": s["kw"], "kw_measured": s["kw_measured"],
                        "charging": s["charging"], "waiting": s["waiting"], "coming": s["coming"],
                        "is_dest": dest is c,
                        "forecast": f and {"wait_min": round(f["wait_s"] / 60), "ahead": f["ahead"],
                                           "eta_ts": now + f["eta_s"], "done_ts": f["done_ts"]}})
        return {"me": me and {"lat": me["lat"], "lon": me["lon"], "heading": me["heading"]}, "chargers": out}


current_zones = []     # 대시보드용
current_chargers = []  # 대시보드용
charging_sent = {}     # vehicle_id -> 마지막으로 보낸 충전 예측 (바뀔 때만 다시 보낸다)
emergency_sent = {}    # (긴급차량, 차량) -> 마지막 전송 시각


def zone_notices(now):
    for z in current_zones:
        if z["kind"] == "jam":
            continue  # 사고 증거가 없는 단순 정체는 내비 몫
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
    track_charging(now, live)
    current_chargers = [charger_state(c, live, now) for c in CHARGERS]
    for vid, v in live.items():
        c = charger_of(v.get("dest"))
        if not c:
            charging_sent.pop(vid, None)
            continue
        state = next(s for s in current_chargers if s["id"] == c["id"])
        f = state["forecast"].get(vid)
        if not f:
            continue
        # 대안 충전소: 도착 시간은 거리 비율로 어림하고, 충전 '완료' 시각이 5분 이상 빠를 때만 권한다
        alt = None
        for other in CHARGERS:
            if other["id"] == c["id"]:
                continue
            ratio = (distance_m(v["lat"], v["lon"], other["lat"], other["lon"])
                     / max(1.0, distance_m(v["lat"], v["lon"], c["lat"], c["lon"])))
            alt_f = charger_state(other, live, now, (vid, f["eta_s"] * ratio, soc_on_arrival(v, other)))["forecast"][vid]
            if alt_f["done_ts"] + 300 < f["done_ts"] and (alt is None or alt_f["done_ts"] < alt["done_ts"]):
                alt = {"name": other["name"], "wait_min": round(alt_f["wait_s"] / 60), "done_ts": alt_f["done_ts"]}
        item = {"kind": "charging", "type": "charging", "station": c["name"], "station_id": c["id"],
                "rating": state["rating"], "rating_n": state["rating_n"], "kw": state["kw"],
                "kw_measured": state["kw_measured"], "sessions": state["sessions"],
                "ahead": f["ahead"], "wait_min": round(f["wait_s"] / 60), "charge_min": round(f["charge_s"] / 60),
                "eta_ts": now + f["eta_s"], "start_ts": f["start_ts"], "done_ts": f["done_ts"],
                "stalls": state["stalls"], "charging_network": state["charging_network"],
                "charging_other": state["charging_other"], "expected_other": state["expected_other"],
                # 향하는 EV 수에서 자기 자신은 뺀다
                "coming": max(0, state["coming"] - 1), "alt": alt, "ts": now}
        signature = (item["ahead"], item["wait_min"], item["charge_min"], round(item["done_ts"] / 60),
                     item["rating"], item["kw"], item["coming"], item["charging_network"], item["charging_other"],
                     json.dumps(alt and alt["name"]))
        if charging_sent.get(vid) != signature and push(vid, item):
            charging_sent[vid] = signature
            print(f"[charging] {vid} -> {c['name']} {f['ahead'] + 1}번째, 대기 {item['wait_min']}분, "
                  f"충전 {item['charge_min']}분 ({state['kw']}kW{' 실측' if state['kw_measured'] else ' 어림'}), "
                  f"충전 중 {state['charging_network']}+타사 {state['charging_other']}"
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
            item = {"kind": "emergency", "type": "emergency", "id": em_id, "role": role, "label": label, "symbol": symbol,
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
        # 사고로 정체가 생겼으면 위험 지점은 사고 지점이 아니라 정체 꼬리다 (2차 사고 방지 rev2)
        zone = next((z for z in current_zones if z["kind"] != "jam"
                     and -50 <= relative({"lat": z["tail_lat"], "lon": z["tail_lon"], "heading": z["heading"]},
                                         e["lat"], e["lon"])[0] <= z["length_m"] + 300), None)
        lat, lon = (zone["tail_lat"], zone["tail_lon"]) if zone else (e["lat"], e["lon"])
        for vid, v in live.items():
            # 같은 정체에 사고 보고가 여러 건이어도 꼬리 경고는 한 번
            key = (vid, ("zone", zone["head"]) if zone else e["ts"])
            if vid == e["vehicle"] or key in close_sent or (zone and vid in zone["members"]):
                continue
            along, _ = relative(v, lat, lon)
            if 0 < along <= CLOSE_ALERT_M:
                close_sent.add(key)
                item = {"type": e["type"], "distance_m": round(along),
                        "lane": lane_relation(v.get("lane"), e.get("lane")), "reporters": 1, "ts": now,
                        **({"zone": "tail", "length_m": zone["length_m"]} if zone else {})}
                if push(vid, item):
                    alerts.append({"to": vid, **item})
                    print(f"[close] {vid}: {e['type']} {'정체 꼬리 ' if zone else ''}{round(along)}m", flush=True)


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
            weather_loop(now)
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
                    "weather_zones": list(weather_zones.values()),
                }
            self._json(200, body)
        elif url.path == "/stats":
            if not q.get("vehicle"):
                self._json(400, {"error": "vehicle required"})
            else:
                self._json(200, vehicle_stats(q["vehicle"]))
        elif url.path == "/chargers":
            self._json(200, charger_map(q.get("vehicle")))
        elif url.path == "/feed/weather":
            self._json(200, weather_feed(q.get("format")))
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
            "/rating": ("vehicle", "station", "stars"),
            "/public_status": ("station",),
            "/demo": (),
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
                    "dest": body.get("dest"), "auto_thanks": body.get("auto_thanks", False),
                    "emote_pack": body.get("emote_pack") if body.get("emote_pack") in EMOTE_PACKS else "basic",
                    "ts": time.time(),
                }
            self._json(200, {"ok": True})
        elif path == "/emote":
            self._json(200, handle_emote(body))
        elif path == "/feedback":
            self._json(200, handle_feedback(body))
        elif path == "/rating":
            self._json(200, handle_rating(body))
        elif path == "/public_status":
            self._json(200, handle_public_status(body))
        elif path == "/demo":
            if body.pop("reset", False):  # 시연을 다시 시작할 때: 이벤트·전송 기록을 비운다 (같은 지점 재시연이 '최근 전송함'에 막히지 않게)
                with lock:
                    for table in (events, alerts, reports, sent, close_sent, weather_zones, weather_onsets, weather_obs):
                        table.clear()
                print("[demo] 이벤트·전송 기록 초기화", flush=True)
            demo.update({k: float(v) for k, v in body.items() if k in demo})
            print(f"[demo] {demo}", flush=True)
            self._json(200, demo)
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
