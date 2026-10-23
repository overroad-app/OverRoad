package com.hyperconnect.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * HyperConnect 프로토타입: 차량 신호로 급제동·정차·사고·기상 상황을 감지해 서버에 보고하고,
 * 다른 차량이 보고해 확정된 경고를 받아 표시한다.
 *
 * 위치는 내비 위치를 기준으로 차량 속도를 적분해 추정한다 ([PositionEstimator]).
 * 시연에서는 차량 ID, 차로, 시작 위치(기준점에서 진행 방향 거리, 뒤쪽은 음수)를 실행 인자로 정한다.
 *   adb shell am start -n com.hyperconnect.app/.MainActivity --es vehicle_id C --ei lane 2 --ed offset_m -300
 */
class MainActivity : Activity() {

    private lateinit var vehicle: Vehicle
    private lateinit var statusView: TextView
    private lateinit var warningView: TextView
    private lateinit var emoteView: TextView
    private lateinit var noticeView: TextView     // 정체·사고 구간 알림 (잠깐 표시)
    private lateinit var chargingView: TextView   // 목적지 충전소 대기 예측 (계속 표시)
    private lateinit var emergencyView: TextView  // 긴급차량 접근 (지나갈 때까지 표시)
    @Volatile private var soc: Int? = null
    @Volatile private var charging = false
    @Volatile private var destination: Destination? = null
    private var role: String? = null              // 시연용: 긴급차량 역할 (ambulance, fire, police)
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var client: HyperConnectClient? = null
    private var tts: TextToSpeech? = null
    private var naviTracker: NaviTracker? = null
    private lateinit var position: PositionEstimator
    private var ttsReady = false
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    private val sounds = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()
    // 이모트 효과음: scripts/make_emote_sounds.py로 직접 합성한 소리
    private val emoteSounds = mutableMapOf<String, Int>()
    private val handler = Handler(Looper.getMainLooper())

    private var vehicleId = DEFAULT_VEHICLE_ID
    private var lane: Int? = null
    private var speedKph = 0f
    @Volatile private var turnSignal: String? = null  // 정체 원인(합류) 판단에 쓰도록 서버와 공유

    // 신호 이름 -> 최신 값 (화면 표시 순서 유지)
    private val signals = linkedMapOf(
        "서버" to "연결 중",
        "차로" to "-",
        "속도" to "-",
        "기어" to "-",
        "브레이크" to "-",
        "와이퍼" to "-",
        "안개등" to "-",
        "비상등" to "-",
        "경고등" to "-",
        "관측 날씨" to "-",
        "마지막 보고" to "-",
        "마지막 이모트" to "-",
        "배터리" to "-",
    )

