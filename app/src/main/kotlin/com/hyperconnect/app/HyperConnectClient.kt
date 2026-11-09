package com.hyperconnect.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 서버에서 받은 경고. */
data class RoadWarning(
    val type: String, val distanceM: Int, val lane: String, val reporters: Int,
    val zoneLabel: String? = null, val lengthM: Int? = null, val eventId: String? = null,
    val tail: Boolean = false,  // 사고 정체의 꼬리에 다가옴 (2차 사고 방지 rev2)
)

/** 차로 변경 알림. [merging]이면 내가 진입하려는 쪽, 아니면 내 차로로 들어오려는 차가 있는 쪽. */
data class LaneNotice(val merging: Boolean, val distanceM: Int, val side: String)

/** 내 기여 통계 (설정 화면). */
data class MyStats(
    val reports: Int, val helped: Int, val thanks: Int, val sorry: Int, val hello: Int,
    val topPct: Int?, val badges: List<String>, val history: List<ReportRecord>,
)

/** 내 보고 한 건: 언제, 무엇을 보고해 몇 대에게 전달됐는지. */
data class ReportRecord(val atMs: Long, val type: String, val notified: Int)

/**
 * 목적지 충전소 예측 (rev2): 도착 순번([ahead]+1)·대기·충전 시작/완료 시각과 충전소 정보(실측 출력, 별점).
 * 시각은 epoch 밀리초. [altName]이 있으면 충전이 더 빨리 끝나는 대안 충전소다.
 */
data class ChargingForecast(
    val station: String, val stationId: String, val rating: Double?, val ratingN: Int,
    val kw: Int, val kwMeasured: Boolean, val sessions: Int,
    val ahead: Int, val waitMin: Int, val chargeMin: Int, val etaMs: Long, val startMs: Long, val doneMs: Long,
    val stalls: Int, val chargingNetwork: Int, val chargingOther: Int, val expectedOther: Int, val coming: Int,
    val altName: String?, val altWaitMin: Int?, val altDoneMs: Long?,
    val operator: String? = null, val free: Int = 0, val socArrival: Int? = null, val known: Int = 0, val estimated: Int = 0,
)

/** 내 충전이 끝남: 이 기록(시간·실측 출력)이 충전소 정보로 다른 운전자에게 쓰인다. 별점을 받을 수 있다. */
data class ChargeDone(val station: String, val stationId: String, val minutes: Int, val kw: Int, val socFrom: Int, val socTo: Int)

/** 뒤에서 접근하는 긴급차량. [role]은 police·fire·ambulance, [id]는 긴급차량 구분용. [passed]면 이미 지나갔다. */
data class EmergencyApproach(
    val id: String, val role: String, val label: String, val symbol: String,
    val distanceM: Int, val sameLane: Boolean, val passed: Boolean,
)

/** 충전소 지도의 핀: 별점·혼잡과, 이 차량이 그곳으로 갈 때의 대기·완료 시각. */
data class ChargerPin(
    val id: String, val name: String, val lat: Double, val lon: Double, val rating: Double?, val ratingN: Int,
    val stalls: Int, val charging: Int, val waitMin: Int?, val doneMs: Long?, val isDest: Boolean,
)

data class ChargerMap(val myLat: Double?, val myLon: Double?, val pins: List<ChargerPin>)

/** 목적지: 위치·이름·남은 시간(초). 충전 대기 예측에 쓴다. */
data class Destination(val lat: Double, val lon: Double, val name: String, val etaS: Int)

/** 다른 차량이 보낸 이모트. [fromAhead]가 true면 앞차가 보냈다. 보낸 차의 ID는 알려주지 않는다. [pack]은 보낸 차의 이모트 팩. */
data class Emote(
    val name: String, val symbol: String, val fromAhead: Boolean, val auto: Boolean = false,
    val pack: String = "basic", val packName: String = "기본",
)

