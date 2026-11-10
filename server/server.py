"""OverRoad 중계 서버 (프로토타입).

모든 참여 차량을 고유 ID로 상시 추적하는 차량 레지스트리(로컬 다이내믹 맵의 씨앗)를 유지하고,
차량이 보고한 이벤트를 이벤트 종류별 규칙에 따라 영향을 받는 차량에만 전파한다.
표준 라이브러리만 사용한다.

  python3 server/server.py              # 0.0.0.0:8080
  GET  /stream?vehicle=B                  차량 구독 (Server-Sent Events)
  POST /position                          차량 상태 갱신: 위치·방향·속도·차로 (JSON)
  POST /events                            이벤트 보고 (JSON)
  POST /emote                             앞차·뒤차에게 이모트 보내기 (JSON)
  POST /forget                            데이터 공유 동의 철회 → 이 차량의 위치·관측·통계 삭제
  POST /rating                            충전 후 충전소 별점 (1~5)
  POST /public_status                     공공 충전기 상태 입력 (환경공단 API 대체, 시연용)
  POST /demo                              시연 설정 (charge_time_scale: 충전 시간 배속)
  POST /detections                        참여 차량 센서(카메라·레이더, Tier 2)가 본 주변 물체 → 비연동 차량으로 등록
  GET  /stats?vehicle=B                   내 기여 통계·배지·최근 보고 기록
  GET  /chargers?vehicle=B                충전소 지도: 모든 충전소의 별점·혼잡·이 차량의 예상 완료 시각
  GET  /nearby?vehicle=B                 앱 세부 정보 화면: 주변 기상·사고·긴급차량·연결 차량 수
  GET  /feed/weather[?format=geojson]     외부 제공용 기상 위험 구간 피드 (현대차 내비·외부 서비스가 가져간다)
  GET  /store?vehicle=B                   이모트 스토어: 팩 목록·보유·장착 (POST /store/buy 는 결제 SDK 출시 전까지 안내만)
  GET  /surroundings?vehicle=B           주변 차량 시각화: 같은 방향 앞 200m·뒤 86m 안 차량의 상대 위치 (ID는 익명)
  (서버가 1초마다 사고 정체 구간, 기상 구간, EV 충전 대기 예측, 긴급차량 접근을 계산해 해당 차량에 보낸다)
  GET  /status                            차량 레지스트리·최근 이벤트·경고
  GET  /dashboard                         실시간 도로 상황 대시보드
"""

import json
import math
import threading
import time
import traceback
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
#   min_onsets : BURST_WINDOW_S 안에 새로 켠(꺼짐 → 켜짐) 서로 다른 차량 수
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

# 경고 대상은 진행 방향 거리만이 아니라 옆으로 떨어진 거리도 본다 (나란한 다른 도로의 차를 걸러 낸다).
# 도로가 휘어 있으면 멀수록 옆으로 벌어지므로, 허용 폭 = 기본 폭 + 진행 방향 거리의 25%.
CORRIDOR_BASE_M = 60          # 사고·급제동·긴급차량: 같은 도로 폭 + GPS 오차
CORRIDOR_WEATHER_M = 500      # 기상은 지역 단위라 넓게
CORRIDOR_SLOPE = 0.25


def in_corridor(along, cross, base=CORRIDOR_BASE_M):
    return abs(cross) <= base + CORRIDOR_SLOPE * abs(along)