    private val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)

    private val hardBrake = HardBrakeDetector { kph, decel ->
        update("마지막 보고", "급제동 %.0fkm/h, %.1fm/s²".format(kph, decel))
        accident.onHardBrake(SystemClock.elapsedRealtime())
        client?.report(EVENT_HARD_BRAKE, mapOf("decel_mps2" to decel.toDouble()))
    }

    private val accident = SecondaryAccidentDetector { type ->
        update("마지막 보고", LABEL[type] ?: type)
        client?.report(type)
    }

    private val weather = WeatherObserver { obs ->
        update("관측 날씨", describe(obs))
        client?.report(EVENT_WEATHER_OBS, mapOf(
            "rain" to obs.rain, "fog" to obs.fog, "slippery" to obs.slippery,
            "defrost" to obs.defrost, "low_beam" to obs.lowBeam,
        ))
    }

    // 리스너 값은 null로 올 수 있다
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            accident.inPark = gear == null || gear == VehicleGear.GEAR_PARK
            update("기어", gear?.name?.removePrefix("GEAR_") ?: "-")
        }
        override fun onFailed(e: Exception) = fail("기어", e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            hardBrake.brakePressed = pressed == true
            update("브레이크", if (pressed == true) "밟음" else "뗌")
        }
        override fun onFailed(e: Exception) = fail("브레이크", e)
    }

    private val fogListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) {
            weather.fogLamp = state == LightState.ON
            update("안개등", if (weather.fogLamp) "켜짐" else "꺼짐")
            weather.evaluate(SystemClock.elapsedRealtime())
        }
        override fun onFailed(e: Exception) = fogUnsupported(e)
    }

    private val lowBeamListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) {
            weather.lowBeam = state == LightState.ON
            weather.evaluate(SystemClock.elapsedRealtime())
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "전조등 조회 실패", e) }
    }

    private val defrostListener = object : HvacDefrosterStateListener {
        override fun onHvacDefrosterStateUpdated(on: Boolean?) {
            weather.defrost = on == true
            weather.evaluate(SystemClock.elapsedRealtime())
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "디포그 조회 실패", e) }
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            turnSignal = state?.takeIf { it != TurnSignalValue.OFF }?.name
            Log.i(TAG, "방향지시등 = ${turnSignal ?: "OFF"}")
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "방향지시등 조회 실패", e) }
    }

    // 경고등: 에어백·ABS·ESC, 그리고 좌우 방향지시등이 함께 켜지면 비상등으로 본다
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(info: Telltales?) = onTelltales(info)
        override fun onFailed(e: Exception) = fail("경고등", e)
    }

    // Vehicle SDK에는 속도 API가 없어 AAOS 표준 속성(PERF_VEHICLE_SPEED)을 쓴다
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = (value.value as? Float) ?: return
            val kph = mps * 3.6f
            if (kotlin.math.abs(kph - speedKph) >= 1f || kph == 0f) {
                speedKph = kph
                update("속도", "%.0f km/h".format(kph))
            }
            val now = SystemClock.elapsedRealtime()
            position.onSpeed(mps, now)
            hardBrake.onSpeed(mps, now)
            accident.onSpeed(kph, now)
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "속도 수신 오류")
        }
    }

    // 속도 콜백은 값이 바뀔 때만 오므로 정차 시간·기상 재전송은 주기적으로 판단한다
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            accident.tick(now)
            vehicle.getEvBattery().getEvChargeSwitchState({ on ->
                if ((on == true) != charging) update("배터리", "${soc ?: "-"}%${if (on == true) " · 충전 중" else ""}")
                charging = on == true
            }, { Log.w(TAG, "충전 상태 조회 실패", it) })
            vehicle.getWiper().getCurrentWiperSwitch(frontWindshield, { sw ->
                if (sw != weather.wiper) update("와이퍼", sw?.name ?: "-")
                weather.wiper = sw
                weather.evaluate(SystemClock.elapsedRealtime())
            }, { Log.w(TAG, "와이퍼 조회 실패", it) })
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicleId = intent.getStringExtra("vehicle_id") ?: DEFAULT_VEHICLE_ID
        lane = intent.getIntExtra("lane", 0).takeIf { it > 0 }
        role = intent.getStringExtra("role")
        // 차로 번호는 시연용 입력이다. 실제 차로 인식은 카메라·정밀지도가 필요한 Tier 2 과제다
        position = PositionEstimator(
            startOffsetM = intent.getDoubleExtra("offset_m", 0.0),
            rightOffsetM = ((lane ?: BASE_LANE) - BASE_LANE) * LANE_WIDTH_M,
        )
        val server = intent.getStringExtra("server") ?: DEFAULT_SERVER

        buildUi()
        emoteSounds["thanks"] = sounds.load(this, R.raw.emote_thanks, 1)
        emoteSounds["sorry"] = sounds.load(this, R.raw.emote_sorry, 1)
        emoteSounds["hello"] = sounds.load(this, R.raw.emote_hello, 1)
        update("차로", lane?.let { "${it}차로" } ?: "미상")

        vehicle = Vehicle(this)
        vehicle.initialize()
        readCurrentValues()
        registerListeners()
        handler.post(ticker)

        if (checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
            startSpeed()
        } else {
            requestPermissions(arrayOf(Car.PERMISSION_SPEED), REQUEST_SPEED)
        }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.KOREAN)?.let { it >= 0 } == true
            Log.i(TAG, "TTS 사용 가능: $ttsReady")
        }

        naviTracker = NaviTracker(
            this,
            onPosition = { position.onNaviPosition(it) },
            onNextTurn = { Log.i(TAG, "다음 안내 = ${it?.type} ${it?.distance}") },
            onDestination = { d ->
                // 경로 안내 중일 때만 목적지가 있다. 충전소 대기 예측에 쓴다
                destination = d?.takeIf { it.remainDistance > 0 }?.let { Destination(it.latitude, it.longitude, it.name, it.remainTime) }
                if (destination == null) runOnUiThread { chargingView.visibility = View.GONE }
            },
            onStatus = { Log.i(TAG, "내비 = $it") },
        ).also { tracker ->
            tracker.start()
            // 시연용: 실행 인자로 목적지를 주면 경로 안내를 시작한다
            if (intent.hasExtra("dest_lat")) {
                tracker.routeTo(intent.getDoubleExtra("dest_lat", 0.0), intent.getDoubleExtra("dest_lon", 0.0),
                    intent.getStringExtra("dest_name") ?: "목적지")
            }
        }

        client = HyperConnectClient(
            baseUrl = server, vehicleId = vehicleId,
            position = { position.current()?.let { (lat, lon, heading) -> Fix(lat, lon, heading, speedKph, lane, turnSignal, soc, charging, destination, role)
            } },
            onConnectionChanged = { update("서버", if (it) "연결됨" else "끊김 (재연결 중)") },
            onWarning = ::showWarning,
            onEmote = ::showEmote,
            onCharging = ::showCharging,
            onEmergency = ::showEmergency,
        ).also { it.start() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode == REQUEST_SPEED && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSpeed()
        else update("속도", "권한 없음")
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        client?.stop()
        naviTracker?.stop()
        propertyManager?.unregisterCallback(speedCallback)
        car?.disconnect()
        tts?.shutdown()
        tone.release()
        sounds.release()
        vehicle.getGear().unregisterGearSelection(gearListener)
        vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
        vehicle.getLight().unregisterFrontFogLightState(fogListener)
        vehicle.getLight().unregisterLowBeamLightState(lowBeamListener)
        vehicle.getHVAC().unregisterHvacDefrosterState(frontWindshield, defrostListener)
        vehicle.getSafety().unregisterTelltales(telltaleListener)
        vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
        vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
        vehicle.release()
        super.onDestroy()
    }

    private fun startSpeed() {
        val c = Car.createCar(this)
        car = c
        propertyManager = (c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).also {
            it.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST)
        }
    }

    private fun readCurrentValues() {
        // get 결과는 값이 없을 때 null로 올 수 있다
        vehicle.getGear().getCurrentGearSelectionState({ gearListener.onSelected(it) }, { fail("기어", it) })
        vehicle.getBrake().getCurrentBrakePedalStatus({ brakeListener.onBrakePedalStatusUpdated(it) }, { fail("브레이크", it) })
        vehicle.getLight().getCurrentFrontFogLightState({ fogListener.onLightStateUpdated(it) }, ::fogUnsupported)
        vehicle.getSafety().getTelltalesInfo(::onTelltales) { fail("경고등", it) }
    }

    private fun registerListeners() {
        // 퍼미션이 없는 도메인이 있어도 나머지 신호는 계속 받도록 하나씩 등록한다
        listOf(
            "기어" to { vehicle.getGear().registerGearSelection(gearListener) },
            "브레이크" to { vehicle.getBrake().registerBrakePedalStatus(brakeListener) },
            "안개등" to { vehicle.getLight().registerFrontFogLightState(fogListener) },
            "전조등" to { vehicle.getLight().registerLowBeamLightState(lowBeamListener) },
            "디포그" to { vehicle.getHVAC().registerHvacDefrosterState(frontWindshield, defrostListener) },
            "경고등" to { vehicle.getSafety().registerTelltales(telltaleListener) },
            "방향지시등" to { vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener) },
            "배터리" to { vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener) },
        ).forEach { (name, register) ->
            try {
                register()
            } catch (e: SecurityException) {
                fail(name, e)
            }
        }
    }

    // 안개등 상태를 지원하지 않는 차량(에뮬레이터 포함)은 안개등 경고등으로 판단한다
    private fun fogUnsupported(e: Exception) {
        Log.w(TAG, "안개등 상태 미지원, 경고등으로 대체", e)
        update("안개등", "경고등으로 확인")
    }

    private fun onTelltales(info: Telltales?) {
        val active = info?.telltales.orEmpty()
            .filter { it.state != TelltaleState.OFF }
            .map { it.type }
            .toSet()
        Log.i(TAG, "경고등 = $active")
        val now = SystemClock.elapsedRealtime()
        accident.hazardOn = TelltaleType.TURN_SIGNAL_LEFT in active && TelltaleType.TURN_SIGNAL_RIGHT in active
        accident.onAirbag(TelltaleType.AIRBAG in active, now)
        weather.absActive = TelltaleType.ABS in active
        weather.escActive = TelltaleType.ESC in active
        if (TelltaleType.FRONT_FOG_LAMP in active) weather.fogLamp = true
        weather.evaluate(now)
        update("비상등", if (accident.hazardOn) "켜짐" else "꺼짐")
        update("경고등", active.filter { it in SHOWN_TELLTALES }.joinToString { it.name }.ifEmpty { "없음" })
    }

    private fun describe(o: WeatherObservation) = buildList {
        if (o.rain > 0) add(listOf("", "약한 비", "비", "폭우")[o.rain])
        if (o.fog) add("안개")
        if (o.slippery) add("미끄러움")
    }.joinToString(", ").ifEmpty { "맑음" }

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            soc = value
            update("배터리", "${value ?: "-"}%${if (charging) " · 충전 중" else ""}")
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "배터리 조회 실패", e) }
    }

    /** 정체·사고 구간은 경고창 대신 내비 안내처럼 짧은 카드로 알린다. */
    private fun showZone(w: RoadWarning) {
        val icon = if (w.zoneLabel?.startsWith("사고") == true) "🚧" else "🚗"
        val text = "$icon  전방 ${w.distanceM}m ${w.zoneLabel ?: "정체 구간"}"
        Log.i(TAG, "구간 알림: $text")
        runOnUiThread {
            noticeView.text = text
            noticeView.visibility = View.VISIBLE
            noticeView.removeCallbacks(hideNotice)
            noticeView.postDelayed(hideNotice, NOTICE_SHOW_MS)
        }
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
    }

    private val hideNotice = Runnable { noticeView.visibility = View.GONE }

    private fun showCharging(c: ChargingForecast) {
        val wait = if (c.waitMin <= 0) "바로 충전 가능" else "도착 시 앞에 ${c.ahead}대 · 약 ${c.waitMin}분 대기"
        val alt = c.altName?.let { "\n대안: $it · ${if ((c.altWaitMin ?: 0) <= 0) "대기 없음" else "약 ${c.altWaitMin}분 대기"}" } ?: ""
        val text = "⚡  ${c.station} · $wait\n나 말고도 이 충전소로 향하는 EV ${c.coming}대$alt"
        Log.i(TAG, "충전 예측: ${text.replace("\n", " / ")}")
        runOnUiThread {
            chargingView.text = text
            chargingView.visibility = View.VISIBLE
        }
    }

    private fun showEmergency(e: EmergencyApproach) {
        val text = if (e.passed) "${e.symbol}  ${e.label}가 지나갔어요"
        else "${e.symbol}  ${e.label} 접근 중 · 뒤 ${e.distanceM}m\n${if (e.sameLane) "오른쪽으로 비켜 길을 열어주세요" else "차로를 유지하세요"}"
        runOnUiThread {
            val first = emergencyView.visibility != View.VISIBLE
            emergencyView.text = text
            emergencyView.visibility = View.VISIBLE
            emergencyView.removeCallbacks(hideEmergency)
            // 접근 중에는 2초마다 갱신이 온다. 갱신이 끊기거나 지나가면 잠시 뒤 닫는다
            emergencyView.postDelayed(hideEmergency, if (e.passed) 3000L else 6000L)
            if (first && !e.passed) {
                Log.i(TAG, "긴급차량: ${text.replace("\n", " / ")}")
                tone.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 800)
            } else if (e.passed) {
                Log.i(TAG, "긴급차량: $text")
            }
        }
    }

    private val hideEmergency = Runnable { emergencyView.visibility = View.GONE }

    private fun showWarning(w: RoadWarning) {
        if (w.type == "congestion") return showZone(w)
        // 멀리 있는 사고·정차 차량은 경고창 대신 구간 카드로 알리고, 가까워졌을 때만 빨간 경고를 띄운다
        if (w.type in FAR_NOTICE_TYPES && w.distanceM > RED_ALERT_M) {
            return showZone(w.copy(zoneLabel = if (w.type == SecondaryAccidentDetector.EVENT_CRASH) "사고 발생" else "정차 차량"))
        }
        val where = if (w.distanceM > 0) "전방 ${w.distanceM}m" else "현재 구간"
        val message = when (w.type) {
            EVENT_HARD_BRAKE -> "$where 급제동 발생, 감속하세요"
            SecondaryAccidentDetector.EVENT_STOPPED ->
                if (w.lane == "same") "$where 같은 차로에 정차 차량, 차로를 변경하세요" else "$where 정차 차량, 주의하세요"
            SecondaryAccidentDetector.EVENT_CRASH -> "$where 사고 발생, 서행하세요"
            "heavy_rain" -> "$where 폭우 구간 (${w.reporters}대 관측), 감속하세요"
            "fog" -> "$where 안개 구간 (${w.reporters}대 관측), 안개등을 켜세요"
            "slippery" -> "$where 미끄러운 노면 (${w.reporters}대 관측), 감속하세요"
            else -> "$where ${w.type}"
        }
        Log.i(TAG, "경고 수신: $message (차로 ${w.lane}, 보고 차량 ${w.reporters}대)")
        runOnUiThread {
            warningView.text = "⚠  $message"
            warningView.visibility = View.VISIBLE
            warningView.removeCallbacks(hideWarning)
            warningView.postDelayed(hideWarning, WARNING_SHOW_MS)
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
        if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "warning")
    }

    private val hideWarning = Runnable { warningView.visibility = View.GONE }

    private fun showEmote(e: Emote) {
        val who = if (e.fromAhead) "앞차" else "뒤차"
        val text = "${e.symbol}  $who${EMOTE_RECEIVED[e.name] ?: ""}"
        Log.i(TAG, "이모트 수신: $text")
        runOnUiThread {
            emoteView.text = text
            emoteView.visibility = View.VISIBLE
            emoteView.removeCallbacks(hideEmote)
            emoteView.postDelayed(hideEmote, EMOTE_SHOW_MS)
        }
        emoteSounds[e.name]?.let { sounds.play(it, 1f, 1f, 1, 0, 1f) }
    }

    private val hideEmote = Runnable { emoteView.visibility = View.GONE }

    // 운전 중 한 번 탭으로 보낼 수 있도록 큰 버튼 세 개만 둔다
    private fun emoteButton(label: String, name: String, toAhead: Boolean) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        setPadding(24, 36, 24, 36)
        setOnClickListener {
            client?.sendEmote(name, toAhead) { result -> update("마지막 이모트", "$label · $result") }
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(12, 0, 12, 0) }
    }

    private fun buildUi() {
        warningView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(200, 30, 30))
            gravity = Gravity.CENTER
            setPadding(48, 64, 48, 64)
            visibility = View.GONE
        }
        // 주행 중 한눈에 읽히도록 크게 표시
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setLineSpacing(0f, 1.1f)
            setPadding(72, 40, 72, 40)
        }
        fun card(bg: Int, sp: Float) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(Color.WHITE)
            setBackgroundColor(bg)
            gravity = Gravity.CENTER
            setPadding(48, 36, 48, 36)
            visibility = View.GONE
        }
        emergencyView = card(Color.rgb(30, 80, 200), 44f)
        noticeView = card(Color.rgb(200, 120, 20), 38f)
        chargingView = card(Color.rgb(20, 110, 120), 32f)
        emoteView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 140, 80))
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            visibility = View.GONE
        }
        val emoteBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 16, 48, 32)
            addView(emoteButton("👍 뒤차에 고마워요", "thanks", toAhead = false))
            addView(emoteButton("🙏 뒤차에 미안해요", "sorry", toAhead = false))
            addView(emoteButton("👍 앞차에 고마워요", "thanks", toAhead = true))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(warningView)
            addView(emoteView)
            addView(emergencyView)
            addView(noticeView)
            addView(chargingView)
            addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(emoteBar)
        })
        render()
    }

    private fun update(name: String, value: String) {
        Log.i(TAG, "$name = $value")
        runOnUiThread {
            signals[name] = value
            render()
        }
    }

    private fun fail(name: String, e: Exception) {
        Log.w(TAG, "$name 조회 실패", e)
        update(name, "오류: ${e.message}")
    }

    private fun render() {
        statusView.text = buildString {
            appendLine("HyperConnect · 차량 $vehicleId")
            signals.forEach { (name, value) -> appendLine("$name : $value") }
        }
    }

    companion object {
        private const val TAG = "HyperConnect"
        private const val EVENT_HARD_BRAKE = "hard_brake"
        private const val EVENT_WEATHER_OBS = "weather_obs"
        private const val REQUEST_SPEED = 1
        private const val TICK_MS = 1000L
        private const val WARNING_SHOW_MS = 8000L
        private const val EMOTE_SHOW_MS = 3000L
        private const val NOTICE_SHOW_MS = 6000L
        private const val RED_ALERT_M = 300
        private val FAR_NOTICE_TYPES = setOf(SecondaryAccidentDetector.EVENT_CRASH, SecondaryAccidentDetector.EVENT_STOPPED)
        private val EMOTE_RECEIVED = mapOf("thanks" to "가 고맙대요", "sorry" to "가 미안하대요", "hello" to "가 인사해요")
        private const val DEFAULT_VEHICLE_ID = "A"
        private const val BASE_LANE = 2
        private const val LANE_WIDTH_M = 3.5
        // 에뮬레이터에서 호스트 Mac을 가리키는 주소
        private const val DEFAULT_SERVER = "http://10.0.2.2:8080"
        private val LABEL = mapOf(
            SecondaryAccidentDetector.EVENT_STOPPED to "정차 차량 보고",
            SecondaryAccidentDetector.EVENT_CRASH to "사고 보고",
        )
        private val SHOWN_TELLTALES = setOf(TelltaleType.AIRBAG, TelltaleType.ABS, TelltaleType.ESC)
    }
}