/** 차량 상태: 위치, 진행 방향°, 속도, 차로(모르면 null), 방향지시등(LEFT/RIGHT/null), 배터리·충전·목적지, 긴급차량 역할. */
data class Fix(
    val lat: Double, val lon: Double, val heading: Double, val speedKph: Float, val lane: Int?, val turn: String?,
    val soc: Int? = null, val charging: Boolean = false, val dest: Destination? = null, val role: String? = null,
    val autoThanks: Boolean = false, val emotePack: String = "basic",
    /** 항상 감정표현 받지 않기 */
    val emotesOff: Boolean = false,
)

/**
 * OverRoad 서버 연결: 위치 갱신·이벤트 보고·이모트 전송(POST)과 경고·이모트 구독(SSE).
 * 구독은 끊기면 자동으로 다시 연결한다.
 */
/** 세부 정보 화면에 보여 줄 주변 상황 (문구는 서버가 만든다). */
data class NearbyInfo(val weather: List<String>, val accidents: List<String>, val emergency: List<String>, val connected: Int)

class HyperConnectClient(
    private val baseUrl: String,
    private val vehicleId: String,
    private val position: () -> Fix?,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onWarning: (RoadWarning) -> Unit,
    private val onEmote: (Emote) -> Unit,
    private val onCharging: (ChargingForecast) -> Unit,
    private val onChargeDone: (ChargeDone) -> Unit,
    private val onEmergency: (EmergencyApproach) -> Unit,
    private val onLane: (LaneNotice) -> Unit,
    private val onMergeDone: (auto: Boolean) -> Unit,
) {
    @Volatile
    private var running = false
    private var connection: HttpURLConnection? = null

    fun start() {
        running = true
        Thread(::subscribeLoop, "hc-subscribe").start()
        Thread(::positionLoop, "hc-position").start()
    }

    fun stop() {
        running = false
        connection?.disconnect()
    }

    fun report(type: String, fields: Map<String, Any?> = emptyMap()) {
        val fix = position()
        if (fix == null) {
            Log.w(TAG, "위치가 없어 보고를 건너뜀: $type")
            return
        }
        Thread({
            try {
                val body = fix.toJson().put("type", type)
                fields.forEach { (k, v) -> body.put(k, v) }
                val result = post("/events", body)
                Log.i(TAG, "보고 완료 $type: $result")
            } catch (e: Exception) {
                Log.w(TAG, "보고 실패 $type", e)
            }
        }, "hc-report").start()
    }

    /** 바로 앞차([toAhead]) 또는 뒤차에게 이모트를 보낸다. 결과 문구를 [onResult]로 돌려준다. */
    fun sendEmote(name: String, toAhead: Boolean, onResult: (String) -> Unit) {
        Thread({
            val result = try {
                val body = JSONObject().put("vehicle", vehicleId).put("emote", name)
                    .put("target", if (toAhead) "ahead" else "behind")
                val o = JSONObject(post("/emote", body))
                if (o.optBoolean("delivered")) "${if (toAhead) "앞차" else "뒤차"}에게 보냈어요"
                else o.optString("reason", "보내지 못했어요")
            } catch (e: Exception) {
                Log.w(TAG, "이모트 전송 실패", e)
                "서버 연결 실패"
            }
            onResult(result)
        }, "hc-emote").start()
    }

    /** 충전소 별점 (1~5). 다른 운전자의 충전소 정보에 쓰인다. */
    fun sendRating(stationId: String, stars: Int) {
        Thread({
            try {
                post("/rating", JSONObject().put("vehicle", vehicleId).put("station", stationId).put("stars", stars))
            } catch (e: Exception) {
                Log.w(TAG, "별점 전송 실패", e)
            }
        }, "hc-rating").start()
    }

    /** 받은 경고가 맞았는지 알린다 (보고한 차량의 신뢰 점수에 반영). */
    /** 데이터 공유 동의 철회: 서버에 이 차량의 데이터를 지우도록 요청한다. */
    fun forget(onDone: (Boolean) -> Unit) {
        Thread({
            val ok = try {
                post("/forget", JSONObject().put("vehicle", vehicleId))
                true
            } catch (e: Exception) {
                Log.w(TAG, "데이터 삭제 요청 실패", e)
                false
            }
            onDone(ok)
        }, "hc-forget").start()
    }

    /** 내 기여 통계를 가져온다. */
    /** 주변 차량 시각화용 상대 위치 (전체 화면이 보일 때만 1초마다 부른다). */
    fun fetchSurroundings(onResult: (Surroundings?) -> Unit) {
        Thread({
            val result = try {
                val conn = URL("$baseUrl/surroundings?vehicle=${URLEncoder.encode(vehicleId, "UTF-8")}").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val me = o.optJSONObject("me")
                if (me == null) null else {
                    val a = o.getJSONArray("vehicles")
                    Surroundings(
                        myLane = if (me.isNull("lane")) null else me.getInt("lane"), mySpeedKph = me.optDouble("speed_kph", 0.0),
                        myDecel = me.optBoolean("decel"),
                        cars = List(a.length()) { i ->
                            a.getJSONObject(i).let { v ->
                                SurroundCar(v.getInt("key"), v.getDouble("along"), v.getDouble("cross"),
                                    if (v.isNull("lane")) null else v.getInt("lane"), v.optDouble("speed_kph", 0.0),
                                    v.optString("turn").takeIf { it == "LEFT" || it == "RIGHT" },
                                    v.optString("role").takeIf { it.isNotEmpty() && it != "null" }, v.optBoolean("braking"),
                                    v.optBoolean("decel"), v.optBoolean("member", true),
                                    v.optString("emote_target").takeIf { it == "ahead" || it == "behind" },
                                    when {
                                        v.isNull("emote_block") -> null
                                        v.optString("emote_block") == "off" -> -1
                                        v.optString("emote_block") == "self" -> -2
                                        else -> v.optInt("emote_block")
                                    })
                            }
                        },
                        connected = o.optInt("connected"), aheadM = o.optDouble("ahead_m", 200.0),
                        behindM = o.optDouble("behind_m", 86.0), errorM = o.optDouble("error_m", 5.0),
                        observed = o.optInt("observed"),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "주변 차량 조회 실패", e)
                null
            }
            onResult(result)
        }, "hc-surround").start()
    }

    /** 이모트 상점: 장착 중인 팩과 가진 팩. */
    fun fetchStore(onResult: (StoreInfo?) -> Unit) {
        Thread({
            val result = try {
                val conn = URL("$baseUrl/store?vehicle=${URLEncoder.encode(vehicleId, "UTF-8")}").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val a = o.getJSONArray("packs")
                StoreInfo(o.optString("equipped", "basic"),
                    (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("owned") }.map { it.getString("id") }.toSet())
            } catch (e: Exception) {
                Log.w(TAG, "상점 조회 실패", e)
                null
            }
            onResult(result)
        }, "hc-store").start()
    }

    /** 구매 요청: 지금은 서버가 "결제 SDK 출시 후" 안내를 돌려준다. */
    fun buyPack(id: String, onResult: (String) -> Unit) {
        Thread({
            val msg = try {
                JSONObject(post("/store/buy", JSONObject().put("vehicle", vehicleId).put("pack", id))).optString("reason", "구매했어요")
            } catch (e: Exception) {
                "지금은 구매할 수 없어요"
            }
            onResult(msg)
        }, "hc-buy").start()
    }

    fun fetchStats(onResult: (MyStats?) -> Unit) {
        Thread({
            val result = try {
                val conn = URL("$baseUrl/stats?vehicle=${URLEncoder.encode(vehicleId, "UTF-8")}").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val e = o.getJSONObject("emotes")
                MyStats(
                    reports = o.getInt("reports"), helped = o.getInt("helped"),
                    thanks = e.optInt("thanks"), sorry = e.optInt("sorry"), hello = e.optInt("hello"),
                    topPct = if (o.isNull("top_pct")) null else o.getInt("top_pct"),
                    badges = o.getJSONArray("badges").let { a -> List(a.length()) { a.getString(it) } },
                    history = o.optJSONArray("history")?.let { a ->
                        List(a.length()) { i ->
                            a.getJSONObject(i).let { v -> ReportRecord((v.getDouble("ts") * 1000).toLong(), v.getString("type"), v.getInt("notified")) }
                        }
                    } ?: emptyList(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "통계 조회 실패", e)
                null
            }
            onResult(result)
        }, "hc-stats").start()
    }

    /** 충전소 지도 데이터를 가져온다. */
    /** 세부 정보 화면: 내 주변의 기상·사고·긴급차량·연결 차량 수. 창을 키웠을 때만 묻는다. */
    fun fetchNearby(onResult: (NearbyInfo?) -> Unit) {
        Thread({
            val result = try {
                val conn = URL("$baseUrl/nearby?vehicle=${URLEncoder.encode(vehicleId, "UTF-8")}").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                fun strings(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } }
                NearbyInfo(strings("weather"), strings("accidents"), strings("emergency"), o.getInt("connected"))
            } catch (e: Exception) {
                Log.w(TAG, "주변 정보 조회 실패", e)
                null
            }
            onResult(result)
        }, "nearby").start()
    }

    fun fetchChargers(onResult: (ChargerMap?) -> Unit) {
        Thread({
            val result = try {
                val conn = URL("$baseUrl/chargers?vehicle=${URLEncoder.encode(vehicleId, "UTF-8")}").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val me = o.optJSONObject("me")
                val a = o.getJSONArray("chargers")
                ChargerMap(me?.getDouble("lat"), me?.getDouble("lon"), List(a.length()) { i ->
                    val c = a.getJSONObject(i)
                    val f = c.optJSONObject("forecast")
                    ChargerPin(
                        id = c.getString("id"), name = c.getString("name"), lat = c.getDouble("lat"), lon = c.getDouble("lon"),
                        rating = if (c.isNull("rating")) null else c.getDouble("rating"), ratingN = c.optInt("rating_n"),
                        stalls = c.getInt("stalls"), charging = c.getInt("charging"),
                        waitMin = f?.getInt("wait_min"), doneMs = f?.let { (it.getDouble("done_ts") * 1000).toLong() },
                        isDest = c.optBoolean("is_dest"),
                    )
                })
            } catch (e: Exception) {
                Log.w(TAG, "충전소 지도 조회 실패", e)
                null
            }
            onResult(result)
        }, "hc-chargers").start()
    }

    private fun Fix.toJson() = JSONObject()
        .put("vehicle", vehicleId).put("lat", lat).put("lon", lon).put("heading", heading)
        .put("speed_kph", speedKph.toDouble()).put("lane", lane ?: JSONObject.NULL)
        .put("turn", turn ?: JSONObject.NULL)
        .put("soc", soc ?: JSONObject.NULL).put("charging", charging)
        .put("role", role ?: JSONObject.NULL).put("auto_thanks", autoThanks).put("emote_pack", emotePack).put("emotes_off", emotesOff)
        .put("dest", dest?.let {
            JSONObject().put("lat", it.lat).put("lon", it.lon).put("name", it.name).put("eta_s", it.etaS)
        } ?: JSONObject.NULL)

    private fun post(path: String, body: JSONObject): String {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3000
            readTimeout = 3000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 서버가 이 차량의 현재 위치로 경고 대상 여부를 판단하도록 주기적으로 알린다. */
    private fun positionLoop() {
        while (running) {
            position()?.let { fix ->
                try {
                    post("/position", fix.toJson())
                } catch (e: Exception) {
                    Log.w(TAG, "위치 갱신 실패: ${e.message}")
                }
            }
            Thread.sleep(POSITION_INTERVAL_MS)
        }
    }

    private fun subscribeLoop() {
        while (running) {
            try {
                val conn = (URL("$baseUrl/stream?vehicle=${URLEncoder.encode(vehicleId, "UTF-8")}")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 30_000 // 서버가 15초마다 ping을 보낸다
                }
                connection = conn
                conn.inputStream.bufferedReader().use { reader ->
                    onConnectionChanged(true)
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data: ")) continue
                        val o = JSONObject(line.removePrefix("data: "))
                        when (o.optString("kind")) {
                            "emote" -> onEmote(Emote(o.getString("emote"), o.getString("symbol"),
                                o.optString("from") == "ahead", o.optBoolean("auto"),
                                o.optString("pack", "basic"), o.optString("pack_name", "기본")))
                            "lane" -> onLane(LaneNotice(o.optString("role") == "merging", o.getInt("distance_m"), o.optString("side")))
                            "merge_done" -> onMergeDone(o.optBoolean("auto"))
                            "charging" -> onCharging(ChargingForecast(
                                station = o.getString("station"), stationId = o.getString("station_id"),
                                rating = if (o.isNull("rating")) null else o.getDouble("rating"), ratingN = o.optInt("rating_n"),
                                kw = o.getInt("kw"), kwMeasured = o.optBoolean("kw_measured"), sessions = o.optInt("sessions"),
                                ahead = o.getInt("ahead"), waitMin = o.getInt("wait_min"), chargeMin = o.getInt("charge_min"),
                                etaMs = (o.getDouble("eta_ts") * 1000).toLong(), startMs = (o.getDouble("start_ts") * 1000).toLong(),
                                doneMs = (o.getDouble("done_ts") * 1000).toLong(),
                                stalls = o.getInt("stalls"), chargingNetwork = o.getInt("charging_network"),
                                chargingOther = o.getInt("charging_other"), expectedOther = o.optInt("expected_other"),
                                coming = o.getInt("coming"),
                                altName = o.optJSONObject("alt")?.optString("name"),
                                altWaitMin = o.optJSONObject("alt")?.optInt("wait_min"),
                                altDoneMs = o.optJSONObject("alt")?.let { (it.getDouble("done_ts") * 1000).toLong() },
                                operator = o.optString("operator").takeIf { it.isNotEmpty() && it != "null" },
                                free = o.optInt("free"), socArrival = if (o.isNull("soc_arrival")) null else o.optInt("soc_arrival"),
                                known = o.optInt("known"), estimated = o.optInt("estimated"),
                            ))
                            "charge_done" -> onChargeDone(ChargeDone(
                                station = o.getString("station"), stationId = o.getString("station_id"),
                                minutes = o.getInt("minutes"), kw = o.getInt("kw"),
                                socFrom = o.getInt("soc_from"), socTo = o.getInt("soc_to"),
                            ))
                            "emergency" -> onEmergency(EmergencyApproach(
                                id = o.optString("id", o.optString("role")), role = o.optString("role"),
                                label = o.getString("label"), symbol = o.getString("symbol"),
                                distanceM = o.getInt("distance_m"), sameLane = o.optString("lane") == "same",
                                passed = o.optString("status") == "passed",
                            ))
                            else -> onWarning(parse(o))
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "구독 끊김, 재연결 대기", e)
            }
            onConnectionChanged(false)
            if (running) Thread.sleep(RETRY_MS)
        }
    }

    private fun parse(o: JSONObject): RoadWarning {
        return RoadWarning(
            type = o.getString("type"),
            distanceM = o.getInt("distance_m"),
            lane = o.optString("lane", "unknown"),
            reporters = o.optInt("reporters", 1),
            zoneLabel = o.optString("label").ifEmpty { null },
            lengthM = if (o.has("length_m")) o.optInt("length_m") else null,
            eventId = o.optString("event_id").ifEmpty { null }?.takeIf { it != "null" },
            tail = o.optString("zone") == "tail",
        )
    }

    companion object {
        private const val TAG = "HyperConnect"
        private const val RETRY_MS = 2000L
        private const val POSITION_INTERVAL_MS = 1000L
    }
}