# 이벤트 종류별 전파 규칙
#   min_reports : 확정에 필요한 서로 다른 차량 수 (프로토타입은 즉시성 이벤트 1대)
#   radius_m    : 경고 대상이 되는 후방 거리
#   lanes       : "same" = 같은 차로만, "all" = 모든 차로
#   cooldown_s  : 같은 지점 같은 이벤트를 한 차량에 다시 보내지 않는 시간
#   inside_m    : 이벤트 지점을 이만큼 지나친 차량도 대상 (기상 구간은 지나쳐도 그 안에 있다)
RULES = {
    # 급제동은 곧 만날 차량에만: 뒤차의 현재 속도로 horizon_s 안에 도달할 거리(최소 min_m)까지. radius_m은 상한
    "hard_brake":      {"min_reports": 1, "radius_m": 1500, "lanes": "same", "cooldown_s": 20,  "inside_m": 0,
                        "horizon_s": 10, "min_m": 100},
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
    {"id": "PANGYO", "name": "판교역 충전소", "operator": "채비", "lat": 37.4020, "lon": 127.1080, "stalls": 2, "kw": 100},
    {"id": "SIHEUNG", "name": "시흥동 충전소", "operator": "에버온", "lat": 37.4180, "lon": 127.1000, "stalls": 2, "kw": 100},
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
station_log = defaultdict(lambda: {"sessions": [], "ratings": {}})  # 충전소 -> OverRoad 차량의 충전 기록·별점
charge_track = {}        # 충전 중인 차량 -> {"station", "start", "soc0", "soc", "last"}
just_finished = {}       # 방금 충전을 마친 차량 -> 충전소 (자리를 비키기 전까지 대기 차량으로 세지 않는다)
demo = {"charge_time_scale": 1.0}  # 시연에서만: 충전 시간을 빠르게 흘려 실측 출력을 계산한다 (POST /demo)

# 긴급차량 길 터주기
EMERGENCY_ROLES = {"ambulance": ("구급차", "🚑"), "fire": ("소방차", "🚒"), "police": ("경찰차", "🚓")}
EMERGENCY_RANGE_M = 1000  # 긴급차량 앞쪽 이 거리 안의 차량에 알린다
EMERGENCY_UPDATE_S = 2    # 접근 거리 갱신 주기
NEARBY_M = 3000           # 앱 세부 정보 화면에 보여 줄 범위

lock = threading.Lock()
subscribers = {}   # vehicle_id -> {"queue": list, "cond": Condition}
vehicles = {}      # vehicle_id -> {"lat", "lon", "heading", "speed_kph", "lane", "ts"}
reports = []       # 즉시성 이벤트 보고
weather_obs = {}   # vehicle_id -> 최근 기상 관측 {"lat", "lon", "rain", "fog", "slippery", "ts"}
events = []        # 확정된 이벤트 (대시보드용)
alerts = []        # 보낸 경고 (대시보드용)
sent = {}          # (vehicle_id, type, zone_key) -> 마지막 전송 시각
emotes = []        # 보낸 이모트 (대시보드용)
emote_times = {}   # vehicle_id -> 최근 이모트 전송 시각들 (연타 제한)
pair_sent = {}     # (보낸 차, 받는 차) -> 마지막으로 보낸 시각 (같은 차 제한)
pair_event = {}    # frozenset({차, 차}) -> 둘 사이에 마지막으로 새 일이 생긴 시각 (양보·차로 변경 알림 → 같은 차 제한 해제)

# 이모트: 정해진 긍정·중립 표현만 허용한다 (자유 입력은 시비·보복운전에 악용될 수 있다)
EMOTES = {"thanks": "👍", "sorry": "🙏", "hello": "👋"}
# 이모트 팩: 같은 세 가지 표현을 팩마다 다른 소리·그림으로 전한다. 받는 쪽은 보낸 차의 팩 소리를 듣는다
# (받아 본 소리가 구매로 이어지는 구조). 유료 팩도 주행 중에는 소리만, 판매는 Pleos 결제 SDK 출시 후.
# 이모트 스토어 (2026-11-08): 기본 · 현대차 오리지널(유료) · 특별 지급(판매 안 함, 조건 충족 시) · 창작마당(심사 통과작, 유료)
# 유료 구매는 Pleos 결제 SDK 출시 후. 지금은 보유(무료·지급)한 팩만 장착할 수 있고, 모든 팩은 미리 들어 볼 수 있다.
STORE = [
    {"id": "basic", "name": "기본", "section": "basic", "maker": "OverRoad", "price": 0},
    {"id": "retro", "name": "레트로 8비트", "section": "original", "maker": "현대차", "price": 2200},
    {"id": "orchestra", "name": "오케스트라", "section": "original", "maker": "현대차", "price": 2200},
    {"id": "electric", "name": "아이오닉 전자음", "section": "original", "maker": "현대차 · 차종 사운드", "price": 3300},
    {"id": "launch", "name": "출고 기념", "section": "special", "maker": "현대차", "unlock": "신차 출고 30일 이내 (시연: 모든 차량)"},
    {"id": "best", "name": "베스트 드라이버", "section": "special", "maker": "현대차", "unlock": "베스트 드라이버 배지를 받으면"},
    {"id": "emergency", "name": "긴급차량 감사", "section": "special", "maker": "현대차",
     "unlock": "구급·소방·경찰차 전용 · 길을 열어 준 차에게 지나간 뒤 자동으로 전해져요"},
    {"id": "gugak", "name": "국악 한 소절", "section": "creator", "maker": "크리에이터 한소리", "price": 1100},
    {"id": "marimba", "name": "마림바", "section": "creator", "maker": "크리에이터 우드톤", "price": 1100},
]
EMOTE_PACKS = {p["id"]: p["name"] for p in STORE}
purchases = defaultdict(set)   # 차량 -> 산 팩 (결제 SDK 연동 후 채워진다)
EMOTE_RANGE_M = 150       # 이 거리 안의 바로 앞차·뒤차에게만 보낸다
EMOTE_BURST_N, EMOTE_BURST_S = 2, 5   # 연타 제한: 5초 안에 최대 2번 (받는 차와 상관없이)
EMOTE_PAIR_S = 180        # 같은 차에게는 3분에 한 번 — 그 사이 둘 사이에 새 일(양보·차로 변경 알림)이 생기면 다시 열린다
OTHER_LANE_PENALTY_M = 30 # 같은 거리면 같은 차로 차량을 우선한다

# 기여 통계 (2026-11-08): 신뢰 점수는 쓰지 않는다. 안전 경고는 보수적으로 일단 보내고,
# 문제는 운영 피드백과 업데이트로 고친다. 조작된 보고는 차량 인증·보고 빈도 제한 같은 보안으로 막는다(출시 과제).
stats = defaultdict(lambda: {"reports": 0, "helped": 0, "emotes": {k: 0 for k in EMOTES}, "charge_reports": 0})
report_log = defaultdict(list)  # 차량 -> 최근 보고와 전달 대수 (내 기여 화면)
REPORT_LOG_MAX = 10
MIN_RANKED = 5            # 이 대수 이상 참여해야 상위 %를 보여준다

# 차로 변경 의도 공유
MERGE_BEHIND_M = 60       # 진입하려는 차로에서 내 뒤 이 거리 안의 차량만 대상
MERGE_ALONGSIDE_M = 5     # 바로 옆(사각지대)에 있는 차량도 대상
MERGE_WINDOW_S = 10       # 깜빡이 후 이 시간 안에 차로가 바뀌면 진입 성공
LANE_UNKNOWN_SIDE_M = (1.0, 7.0)  # 차로를 모를 때 '옆 차로'로 보는 좌우 거리 범위
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


def absolute(observer, along, cross):
    """관찰 차량 기준 (전방 m, 오른쪽 m) 지점의 위도·경도 — relative()의 역."""
    h = math.radians(observer["heading"])
    north = along * math.cos(h) - cross * math.sin(h)
    east = along * math.sin(h) + cross * math.cos(h)
    return (observer["lat"] + north / 111320,
            observer["lon"] + east / (111320 * math.cos(math.radians(observer["lat"]))))


def same_direction(a, b):
    return abs((a - b + 180) % 360 - 180) <= SAME_DIRECTION_DEG


def lane_relation(a, b):
    if a is None or b is None:
        return "unknown"
    return "same" if a == b else ("adjacent" if abs(a - b) == 1 else "other")


def live_vehicles(now):
    return {k: v for k, v in vehicles.items() if now - v["ts"] <= VEHICLE_TTL_S}


def consensus_met(reporters, min_reports):
    """서로 다른 보고 차량이 필요한 대수 이상이면 확정."""
    return len(reporters) >= min_reports


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
        if not in_corridor(along, cross, CORRIDOR_WEATHER_M if event["type"] in WEATHER_RULES else CORRIDOR_BASE_M):
            decisions[vid] = f"다른 도로(옆으로 {round(abs(cross))}m)"
            continue
        if "horizon_s" in rule:
            reach = max(rule["min_m"], (v.get("speed_kph") or 0) / 3.6 * rule["horizon_s"])
            if along > reach:
                decisions[vid] = f"{rule['horizon_s']}초 거리 밖 {round(along)}m (도달 가능 {round(reach)}m)"
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
        report_log[r].insert(0, {"ts": now, "type": event["type"], "notified": len(notified)})
        del report_log[r][REPORT_LOG_MAX:]
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
            print(f"  합의 부족 (보고 {len(reporters)}대) → 확정 대기", flush=True)
            return False, []
        event["event_id"] = f"{event['type']}-{event['vehicle']}-{int(now * 1000)}"
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
    if len(onset) < rule["min_onsets"] - 1e-9 or share < rule["share"]:
        print(f"[weather] {hazard} 동시 다발 대기: {BURST_WINDOW_S}초 안 새로 켠 차량 {len(onset)}대"
              f"(필요 {rule['min_onsets']}), 지역 관측 비율 {share:.0%}(필요 {rule['share']:.0%})", flush=True)
        return None
    zid = f"{hazard}-{int(now * 1000)}"
    zone = {"id": zid, "type": hazard, "label": WEATHER_LABELS[hazard], "status": "active",
            "lat": lat, "lon": lon, "radius_m": WEATHER_ZONE_M, "heading": obs.get("heading"),
            "confirmed_at": now, "updated_at": now, "ended_at": None,
            "onset_vehicles": len(onset), "observing": len(seeing), "share": round(share, 2),
            "reporters": sorted(onset)}  # 경고에서 뺄 보고 차량 (외부 피드에는 내보내지 않는다)
    weather_zones[zid] = zone
    print(f"[weather] {hazard} 확정: {BURST_WINDOW_S}초 안 {len(onset)}대가 새로 켬, 지역 관측 비율 {share:.0%}", flush=True)
    events.append({"type": hazard, "vehicle": obs["vehicle"], "lat": lat, "lon": lon, "lane": None, "ts": now,
                   "reporters": sorted(onset)})
    del events[:-30]
    return zone


def propagate_weather(zone, now, verbose=True):
    event = {"type": zone["type"], "vehicle": None, "lat": zone["lat"], "lon": zone["lon"],
             "heading": zone["heading"], "lane": None, "ts": now, "event_id": zone["id"], "zone_key": zone["id"]}
    notified, _ = propagate(event, set(zone.get("reporters", ())), now, verbose=verbose)
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
    return {"source": "OverRoad", "generated_at": round(now), "zones": items}


# ---------- HTTP ----------

def emote_target(vid, me, live, want_ahead):
    """이모트를 받을 바로 앞차·뒤차: 같은 방향, 150m 안, 같은 차로(모르면 같은 차로로 본다)를 먼저, 바로 옆 차로까지만.
    기본 화면의 점선(`surroundings`)도 이 함수를 써서, 화면에 이어진 차가 곧 실제로 받는 차다. 호출 시 lock을 잡고 있어야 한다."""
    best, best_score = None, None
    for oid, v in live.items():
        if oid == vid or not same_direction(me["heading"], v["heading"]):
            continue
        along, _ = relative(me, v["lat"], v["lon"])
        if (along > 0) != want_ahead or abs(along) > EMOTE_RANGE_M:
            continue
        rel = lane_relation(me.get("lane"), v.get("lane"))
        if rel == "other":
            continue  # 두 차로 이상 떨어진 차는 '앞차·뒤차'가 아니다 (2026-11-09)
        score = abs(along) + (0 if rel in ("same", "unknown") else OTHER_LANE_PENALTY_M)
        if best_score is None or score < best_score:
            best, best_score = oid, score
    return best


def handle_emote(body):
    """보낸 차량 기준 바로 앞차 또는 뒤차를 찾아 이모트를 전달한다. 받는 쪽에는 상대 ID를 알리지 않는다."""
    now = time.time()
    with lock:
        me = live_vehicles(now).get(body["vehicle"])
        if me is None:
            return {"delivered": False, "reason": "위치 정보 없음"}
        if body["emote"] not in EMOTES or body["target"] not in ("ahead", "behind"):
            return {"delivered": False, "reason": "허용되지 않은 이모트"}
        if me.get("emotes_off"):
            # 받지 않는 차는 보낼 수도 없다 (한쪽으로만 보내는 일 방지)
            return {"delivered": False, "reason": "'항상 감정표현 받지 않기'를 켜 두면 보낼 수도 없어요", "limit": "self_off"}
        sent = [t for t in emote_times.get(body["vehicle"], []) if now - t < EMOTE_BURST_S]
        emote_times[body["vehicle"]] = sent
        if len(sent) >= EMOTE_BURST_N:
            return {"delivered": False, "reason": f"{EMOTE_BURST_S}초에 {EMOTE_BURST_N}번까지 보낼 수 있어요", "limit": "burst"}

        want_ahead = body["target"] == "ahead"
        best = emote_target(body["vehicle"], me, live_vehicles(now), want_ahead)
        if best is None:
            return {"delivered": False, "reason": f"{EMOTE_RANGE_M}m 안에 {'앞' if want_ahead else '뒤'}차가 없음"}

        side = "앞" if want_ahead else "뒤"
        if vehicles[best].get("emotes_off"):
            return {"delivered": False, "reason": f"{side}차는 이모트를 받지 않아요", "limit": "off"}
        wait = pair_block_s(body["vehicle"], best, now)
        if wait:
            return {"delivered": False, "reason": f"같은 {side}차에는 {EMOTE_PAIR_S // 60}분에 한 번 · {wait // 60}분 {wait % 60}초 뒤에",
                    "limit": "pair", "wait_s": wait}
        sent.append(now)
        pair_sent[(body["vehicle"], best)] = now
        # 받는 차 입장에서 보낸 차의 위치: 내가 뒤차에게 보냈다면 받는 쪽에서 나는 앞차다
        deliver_emote(body["vehicle"], best, body["emote"], "ahead" if not want_ahead else "behind", now)
        return {"delivered": True, "target": body["target"]}


def owned_packs(vid):
    """이 차량이 장착할 수 있는 팩: 기본 + 특별 지급(조건 충족) + 산 팩. 호출 시 lock을 잡고 있어야 한다."""
    v = vehicles.get(vid) or {}
    owned = {"basic", "launch"} | purchases[vid]
    if "베스트 드라이버" in badges_of(vid)[0]:
        owned.add("best")
    if v.get("role") in EMERGENCY_ROLES:
        owned.add("emergency")
    return owned


def pair_block_s(sender, receiver, now):
    """같은 차 제한: 이 차에게 다시 보낼 수 있을 때까지 남은 초 (0이면 보낼 수 있음). 호출 시 lock을 잡고 있어야 한다."""
    last = pair_sent.get((sender, receiver))
    if last is None or now - last >= EMOTE_PAIR_S:
        return 0
    if pair_event.get(frozenset((sender, receiver)), 0) > last:
        return 0  # 보낸 뒤 둘 사이에 새 일이 생겼다
    return int(EMOTE_PAIR_S - (now - last)) + 1


def mark_pair_event(a, b, now):
    """두 차 사이에 새 일(양보 요청·진입 성공)이 생겼다 → 같은 차 제한을 푼다."""
    pair_event[frozenset((a, b))] = now


def deliver_emote(sender, receiver, emote, from_side, now, auto=False, pack=None):
    """이모트 하나를 전달하고 기록한다. 보낸 차의 팩으로 들린다(갖고 있지 않은 팩이면 기본). 호출 시 lock을 잡고 있어야 한다.
    받는 차가 '항상 감정표현 받지 않기'를 켰으면 자동 감사 인사·긴급차량 감사도 보내지 않는다."""
    if (vehicles.get(receiver) or {}).get("emotes_off"):
        print(f"[emote] {sender} -> {receiver}: 받지 않기 설정이라 보내지 않음", flush=True)
        return False
    pack = pack or (vehicles.get(sender) or {}).get("emote_pack") or "basic"
    if pack not in owned_packs(sender):
        pack = "basic"
    push(receiver, {"kind": "emote", "type": "emote", "emote": emote, "symbol": EMOTES[emote],
                    "pack": pack, "pack_name": EMOTE_PACKS[pack], "from": from_side, "auto": auto, "ts": now})
    stats[receiver]["emotes"][emote] += 1
    emotes.append({"from": sender, "to": receiver, "emote": emote, "auto": auto, "ts": now})
    del emotes[:-20]
    print(f"[emote] {sender} -> {receiver}: {EMOTES[emote]} {emote}{' (자동)' if auto else ''}", flush=True)
    return True


def forget(vid):
    """데이터 공유 동의 철회: 이 차량의 위치·관측·통계를 지운다. (보고 이력은 익명 이벤트로만 남는다)"""
    with lock:
        report_log.pop(vid, None)
        removed = [name for name, table in (("위치", vehicles), ("기상 관측", weather_obs), ("통계", stats),
                                            ("차로 변경", lane_intents)) if table.pop(vid, None) is not None]
        for key in [k for k in weather_onsets if k[0] == vid]:
            del weather_onsets[key]
        emote_times.pop(vid, None)
        for key in [k for k in pair_sent if vid in k]:
            del pair_sent[key]
        for key in [k for k in pair_event if vid in k]:
            del pair_event[key]
    print(f"[forget] {vid} 데이터 삭제: {', '.join(removed) or '없음'}", flush=True)
    return {"ok": True, "removed": removed}


def score(vid):
    """베스트 드라이버 점수: 도운 차량 수와 받은 감사를 함께 본다 (👍 몰아주기만으로는 오르기 어렵게)."""
    s = stats[vid]
    return s["helped"] + 2 * s["emotes"]["thanks"] + s["emotes"]["hello"]


def badges_of(vid):
    """배지와 순위. 호출 시 lock을 잡고 있어야 한다."""
    if vid not in stats:
        return [], None
    s = stats[vid]
    active = [v for v in stats if score(v) > 0]
    mine = score(vid)
    better = sum(1 for v in active if score(v) > mine)
    # 참여 차량이 적으면 "상위 100%"처럼 어색한 값이 나오므로 순위 %는 숨긴다
    top_pct = (round(100 * (better + 1) / len(active))
               if len(active) >= MIN_RANKED and mine > 0 else None)
    badges = []
    if mine > 0 and (better == 0 or (top_pct or 100) <= 10):
        badges.append("베스트 드라이버")
    if s["helped"] >= 10:
        badges.append("정보 기여왕")
    if s["emotes"]["thanks"] >= 3:
        badges.append("매너 드라이버")
    return badges, top_pct


def vehicle_stats(vid):
    """내 기여 화면에 보여줄 통계와 배지. 순위표는 공개하지 않고 내 위치(상위 %)만 알려준다."""
    with lock:
        s = stats[vid]
        badges, top_pct = badges_of(vid)
        return {
            "vehicle": vid, "reports": s["reports"], "helped": s["helped"], "emotes": dict(s["emotes"]),
            "top_pct": top_pct, "badges": badges, "history": report_log.get(vid, [])[:REPORT_LOG_MAX],
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
    """충전소 정보: 실측 출력·평균 충전 시간·별점. OverRoad 차량의 충전 기록이 쌓일수록 정확해진다."""
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

    - OverRoad 차량: 배터리 잔량으로 남은 충전 시간을 정확히 계산
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
    """OverRoad 차량의 충전 기록을 만든다: 충전이 끝나면 실측 출력·시간이 그 충전소 정보가 된다."""
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


def nearby_info(vid):
    """앱 세부 정보 화면: 이 차량 주변의 기상·사고·긴급차량을 운전자가 읽을 문구로. 다른 차량 ID는 내보내지 않는다."""
    now = time.time()
    with lock:
        live = live_vehicles(now)
        me = live.get(vid)
        if not me:
            return {"weather": [], "accidents": [], "emergency": [], "connected": 0}

        def where(lat, lon):
            ahead = relative(me, lat, lon)[0]
            return f"전방 {round(ahead)}m" if ahead >= 0 else f"뒤 {round(-ahead)}m"
        weather = [f"{where(z['lat'], z['lon'])} {z['label']} · 지역 차량 {round(z['share'] * 100)}% 관측"
                   for z in weather_zones.values() if z["status"] == "active"
                   and distance_m(me["lat"], me["lon"], z["lat"], z["lon"]) <= NEARBY_M]
        accidents = [f"{where(z['tail_lat'], z['tail_lon'])}부터 {z['label']} · 길이 {z['length_m']}m · 평균 {z['avg_kph']}km/h"
                     for z in current_zones if z["kind"] != "jam"
                     and distance_m(me["lat"], me["lon"], z["tail_lat"], z["tail_lon"]) <= NEARBY_M]
        emergency = [f"{where(v['lat'], v['lon'])} {EMERGENCY_ROLES[v['role']][1]} {EMERGENCY_ROLES[v['role']][0]}"
                     for o, v in live.items() if o != vid and v.get("role") in EMERGENCY_ROLES
                     and distance_m(me["lat"], me["lon"], v["lat"], v["lon"]) <= NEARBY_M]
        connected = sum(1 for o, v in live.items() if o != vid and distance_m(me["lat"], me["lon"], v["lat"], v["lon"]) <= 1000)
    return {"weather": weather, "accidents": accidents, "emergency": emergency, "connected": connected}


SURROUND_AHEAD_M = 200   # 화면 7 : 3 구도 → 앞 200m, 뒤 86m
SURROUND_BEHIND_M = 86
SURROUND_SIDE_M = 20
BRAKE_GLOW_S = 4         # 급제동 보고 뒤 이 시간 동안 브레이크등을 켜서 그린다
DECEL_SHOW_MPS2 = 1.5    # 이보다 빠르게 줄면 감속 중 (브레이크등)


# ---------- 비연동 차량 (참여 차 센서가 본 물체, Tier 2) ----------
OBS_MATCH_M = 4.0      # 이 거리 안에 참여 차량이 있으면 같은 차 (센서 물체 = 이미 아는 OverRoad 차)
OBS_TTL_S = 2.0        # 이 시간 동안 아무 센서도 다시 보지 못하면 지운다
observed = {}          # "S1" -> {"lat", "lon", "heading", "speed_kph", "ts", "seen_by": set, "decel_until"}
observed_seq = [0]


SENSOR_LAYERS = ("camera", "radar", "lidar", "uwb")   # GPS 비콘(Tier 1) 위에 더해지는 식별 층위 (Tier 2~3)
sensor_layers = {}     # 센서 보고 차량 -> (센서 종류 집합, 마지막 보고 시각)


def handle_detections(body):
    """참여 차량이 카메라·레이더로 본 물체 목록을 받는다 (Tier 2 미리보기: 지금 Pleos SDK에는 센서 물체 API가 없다).
    물체마다 (1) 참여 차량과 겹치면 버리고(이미 위치를 직접 보내는 차) (2) 이미 등록한 비연동 차량과 겹치면 갱신,
    (3) 아니면 새 비연동 차량으로 등록한다. 비연동 차량은 ID가 없으므로 서버가 붙인 임시 번호로만 이어 그린다."""
    now = time.time()
    with lock:
        live = live_vehicles(now)
        me = live.get(body["vehicle"])
        if not me:
            return {"ok": False}
        # 이 차가 어떤 센서로 봤는지 (기본 화면의 식별 층위 표시에 쓴다). 안 보내면 카메라·레이더로 본다
        kinds = {k for k in body.get("sensors", ["camera", "radar"]) if k in SENSOR_LAYERS}
        sensor_layers[body["vehicle"]] = (kinds, now)
        matched, new, updated = 0, 0, 0
        for o in body.get("objects", []):
            lat, lon = absolute(me, o["along"], o["cross"])
            if any(distance_m(lat, lon, v["lat"], v["lon"]) <= OBS_MATCH_M for vid, v in live.items() if vid != body["vehicle"]):
                matched += 1
                continue
            best, best_d = None, OBS_MATCH_M
            for oid, ob in observed.items():
                # 마지막으로 본 뒤 달린 거리만큼 앞으로 옮겨서 비교
                ahead = (ob.get("speed_kph") or 0) / 3.6 * (now - ob["ts"])
                plat, plon = absolute(ob, ahead, 0)
                d = distance_m(lat, lon, plat, plon)
                if d < best_d:
                    best, best_d = oid, d
            speed = o.get("speed_kph")
            if best is None:
                observed_seq[0] += 1
                best = f"S{observed_seq[0]}"
                observed[best] = {"seen_by": set(), "decel_until": 0, "first": now}
                new += 1
            else:
                updated += 1
            ob = observed[best]
            prev_kph, prev_ts = ob.get("speed_kph"), ob.get("ts")
            if prev_kph is not None and speed is not None and prev_ts and 0 < now - prev_ts < 3 \
                    and (prev_kph - speed) / 3.6 / (now - prev_ts) >= DECEL_SHOW_MPS2:
                ob["decel_until"] = now + 1.5
            ob.update({"lat": lat, "lon": lon, "heading": me["heading"], "speed_kph": speed, "ts": now})
            ob["seen_by"].add(body["vehicle"])
        for oid in [k for k, ob in observed.items() if now - ob["ts"] > OBS_TTL_S]:
            del observed[oid]
    return {"ok": True, "matched_members": matched, "new": new, "updated": updated}


def surroundings(vid):
    """주변 차량 시각화 (Tier 1 = GPS 비콘): 같은 방향으로 가는 차량의 상대 위치(앞뒤·좌우 m)와 상태.
    다른 차량 ID는 내보내지 않고, 화면에서 움직임을 이어 그리기 위한 익명 번호만 준다."""
    now = time.time()
    with lock:
        live = live_vehicles(now)
        me = live.get(vid)
        if not me:
            return {"me": None, "vehicles": [], "connected": 0}
        braking = {r["vehicle"] for r in reports if r["type"] == "hard_brake" and now - r["ts"] <= BRAKE_GLOW_S}
        # 이모트를 누르면 실제로 받을 차 (화면에서 점선으로 잇는다)
        targets = {emote_target(vid, me, live, True): "ahead", emote_target(vid, me, live, False): "behind"}
        targets.pop(None, None)
        out = []
        for oid, v in live.items():
            if oid == vid or not same_direction(me["heading"], v["heading"]):
                continue
            along, cross = relative(me, v["lat"], v["lon"])
            if not -SURROUND_BEHIND_M <= along <= SURROUND_AHEAD_M or abs(cross) > SURROUND_SIDE_M:
                continue
            out.append({"key": abs(hash((vid, oid))) % 100000, "along": round(along, 1), "cross": round(cross, 1),
                        "lane": v.get("lane"), "speed_kph": v.get("speed_kph") or 0, "turn": v.get("turn"),
                        "role": v.get("role"), "braking": oid in braking, "decel": now < v.get("decel_until", 0),
                        "age_s": round(now - v["ts"], 1), "member": True, "emote_target": targets.get(oid),
                        # 대상 차에 지금 보낼 수 없으면: "self"(내가 받지 않기) · "off"(상대가 받지 않기) · 남은 초 (같은 차 3분 제한)
                        "emote_block": ("self" if me.get("emotes_off") else "off" if v.get("emotes_off")
                                        else pair_block_s(vid, oid, now) or None)
                        if oid in targets else None})
        # 비연동 차량: 참여 차 센서가 본 물체 (연동 차량과 겹치면 이미 위에서 그렸다)
        n_obs = 0
        for oid, ob in observed.items():
            if now - ob["ts"] > OBS_TTL_S or not same_direction(me["heading"], ob["heading"]):
                continue
            if distance_m(me["lat"], me["lon"], ob["lat"], ob["lon"]) <= OBS_MATCH_M:
                continue  # 내 차를 본 것
            if any(distance_m(ob["lat"], ob["lon"], v["lat"], v["lon"]) <= OBS_MATCH_M for v in live.values()):
                continue
            along, cross = relative(me, ob["lat"], ob["lon"])
            along += (ob.get("speed_kph") or 0) / 3.6 * (now - ob["ts"])
            if not -SURROUND_BEHIND_M <= along <= SURROUND_AHEAD_M or abs(cross) > SURROUND_SIDE_M:
                continue
            n_obs += 1
            out.append({"key": abs(hash((vid, oid))) % 100000, "along": round(along, 1), "cross": round(cross, 1),
                        "lane": None, "speed_kph": ob.get("speed_kph") or 0, "turn": None, "role": None,
                        "braking": False, "decel": now < ob.get("decel_until", 0), "member": False,
                        "seen_by": len(ob["seen_by"])})
        connected = sum(1 for o, v in live.items() if o != vid and distance_m(me["lat"], me["lon"], v["lat"], v["lon"]) <= 1000)
        # 화면 범위 밖이지만 연결된 차(1km 안): 위치만 준다 → 화면에서 그 방향으로 흐려지는 점선을 그린다 ("멀지만 연결돼 있다")
        far = []
        for oid, v in live.items():
            if oid == vid or distance_m(me["lat"], me["lon"], v["lat"], v["lon"]) > 1000:
                continue
            f_along, f_cross = relative(me, v["lat"], v["lon"])
            inside = same_direction(me["heading"], v["heading"]) and -SURROUND_BEHIND_M <= f_along <= SURROUND_AHEAD_M and abs(f_cross) <= SURROUND_SIDE_M
            if not inside:
                far.append({"along": round(f_along), "cross": round(f_cross)})
        far = sorted(far, key=lambda p: abs(p["along"]) + abs(p["cross"]))[:30]
    return {"me": {"lane": me.get("lane"), "speed_kph": me.get("speed_kph") or 0, "turn": me.get("turn"),
                   "decel": now < me.get("decel_until", 0)},
            "vehicles": out, "connected": connected, "ahead_m": SURROUND_AHEAD_M, "behind_m": SURROUND_BEHIND_M,
            "source": "gps_beacon", "error_m": 5, "observed": n_obs, "far": far,
            # 지금 내 주변 인식에 쓰이고 있는 식별 층위: GPS 비콘 + 주변(나 포함) 참여 차가 최근 보고한 센서 종류
            "sources": ["gps_beacon"] + sorted({k for sv, (kinds, ts) in sensor_layers.items()
                                               if now - ts <= OBS_TTL_S and sv in live
                                               and distance_m(me["lat"], me["lon"], live[sv]["lat"], live[sv]["lon"]) <= SURROUND_AHEAD_M
                                               for k in kinds})}


def store_info(vid):
    """이모트 스토어: 팩 목록과 이 차량의 보유·장착 상태."""
    with lock:
        owned = owned_packs(vid)
        equipped = (vehicles.get(vid) or {}).get("emote_pack") or "basic"
    return {"equipped": equipped if equipped in owned else "basic", "packs": [
        {**p, "owned": p["id"] in owned} for p in STORE]}


def store_buy(body):
    """구매: Pleos 결제 SDK가 나오면 여기서 결제를 확인하고 purchases에 넣는다."""
    return {"ok": False, "reason": "Pleos 결제 SDK 출시 후 구매할 수 있어요 (지금은 미리 듣기만)"}


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
            # 정체는 머리·꼬리 차량이 계속 바뀌므로 종류로만 재전송을 막는다 (종류가 바뀌면 다시 알림)
            "zone_key": ("zone", z["kind"]),
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
            alt_f = charger_state(other, live, now, (vid, f["eta_s"] * ratio, soc_on_arrival(v, other)))["forecast"].get(vid)
            if alt_f is None:
                continue
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
                "coming": max(0, state["coming"] - 1), "alt": alt, "ts": now,
                # 여정 화면용: 운영사, 지금 빈 충전기, 도착 시 배터리, 예측 근거(자사 정확 / 타사 추정)
                "operator": c.get("operator"), "free": max(0, state["stalls"] - state["charging"]),
                "soc_arrival": round(soc_on_arrival(v, c)) if v.get("soc") is not None else None,
                "known": state["charging_network"] + max(0, state["coming"] - 1),
                "estimated": state["charging_other"] + state["expected_other"]}
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
            em_along, em_cross = relative(v, em["lat"], em["lon"])
            behind = -em_along  # 긴급차량이 내 뒤 몇 m에 있는가
            if not in_corridor(em_along, em_cross) and (em_id, vid) not in emergency_sent:
                continue        # 나란한 다른 도로의 긴급차량은 알리지 않는다
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
                # 특별 지급 '긴급차량 감사': 지나간 긴급차량이 길을 열어 준 차에게 자동으로 고마움을 전한다
                if role in EMERGENCY_ROLES and not v.get("role"):
                    deliver_emote(em_id, vid, "thanks", "ahead", now, auto=True, pack="emergency")


CLOSE_ALERT_M = 300  # 사고·정차 지점에 이만큼 가까워지면 한 번 더 (앱에서는 빨간 경고로) 알린다
close_sent = set()   # (차량, 이벤트 시각) — 가까워짐 알림을 이미 보낸 조합


zone_close_sent = {}       # vehicle_id -> 마지막 정체 꼬리 경고 시각
ZONE_CLOSE_COOLDOWN_S = 120


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
            # 같은 정체에 사고 보고가 여러 건이거나 정체 머리 차량이 바뀌어도 꼬리 경고는 한 번
            key = (vid, e["ts"])
            if vid == e["vehicle"] or key in close_sent or (zone and vid in zone["members"]):
                continue
            if zone and now - zone_close_sent.get(vid, 0) < ZONE_CLOSE_COOLDOWN_S:
                continue
            along, cross = relative(v, lat, lon)
            if e.get("heading") is not None and not same_direction(v["heading"], e["heading"]):
                continue        # 반대 방향 차는 그 지점을 만나지 않는다
            if 0 < along <= CLOSE_ALERT_M and in_corridor(along, cross):
                close_sent.add(key)
                if zone:
                    zone_close_sent[vid] = now
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
        if intent["target"] is not None and me.get("lane") == intent["target"]:  # 진입 성공
            del lane_intents[vid]
            merge_done(vid, me, live, now)
            continue
        if now - intent["since"] > MERGE_WINDOW_S:
            continue
        best, best_gap = None, None
        for oid, o in live.items():
            if oid == vid or not same_direction(me["heading"], o["heading"]):
                continue
            ahead_of_me, side_m = relative(me, o["lat"], o["lon"])  # 음수 = 내 뒤, 양수 = 오른쪽
            if intent["target"] is not None:
                if o.get("lane") != intent["target"]:
                    continue
            # 차로를 모를 때: 깜빡이 쪽으로 옆 1~7m(대략 옆 차로, GPS 오차 감안해 넓게)에 있는 차
            elif not LANE_UNKNOWN_SIDE_M[0] <= (side_m if intent["dir"] == "RIGHT" else -side_m) <= LANE_UNKNOWN_SIDE_M[1]:
                continue
            if not -MERGE_BEHIND_M <= ahead_of_me <= MERGE_ALONGSIDE_M:
                continue
            closing = (o.get("speed_kph") or 0) > (me.get("speed_kph") or 0) or abs(ahead_of_me) <= MERGE_ALONGSIDE_M
            if closing and (best_gap is None or abs(ahead_of_me) < best_gap):
                best, best_gap = oid, abs(ahead_of_me)
        if best and best not in intent["notified"]:
            intent["notified"].add(best)
            mark_pair_event(vid, best, now)
            gap = round(best_gap)
            push(vid, {"kind": "lane", "type": "lane", "role": "merging", "distance_m": gap,
                       "side": intent["dir"], "ts": now})
            push(best, {"kind": "lane", "type": "lane", "role": "yielding", "distance_m": gap,
                        "side": intent["dir"], "ts": now})
            print(f"[lane] {vid} → {(str(intent['target']) + '차로') if intent['target'] else intent['dir'] + '(차로 모름)'} 진입 의도, 뒤 {gap}m {best}에 알림", flush=True)


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
    mark_pair_event(vid, yielder, now)  # 양보를 받았다 → 이 차에게는 다시 고마워할 수 있다
    if me.get("auto_thanks") and not me.get("emotes_off"):
        # 운전자 조작 없이 자동으로 감사 인사: 차로 변경 직후는 화면을 보면 안 되는 순간이다
        if deliver_emote(vid, yielder, "thanks", "ahead", now, auto=True):
            pair_sent[(vid, yielder)] = now
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
            # 한 단계에서 예외가 나도 나머지 단계와 다음 주기는 계속 돈다 (루프가 죽으면 모든 판단이 조용히 멈춘다)
            try:
                current_zones = detect_zones(now)
            except Exception:
                traceback.print_exc()
            for step in (zone_notices, weather_loop, charging_forecasts, emergency_yield, close_alerts, lane_changes):
                try:
                    step(now)
                except Exception:
                    print(f"[road_loop] {step.__name__} 실패", flush=True)
                    traceback.print_exc()


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
                    "stats": {v: dict(stats[v]) for v in list(stats)},
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
        elif url.path == "/store":
            self._json(200, store_info(q.get("vehicle")))
        elif url.path == "/surroundings":
            self._json(200, surroundings(q.get("vehicle")))
        elif url.path == "/nearby":
            self._json(200, nearby_info(q.get("vehicle")))
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
            "/store/buy": ("vehicle", "pack"),
            "/forget": ("vehicle",),
            "/rating": ("vehicle", "station", "stars"),
            "/public_status": ("station",),
            "/demo": (),
            "/detections": ("vehicle", "objects"),
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
            # 위치·방향이 숫자가 아니면 받지 않는다 (잘못된 값이 레지스트리에 들어가면 1초 판단 루프가 계산하다 실패한다)
            num = lambda x: isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)
            dest = body.get("dest")
            if (not all(num(body.get(k)) for k in ("lat", "lon", "heading"))
                    or not (body.get("speed_kph") is None or num(body["speed_kph"]))
                    or not (body.get("lane") is None or (isinstance(body["lane"], int) and not isinstance(body["lane"], bool)))
                    or not (body.get("soc") is None or num(body["soc"]))
                    or not (dest is None or (isinstance(dest, dict) and num(dest.get("lat")) and num(dest.get("lon"))))):
                self._json(400, {"error": "invalid position fields"})
                return
            with lock:
                prev = vehicles.get(body["vehicle"], {})
                turn, lane = body.get("turn"), body.get("lane")
                # 깜빡이가 새로 켜지면 진입하려는 차로를 기록한다 (1차로가 가장 왼쪽)
                if turn in ("LEFT", "RIGHT") and turn != prev.get("turn"):
                    # 차로를 모르면(Tier 1 GPS만) 목표 차로 없이 기록하고, 대상은 위치 기하로 넓게 고른다
                    target = (lane - 1 if turn == "LEFT" else lane + 1) if lane else None
                    if target is None or target >= 1:
                        lane_intents[body["vehicle"]] = {"dir": turn, "from_lane": lane, "target": target,
                                                         "since": time.time(), "notified": set()}
                # 감속 중인지: 이전 보고보다 속도가 빠르게 줄면(1.5m/s² 이상) 1.5초 동안 "감속 중"으로 둔다 (시각화의 브레이크등)
                now_ts = time.time()
                prev_kph, prev_ts = prev.get("speed_kph"), prev.get("ts")
                decel_until = prev.get("decel_until", 0)
                if prev_kph is not None and prev_ts and body.get("speed_kph") is not None and 0 < now_ts - prev_ts < 3:
                    if (prev_kph - body["speed_kph"]) / 3.6 / (now_ts - prev_ts) >= DECEL_SHOW_MPS2:
                        decel_until = now_ts + 1.5
                vehicles[body["vehicle"]] = {
                    "lat": body["lat"], "lon": body["lon"], "heading": body["heading"],
                    "speed_kph": body.get("speed_kph"), "lane": body.get("lane"), "turn": body.get("turn"),
                    "role": body.get("role"), "soc": body.get("soc"), "charging": body.get("charging"),
                    "dest": body.get("dest"), "auto_thanks": body.get("auto_thanks", False),
                    "emotes_off": bool(body.get("emotes_off", False)),
                    "emote_pack": body.get("emote_pack") if body.get("emote_pack") in EMOTE_PACKS else "basic",
                    "ts": now_ts, "decel_until": decel_until,
                }
            self._json(200, {"ok": True})
        elif path == "/store/buy":
            self._json(200, store_buy(body))
        elif path == "/emote":
            self._json(200, handle_emote(body))
        elif path == "/rating":
            self._json(200, handle_rating(body))
        elif path == "/detections":
            self._json(200, handle_detections(body))
        elif path == "/public_status":
            self._json(200, handle_public_status(body))
        elif path == "/demo":
            if body.pop("reset", False):  # 시연을 다시 시작할 때: 이벤트·전송 기록을 비운다 (같은 지점 재시연이 '최근 전송함'에 막히지 않게)
                with lock:
                    for table in (events, alerts, reports, sent, close_sent, zone_close_sent, weather_zones, weather_onsets, weather_obs,
                                  pair_sent, pair_event, emote_times, emergency_sent, charging_sent, lane_intents):  # 이모트 제한·긴급차량·충전 안내 기록도 함께
                        table.clear()
                print("[demo] 이벤트·전송 기록 초기화", flush=True)
            if body.pop("reset_stats", False):  # 기여 통계를 처음부터 보여 줄 때
                with lock:
                    for table in (stats, report_log):
                        table.clear()
                print("[demo] 기여 통계 초기화", flush=True)
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
                    if "distance_m" in item and item.get("kind") not in ("emote", "charging", "emergency", "lane", "merge_done"):
                        print(f"[notify] -> {vid}: {item.get('type')} {item['distance_m']}m ({item.get('lane')})", flush=True)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            with lock:
                if subscribers.get(vid) is sub:
                    del subscribers[vid]
            print(f"[unsubscribe] {vid}", flush=True)


if __name__ == "__main__":
    print(f"OverRoad server on 0.0.0.0:{PORT}", flush=True)
    threading.Thread(target=road_loop, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
