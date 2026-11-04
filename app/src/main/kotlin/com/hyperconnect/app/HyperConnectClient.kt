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
)

/** 차로 변경 알림. [merging]이면 내가 진입하려는 쪽, 아니면 내 차로로 들어오려는 차가 있는 쪽. */
data class LaneNotice(val merging: Boolean, val distanceM: Int, val side: String)

/** 내 기여 통계 (설정 화면). */
data class MyStats(
    val reports: Int, val helped: Int, val thanks: Int, val sorry: Int, val hello: Int,
    val accuracy: Int?, val topPct: Int?, val badges: List<String>,
)

/** 목적지 충전소의 도착 시점 대기 예측. [altName]이 있으면 대기가 더 짧은 대안 충전소다. */
data class ChargingForecast(
    val station: String, val ahead: Int, val waitMin: Int, val coming: Int,
    val altName: String?, val altWaitMin: Int?,
)

/** 뒤에서 접근하는 긴급차량. [passed]면 이미 지나갔다. */
data class EmergencyApproach(val label: String, val symbol: String, val distanceM: Int, val sameLane: Boolean, val passed: Boolean)

/** 목적지: 위치·이름·남은 시간(초). 충전 대기 예측에 쓴다. */
data class Destination(val lat: Double, val lon: Double, val name: String, val etaS: Int)

/** 다른 차량이 보낸 이모트. [fromAhead]가 true면 앞차가 보냈다. 보낸 차의 ID는 알려주지 않는다. */
data class Emote(val name: String, val symbol: String, val fromAhead: Boolean, val auto: Boolean = false)

/** 차량 상태: 위치, 진행 방향°, 속도, 차로(모르면 null), 방향지시등(LEFT/RIGHT/null), 배터리·충전·목적지, 긴급차량 역할. */
data class Fix(
    val lat: Double, val lon: Double, val heading: Double, val speedKph: Float, val lane: Int?, val turn: String?,
    val soc: Int? = null, val charging: Boolean = false, val dest: Destination? = null, val role: String? = null,
    val autoThanks: Boolean = false,
)

/**
 * HyperConnect 서버 연결: 위치 갱신·이벤트 보고·이모트 전송(POST)과 경고·이모트 구독(SSE).
 * 구독은 끊기면 자동으로 다시 연결한다.
 */
class HyperConnectClient(
    private val baseUrl: String,
    private val vehicleId: String,
    private val position: () -> Fix?,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onWarning: (RoadWarning) -> Unit,
    private val onEmote: (Emote) -> Unit,
    private val onCharging: (ChargingForecast) -> Unit,
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

    /** 받은 경고가 맞았는지 알린다 (보고한 차량의 신뢰 점수에 반영). */
    fun sendFeedback(eventId: String, correct: Boolean) {
        Thread({
            try {
                post("/feedback", JSONObject().put("vehicle", vehicleId).put("event_id", eventId).put("correct", correct))
            } catch (e: Exception) {
                Log.w(TAG, "피드백 전송 실패", e)
            }
        }, "hc-feedback").start()
    }

    /** 내 기여 통계를 가져온다. */
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
                    accuracy = if (o.isNull("accuracy")) null else o.getInt("accuracy"),
                    topPct = if (o.isNull("top_pct")) null else o.getInt("top_pct"),
                    badges = o.getJSONArray("badges").let { a -> List(a.length()) { a.getString(it) } },
                )
            } catch (e: Exception) {
                Log.w(TAG, "통계 조회 실패", e)
                null
            }
            onResult(result)
        }, "hc-stats").start()
    }

    private fun Fix.toJson() = JSONObject()
        .put("vehicle", vehicleId).put("lat", lat).put("lon", lon).put("heading", heading)
        .put("speed_kph", speedKph.toDouble()).put("lane", lane ?: JSONObject.NULL)
        .put("turn", turn ?: JSONObject.NULL)
        .put("soc", soc ?: JSONObject.NULL).put("charging", charging)
        .put("role", role ?: JSONObject.NULL).put("auto_thanks", autoThanks)
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
                                o.optString("from") == "ahead", o.optBoolean("auto")))
                            "lane" -> onLane(LaneNotice(o.optString("role") == "merging", o.getInt("distance_m"), o.optString("side")))
                            "merge_done" -> onMergeDone(o.optBoolean("auto"))
                            "charging" -> onCharging(ChargingForecast(
                                station = o.getString("station"), ahead = o.getInt("ahead"),
                                waitMin = o.getInt("wait_min"), coming = o.getInt("coming"),
                                altName = o.optJSONObject("alt")?.optString("name"),
                                altWaitMin = o.optJSONObject("alt")?.optInt("wait_min"),
                            ))
                            "emergency" -> onEmergency(EmergencyApproach(
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
        )
    }

    companion object {
        private const val TAG = "HyperConnect"
        private const val RETRY_MS = 2000L
        private const val POSITION_INTERVAL_MS = 1000L
    }
}
