package com.hyperconnect.app

import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener as PleosTtsListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
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
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.animation.OvershootInterpolator
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.FrameLayout
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
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
    private lateinit var statusChip: TextView     // 위쪽 상태 줄 (연결·차로·속도)
    private lateinit var sheet: AssistantSheet    // 평소에는 이모트 바만, 끌어 올리면 세부 정보
    private lateinit var infoRows: LinearLayout   // 세부 정보: 기상·사고·긴급차량·충전·주변 차량
    private lateinit var mapView: ChargerMapView  // 충전소 지도 (정차 중)
    // 충전 여정 (EV rev4): 중간 크기 창의 가로 막대, 작은 창의 알약 링 (내비가 앞이면 알림 아이콘 링)
    private lateinit var tripBar: TripView
    private lateinit var tripPill: TripView
    private var trip: Trip? = null
    private var tripStation: String? = null
    private var tripStartMs = 0L
    private var ratingReadyAtMs = 0L
    private val ratingStars = mutableListOf<TextView>()
    private lateinit var surround: SurroundView   // 연결망 시각화: 정차 중 전체 화면의 배경
    private lateinit var topBar: LinearLayout
    private lateinit var warningView: LinearLayout  // 도로 위험 (종류별 색·띠·제목: 급제동은 브레이크등 디자인)
    private lateinit var warningStripe: View
    private lateinit var warningTitle: TextView
    private lateinit var warningLine: TextView
    private lateinit var emoteCard: FrameLayout   // 받은 이모트 (정차·저속일 때만, 팩별 색·애니메이션)
    private lateinit var emoteEmoji: TextView
    private lateinit var emoteCaption: TextView
    private lateinit var emotePackLabel: TextView
    private lateinit var emoteParticles: FrameLayout
    private lateinit var noticeView: TextView     // 정체·사고 구간 알림 (잠깐 표시)
    private lateinit var chargingView: TextView   // 목적지 충전소 대기 예측 (계속 표시)
    private lateinit var ratingBar: LinearLayout  // 충전을 마친 뒤 충전소 별점 (정차 중에만)
    private var pendingRating: ChargeDone? = null
    // 시연용: 실행 인자로 차량 위치를 고정한다 (예: 충전소에 서 있는 차량). 실차는 내비 위치를 쓴다
    private var fixedPosition: Pair<Double, Double>? = null
    private lateinit var emergencyCard: LinearLayout  // 긴급차량 접근 (역할별 색·문구, 지나갈 때까지 표시)
    private lateinit var emergencyStripe: View
    private lateinit var emergencyTitle: TextView
    private lateinit var emergencyLine: TextView
    private lateinit var laneView: TextView       // 차로 변경 의도 알림
    private lateinit var mergeThanksButton: Button  // 자동 감사 인사를 끈 경우, 정차·저속일 때만 표시
    private lateinit var emoteBar: LinearLayout
    private lateinit var prefs: Prefs
    private lateinit var notifier: AlertNotifier
    private lateinit var settings: SettingsPanel
    private lateinit var gearButton: Button
    // 알림 상태 타이머: 경고 카드들은 화면에 붙이지 않으므로(OS 상단 알림으로만 보임) View.postDelayed가 돌지 않는다 → 메인 핸들러로
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var storeButton: Button   // 이모트 상점: 정차 중 전체 화면에서만 보인다
    private lateinit var store: StorePanel
    private lateinit var consent: ConsentView
    private var serverUrl = DEFAULT_SERVER
    private lateinit var offlineView: TextView    // 서버 연결이 끊기면 경고를 받을 수 없다는 안내

    // 플랫폼 주행 규제 (CarUxRestrictions). 설정 화면은 NO_SETUP이 걸리면(정차·주행 중) 열 수 없다
    private var uxManager: CarUxRestrictionsManager? = null
    @Volatile private var setupRestricted = false

    // 주의 분산 대응: 주행 중 이모트는 소리만, 바쁜 순간(경고·차로 변경)에는 미뤘다가 들려준다
    private val pendingEmotes = ArrayDeque<Emote>()
    private val unseenEmotes = mutableListOf<String>()
    private var busyUntilMs = 0L
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
    // Pleos TTS(기기 내 모드, 앱 등록 불필요)를 먼저 쓰고, 안 되면 안드로이드 TTS로 대신한다
    private var pleosTts: PleosTts? = null
    @Volatile private var pleosTtsReady = false
    private val pleosTtsListener = object : PleosTtsListener {
        override fun onStart() {}
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onDone() { handler.post(releaseFocus) }
        override fun onStop() { handler.post(releaseFocus) }
        override fun onError(type: ErrorType) {
            Log.w(TAG, "Pleos TTS 오류: $type")
            handler.post(releaseFocus)
        }
    }
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    // 모든 효과음·음성은 재생 전에 오디오 포커스를 잠깐(MAY_DUCK) 받아 음악·내비 소리를 낮추고, 끝나면 돌려준다
    private val alertAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val sounds = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(alertAttributes)
        .build()
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var focusRequest: AudioFocusRequest? = null
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
        busyUntilMs = SystemClock.elapsedRealtime() + BUSY_HOLD_MS
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
            val wasSlowBefore = isSlow()
            if (kotlin.math.abs(kph - speedKph) >= 1f || kph == 0f) {
                val wasSlow = isSlow()
                speedKph = kph
                update("속도", "%.0f km/h".format(kph))
                if (wasSlow != isSlow()) runOnUiThread { updateDriverUi(SystemClock.elapsedRealtime()) }
            }
            // 주행을 시작하면 앱 화면이 내비를 가리지 않도록 스스로 뒤로 물러난다 (이후 알림은 상단 알림 + 음성)
            // 단, '주행 중 연결망 화면(상위 티어 미리보기)'을 켜고 전체 화면으로 보고 있으면 그대로 둔다
            val keepView = prefs.drivingView && sheet.level == AssistantSheet.LEVEL_FULL
            // 출발하는 순간에만 물러난다 → 주행 중 사용자가 다시 열면 중간 크기까지 쓸 수 있다
            if (wasSlowBefore && kph > SLOW_KPH && inFront && !keepView && settings.view.visibility != View.VISIBLE && consent.view.visibility != View.VISIBLE) {
                Log.i(TAG, "주행 시작: 내비가 보이도록 앱 화면을 뒤로 보냄")
                inFront = false
                runOnUiThread { sheet.snapTo(AssistantSheet.LEVEL_MINI) }
                showNavigation()
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
            if (chargeSwitchSupported != false) vehicle.getEvBattery().getEvChargeSwitchState({ on ->
                if ((on == true) != charging) update("배터리", "${soc ?: "-"}%${if (on == true) " · 충전 중" else ""}")
                charging = on == true
            }, { Log.w(TAG, "충전 상태 조회 실패", it) })
            if (wiperSupported != false) vehicle.getWiper().getCurrentWiperSwitch(frontWindshield, { sw ->
                if (sw != weather.wiper) update("와이퍼", sw?.name ?: "-")
                weather.wiper = sw
                weather.evaluate(SystemClock.elapsedRealtime())
            }, { Log.w(TAG, "와이퍼 조회 실패", it) })
            updateDriverUi(now)
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
        serverUrl = intent.getStringExtra("server") ?: DEFAULT_SERVER
        if (intent.hasExtra("fix_lat")) fixedPosition = intent.getDoubleExtra("fix_lat", 0.0) to intent.getDoubleExtra("fix_lon", 0.0)

        prefs = Prefs(this)
        notifier = AlertNotifier(this)
        // 시연 자동화용: 실행 인자로 동의 상태를 미리 정한다 (실사용에서는 동의 화면에서만 바뀐다)
        if (intent.hasExtra("consent")) prefs.consented = intent.getBooleanExtra("consent", false)
        intent.getStringExtra("emote_pack")?.let { prefs.emotePack = it }
        if (intent.hasExtra("driving_view")) prefs.drivingView = intent.getBooleanExtra("driving_view", false)
        buildUi()
        registerLaneReceiver()
        EmotePacks.all.forEach { pack -> pack.sounds.forEach { (name, res) -> emoteSounds["${pack.id}:$name"] = sounds.load(this, res, 1) } }
        update("차로", lane?.let { "${it}차로" } ?: "미상")

        vehicle = Vehicle(this)
        vehicle.initialize()
        readCurrentValues()
        registerListeners()
        handler.post(ticker)

        car = Car.createCar(this)
        watchUxRestrictions()
        // 속도(차량 신호)와 상단 알림(내비 위 경고) 권한. 둘 다 Dangerous 권한이라 실행 중에 묻는다
        val missing = listOf(Car.PERMISSION_SPEED, android.Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Car.PERMISSION_SPEED !in missing) startSpeed()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_SPEED)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.KOREAN)?.let { it >= 0 } == true
            Log.i(TAG, "TTS 사용 가능: $ttsReady")
            tts?.setAudioAttributes(alertAttributes)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == UTTERANCE_WARNING) handler.post(releaseFocus) }
                @Deprecated("기본 오류 콜백") override fun onError(id: String?) { handler.post(releaseFocus) }
            })
        }

        startPleosTts()

        naviTracker = NaviTracker(
            this,
            onPosition = { position.onNaviPosition(it) },
            onNextTurn = { Log.i(TAG, "다음 안내 = ${it?.type} ${it?.distance}") },
            onDestination = { d ->
                // 경로 안내 중일 때만 목적지가 있다. 충전소 대기 예측에 쓴다
                destination = d?.takeIf { it.remainDistance > 0 }?.let { Destination(it.latitude, it.longitude, it.name, it.remainTime) }
                // 경로 안내가 끝나면 충전 예측 카드만 닫는다 (충전 완료·별점 카드는 남긴다)
                if (destination == null && trip != null) clearTrip()
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

        if (prefs.consented) startSharing() else consent.show()
    }

    /** 동의한 뒤에만 서버 연결을 시작한다. */
    private fun startSharing() {
        if (client != null) return
        consent.hide()
        client = HyperConnectClient(
            baseUrl = serverUrl, vehicleId = vehicleId,
            position = { (fixedPosition?.let { (lat, lon) -> Triple(lat, lon, 0.0) } ?: position.current())?.let { (lat, lon, heading) -> Fix(lat, lon, heading, speedKph, lane, turnSignal, soc, charging, destination, role,
                autoThanks = prefs.autoThanks, emotePack = prefs.emotePack)
            } },
            onConnectionChanged = { connected ->
                update("서버", if (connected) "연결됨" else "끊김 (재연결 중)")
                runOnUiThread { offlineView.visibility = if (connected) View.GONE else View.VISIBLE }
            },
            onWarning = ::showWarning,
            onEmote = ::showEmote,
            onCharging = ::showCharging,
            onChargeDone = ::onChargeDone,
            onEmergency = ::showEmergency,
            onLane = ::showLane,
            onMergeDone = ::onMergeDone,
        ).also { it.start() }
        KeepAliveService.start(this)
        Log.i(TAG, "데이터 공유 시작 (동의함)")
    }

    /** 동의 철회: 전송을 멈추고 서버에 내 데이터 삭제를 요청한다. */
    private fun withdrawConsent() {
        val c = client ?: return
        client = null
        prefs.consented = false
        c.stop()
        c.forget { ok -> Log.i(TAG, "동의 철회: 서버 데이터 삭제 ${if (ok) "완료" else "실패"}") }
        KeepAliveService.stop(this)
        update("서버", "공유 중단 (동의 철회)")
        settings.view.visibility = View.GONE
        offlineView.visibility = View.GONE
        consent.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        val speed = permissions.indexOf(Car.PERMISSION_SPEED)
        if (speed < 0) return
        if (results.getOrNull(speed) == PackageManager.PERMISSION_GRANTED) startSpeed() else update("속도", "권한 없음")
    }

    /** 맨 위 화면에서 뒤로 가기(CCP Back 포함)는 앱을 끄지 않고 숨긴다. 숨겨져도 경고 수신은 계속된다. */
    @Deprecated("Activity 기본 뒤로 가기 처리")
    override fun onBackPressed() {
        if (store.view.visibility == View.VISIBLE) {
            store.view.visibility = View.GONE
        } else if (settings.view.visibility == View.VISIBLE) {
            settings.view.visibility = View.GONE
        } else {
            showNavigation()
        }
    }

    /** 플랫폼이 내려주는 주행 규제를 따른다. 에뮬레이터 실측: 주차 0x0, 정차·주행 0x50(NO_VIDEO + NO_SETUP). */
    private fun watchUxRestrictions() {
        val manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
        uxManager = manager
        applyUxRestrictions(manager.currentCarUxRestrictions)
        manager.registerListener { applyUxRestrictions(it) }
    }

    private fun applyUxRestrictions(r: CarUxRestrictions?) {
        val restricted = r != null && r.isRequiresDistractionOptimization &&
            (r.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP) != 0
        if (restricted == setupRestricted && r != null) return
        setupRestricted = restricted
        Log.i(TAG, "주행 규제 = 0x${Integer.toHexString(r?.activeRestrictions ?: 0)} (설정 화면 ${if (restricted) "잠김" else "허용"})")
        runOnUiThread {
            gearButton.alpha = if (restricted) 0.35f else 1f
            if (restricted) settings.view.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        KeepAliveService.stop(this)
        releaseFocus.run()
        uxManager?.unregisterListener()
        unregisterReceiver(laneReceiver)
        client?.stop()
        naviTracker?.stop()
        propertyManager?.unregisterCallback(speedCallback)
        car?.disconnect()
        tts?.shutdown()
        pleosTts?.removeEventListener(pleosTtsListener)
        pleosTts?.release()
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

    /**
     * 내비를 앞으로 띄운다. Pleos 패널은 앱을 뒤로 보내기만 하면(moveTaskToBack) 마지막 화면이 남아 있어,
     * 내비 앱을 직접 앞으로 불러온다. 내비를 찾지 못하면 뒤로 보내기만 한다.
     */
    private fun showNavigation() {
        val navi = packageManager.getLaunchIntentForPackage(NAVI_PACKAGE)
        if (navi != null) {
            try {
                startActivity(navi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.w(TAG, "내비 앱을 띄우지 못함", e)
            }
        }
        moveTaskToBack(true)
    }

    private fun startPleosTts() {
        try {
            val t = PleosTts(this, Mode.ON_DEVICE)
            pleosTts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    t.addEventListener(pleosTtsListener)
                    t.setLocale(Locale.KOREA)
                    t.setAudioAttributes(alertAttributes)
                    pleosTtsReady = true
                    Log.i(TAG, "Pleos TTS 사용 가능 (기기 내 모드)")
                }
                override fun onFailure(type: ErrorType) {
                    Log.i(TAG, "Pleos TTS 사용 불가($type), 안드로이드 TTS로 대신함")
                }
            })
        } catch (e: Throwable) {
            Log.i(TAG, "Pleos TTS 초기화 실패, 안드로이드 TTS로 대신함", e)
        }
    }

    private fun startSpeed() {
        val c = car ?: Car.createCar(this).also { car = it }
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

    /** 1초마다 조회하는 값: 지원 여부 확인 전에는 시도하고(null), 미지원이면 조회하지 않는다. */
    @Volatile private var wiperSupported: Boolean? = null
    @Volatile private var chargeSwitchSupported: Boolean? = null

    private fun CapabilityConfig.readable() = accessType == AccessType.READ || accessType == AccessType.READ_WRITE

    /**
     * 차종마다 지원하는 신호가 다르므로(Pleos 문서: checkXxxCapabilities로 먼저 확인) 지원하는 것만 구독한다.
     * 확인 자체가 실패하면 일단 구독을 시도하고, 퍼미션이 없는 도메인이 있어도 나머지 신호는 계속 받는다.
     */
    private fun registerListeners() {
        fun subscribe(name: String, check: ((onResult: (Boolean) -> Unit) -> Unit)?, register: () -> Unit) {
            fun doRegister() = try {
                register()
            } catch (e: SecurityException) {
                fail(name, e)
            }
            if (check == null) return doRegister()
            check { supported ->
                if (supported) doRegister()
                else if (name == "안개등") fogUnsupported(IllegalStateException("capability NONE"))
                else update(name, "미지원 차량")
            }
        }
        // 확인이 실패하면(구버전 서비스 등) 지원한다고 보고 시도한다
        fun onCheckFailed(name: String): (Exception) -> Unit = { Log.w(TAG, "$name 지원 여부 확인 실패, 구독 시도", it) }
        subscribe("기어", null) { vehicle.getGear().registerGearSelection(gearListener) }
        subscribe("브레이크", { ok -> vehicle.getBrake().checkBrakePedalStatusCapabilities({ ok(it.readable()) }, { onCheckFailed("브레이크")(it); ok(true) }) }) {
            vehicle.getBrake().registerBrakePedalStatus(brakeListener)
        }
        subscribe("안개등", { ok -> vehicle.getLight().checkFrontFogLightStateCapabilities({ c, _ -> ok(c.readable()) }, { onCheckFailed("안개등")(it); ok(true) }) }) {
            vehicle.getLight().registerFrontFogLightState(fogListener)
        }
        subscribe("전조등", { ok -> vehicle.getLight().checkLowBeamLightStateCapabilities({ c, _ -> ok(c.readable()) }, { onCheckFailed("전조등")(it); ok(true) }) }) {
            vehicle.getLight().registerLowBeamLightState(lowBeamListener)
        }
        subscribe("디포그", { ok -> vehicle.getHVAC().checkHvacDefrosterCapabilities({ c, areas -> ok(c.readable() && areas.isNotEmpty()) }, { onCheckFailed("디포그")(it); ok(true) }) }) {
            vehicle.getHVAC().registerHvacDefrosterState(frontWindshield, defrostListener)
        }
        subscribe("경고등", { ok -> vehicle.getSafety().checkTelltalesCapabilities({ ok(it.readable()) }, { onCheckFailed("경고등")(it); ok(true) }) }) {
            vehicle.getSafety().registerTelltales(telltaleListener)
        }
        subscribe("방향지시등", { ok -> vehicle.getTurnSignal().checkTurnSignalStateCapabilities({ ok(it.readable()) }, { onCheckFailed("방향지시등")(it); ok(true) }) }) {
            vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener)
        }
        subscribe("배터리", { ok -> vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities({ ok(it.readable()) }, { onCheckFailed("배터리")(it); ok(true) }) }) {
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener)
        }
        vehicle.getWiper().checkWiperSwitchCapabilities({ c, areas ->
            wiperSupported = c.readable() && areas.isNotEmpty()
            if (wiperSupported == false) update("와이퍼", "미지원 차량")
        }, onCheckFailed("와이퍼"))
        vehicle.getEvBattery().checkEvChargeSwitchCapabilities({ chargeSwitchSupported = it.readable() }, onCheckFailed("충전 상태"))
        Log.i(TAG, "차량 신호 지원 여부 확인 요청 완료")
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

    // ---------- 알림 표시 원칙 (rev2 화면 개편) ----------
    // (2026-11-08) 앱이 앞에 있든 뒤에 있든 OS 상단 알림 한 가지로 보여 준다. 위치는 OS가 정하므로 짧게 띄운다.
    // 어느 쪽이든 소리·음성은 같다. Pleos는 오른쪽 패널에 한 앱만 보여 주므로, 주행을 시작하면 앱이 스스로 뒤로 물러나 내비를 보이게 한다.
    @Volatile private var inFront = false

    override fun onResume() {
        super.onResume()
        inFront = true
    }

    override fun onPause() {
        inFront = false
        super.onPause()
    }

    private fun popup(key: String, title: String, text: String, color: Int, ms: Long, onlyOnce: Boolean = false,
                      fg: Int = Color.WHITE, stripe: IntArray? = null, art: String? = null) {
        // 경고·안내는 앱이 앞에 있든 없든 OS(Pleos) 상단 알림 한 가지로 띄운다 (OS와 같은 모양, 아이콘만 HyperConnect)
        notifier.show(key, title, text, color, ms, onlyOnce, fg = fg, stripe = stripe, art = art)
    }

    /** 사고 정체 (2차 사고 방지 rev2): 멀리 있는 사고·정차 지점과 그 뒤 정체 꼬리를 짧은 카드로 알린다. */
    private fun showZone(w: RoadWarning) {
        val label = w.zoneLabel ?: "사고 정체"
        val text = if (w.type == "congestion") "전방 ${w.distanceM}m부터 $label${w.lengthM?.let { " · 길이 ${it}m" } ?: ""}"
        else "전방 ${w.distanceM}m $label"
        Log.i(TAG, "구간 알림: $text")
        runOnUiThread {
            noticeView.text = "🚧  $text"
            noticeView.visibility = View.VISIBLE
            ui.removeCallbacks(hideNotice)
            ui.postDelayed(hideNotice, NOTICE_SHOW_MS)
        }
        popup("notice", "🚧 $label", text, COLOR_NOTICE, NOTICE_SHOW_MS)
        refreshDetails()
        playTone(ToneGenerator.TONE_PROP_ACK, 150)
    }

    private val hideNotice = Runnable { noticeView.visibility = View.GONE }

    private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
    private fun hm(ms: Long) = clock.format(Date(ms))
    private var lastForecast: ChargingForecast? = null

    /**
     * 충전 예측 카드 (EV rev3): 두 줄로 줄였다 — 충전소·별점 / 도착 → 완료 시각과 대기.
     * 혼잡·별점 비교는 정차 중 충전소 지도에서 색과 별로 본다.
     */
    private fun showCharging(c: ChargingForecast) {
        val star = c.rating?.let { "★%.1f".format(it) } ?: "★ -"
        val wait = if (c.waitMin <= 0) "바로 충전" else "${c.ahead + 1}번째 · ${c.waitMin}분 대기"
        val line2 = "${hm(c.etaMs)} 도착 → ${hm(c.doneMs)} 완료 · $wait"
        Log.i(TAG, "충전 예측: ${c.station} $star / $line2 (실측 ${c.kwMeasured} ${c.kw}kW, 충전 중 ${c.chargingNetwork}+타사 ${c.chargingOther}, 오는 EV ${c.coming})")
        lastForecast = c
        val now = System.currentTimeMillis()
        val newTrip = tripStation != c.stationId
        if (newTrip) { tripStation = c.stationId; tripStartMs = now }
        val t = Trip(c.station, c.operator, c.rating, c.ratingN, c.kw, c.free, c.stalls, c.socArrival, c.known, c.estimated,
            kotlin.math.min(tripStartMs, c.etaMs), c.etaMs, c.startMs, c.doneMs)
        trip = t
        runOnUiThread {
            tripBar.trip = t; tripPill.trip = t
            renderInfoRows(); updateTripPill()
        }
        // 충전 링은 앱 안(이모트 바 위 알약·가로 막대)에서만 보인다. 내비 위 상단 알림으로는 띄우지 않는다
        //   (내비 위 상시 링은 Tier 2+ 컨셉 이미지: docs/concept)
    }

    /** 작은 창일 때만 이모트 바 위 알약 링을 보인다. 누르면 중간 크기(가로 막대)로. */
    private fun updateTripPill() {
        val show = trip != null && sheet.level == AssistantSheet.LEVEL_MINI && settings.view.visibility != View.VISIBLE
        val v = if (show) View.VISIBLE else View.GONE
        if (tripPill.visibility != v) tripPill.visibility = v
    }

    private fun clearTrip() {
        trip = null; tripStation = null; lastForecast = null
        runOnUiThread { tripBar.trip = null; tripPill.trip = null; renderInfoRows(); updateTripPill() }
    }

    /** 충전 카드도 다른 알림처럼 잠깐 떴다 사라진다. 계속 보려면 창을 키워 세부 정보에서 본다. */
    private fun popCharging() {
        chargingView.visibility = View.VISIBLE
        ui.removeCallbacks(hideCharging)
        ui.postDelayed(hideCharging, CHARGING_SHOW_MS)
    }
    private val hideCharging = Runnable { chargingView.visibility = View.GONE }

    /** 내 충전이 끝남: 기록이 충전소 정보가 되었음을 알리고, 정차 중이면 별점을 묻는다. */
    private fun onChargeDone(d: ChargeDone) = runOnUiThread {
        val text = "🔌  ${d.station} 충전 완료 · ${d.minutes}분 · ${d.socFrom}→${d.socTo}%\n이 기록이 다른 운전자의 충전 예측에 쓰여요"
        Log.i(TAG, "충전 완료: ${text.replace("\n", " / ")} (실측 ${d.kw}kW)")
        chargingView.text = text
        popCharging()
        clearTrip()
        pendingRating = d
        ratingReadyAtMs = SystemClock.elapsedRealtime() + INFO_ALERT_MS + 500  // 완료 알림이 사라진 뒤에 별점을 묻는다 (겹치지 않게)
        popup("charging", "🔌 ${d.station} 충전 완료", "${d.minutes}분 · ${d.socFrom}→${d.socTo}% · 이 기록이 다른 운전자의 예측에 쓰여요", COLOR_CHARGING, INFO_ALERT_MS)
        playTone(ToneGenerator.TONE_PROP_ACK, 150)
    }

    private fun answerRating(stars: Int) {
        val d = pendingRating ?: return
        ratingStars.forEachIndexed { i, v -> v.setTextColor(if (i < stars) TripView.STAR else TripView.STAR_EMPTY) }
        client?.sendRating(d.stationId, stars)
        Log.i(TAG, "별점 보냄: ${d.station} ★$stars")
        update("마지막 별점", "${d.station} ★$stars")
        pendingRating = null
        ui.postDelayed({ ratingBar.visibility = View.GONE }, 900)  // 채워진 별을 잠깐 보여 준 뒤 닫는다
        chargingView.text = "🔌  ${d.station} ★$stars 고마워요\n다음 운전자의 충전소 선택에 쓰여요"
        ui.removeCallbacks(hideCharging)
        chargingView.visibility = View.VISIBLE
        ui.postDelayed(hideCharging, 4000)
        mapTick = MAP_REFRESH_TICKS  // 지도 별점을 바로 다시 읽는다
    }

    /** 긴급차량 역할별 표현 (06 rev2): 색·아이콘·문구·사이렌음이 다르다. */
    private data class RoleStyle(val bg: Int, val fg: Int, val stripe: IntArray, val title: String, val tone: Int)

    private fun roleStyle(role: String) = when (role) {
        "police" -> RoleStyle(Color.rgb(29, 64, 175), Color.WHITE, intArrayOf(Color.rgb(239, 68, 68), Color.rgb(96, 165, 250)),
            "🚓  경찰차 긴급 출동", ToneGenerator.TONE_SUP_INTERCEPT)
        "fire" -> RoleStyle(Color.rgb(185, 28, 28), Color.WHITE, intArrayOf(Color.rgb(251, 146, 60), Color.rgb(250, 204, 21)),
            "🚒  소방차 출동", ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
        else -> RoleStyle(Color.rgb(250, 250, 250), Color.rgb(185, 28, 28), intArrayOf(Color.rgb(22, 163, 74), Color.rgb(220, 38, 38)),
            "🚑  구급차 · 환자 이송 중", ToneGenerator.TONE_CDMA_HIGH_L)
    }

    private val emergencies = mutableMapOf<String, Pair<EmergencyApproach, Long>>()
    private val announced = mutableSetOf<String>()

    /** 여러 대가 오면 가장 가까운 한 대를 보여 준다. 처음 접근할 때 역할별 사이렌음과 음성으로 한 번 알린다. */
    private fun showEmergency(e: EmergencyApproach) = runOnUiThread {
        val now = SystemClock.elapsedRealtime()
        if (e.passed) emergencies.remove(e.id) else emergencies[e.id] = e to now
        emergencies.entries.removeAll { now - it.value.second > 6000 }
        val near = emergencies.values.map { it.first }.minByOrNull { it.distanceM }
        ui.removeCallbacks(hideEmergency)
        if (near == null) {
            if (e.passed) {
                val s = roleStyle(e.role)
                styleEmergency(s, "${e.symbol}  ${e.label}가 지나갔어요", "")
                Log.i(TAG, "긴급차량: ${e.label}가 지나갔어요")
                notifier.cancel("emergency")
                ui.postDelayed(hideEmergency, 3000L)
            }
            return@runOnUiThread
        }
        val s = roleStyle(near.role)
        val action = if (near.sameLane) "오른쪽으로 비켜 길을 열어 주세요" else "차로를 유지하세요 · 끼어들지 마세요"
        styleEmergency(s, s.title, "뒤 ${near.distanceM}m · $action")
        refreshDetails()
        ui.postDelayed(hideEmergency, 6000L)
        // 상단 알림은 처음 다가올 때 한 번만 (거리 갱신마다 다시 띄우면 OS 알림 영역을 오래 가린다). 이후 안내는 음성과 앱 세부 정보로
        if (near.id !in announced) popup("emergency", s.title.replace("  ", " "), "뒤 ${near.distanceM}m · $action", s.bg, WARNING_SHOW_MS, fg = s.fg, stripe = s.stripe)
        if (announced.add(near.id)) {
            Log.i(TAG, "긴급차량: ${s.title.trim()} / 뒤 ${near.distanceM}m / $action")
            busyUntilMs = now + 6000
            speakAlert("뒤에서 ${near.label}가 다가옵니다. ${if (near.sameLane) "오른쪽으로 비켜 주세요" else "차로를 유지하세요"}", s.tone)
        }
    }

    private fun styleEmergency(s: RoleStyle, title: String, line: String) {
        emergencyCard.background = rounded(s.bg)
        emergencyStripe.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, s.stripe).apply { cornerRadius = 12f }
        emergencyTitle.setTextColor(s.fg)
        emergencyLine.setTextColor(s.fg)
        emergencyTitle.text = title
        emergencyLine.text = line
        emergencyLine.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        emergencyCard.visibility = View.VISIBLE
    }

    private val hideEmergency = Runnable {
        emergencyCard.visibility = View.GONE
        announced.retainAll(emergencies.keys)
    }

    /**
     * 도로 위험 종류별 카드 (rev5): 긴급차량처럼 종류마다 색·왼쪽 띠·제목을 따로 둔다.
     * 급제동은 어두운 빨강 바탕에 브레이크등(빨강)·비상등(주황) 띠 — 한눈에 '앞차가 섰다'로 읽히게.
     */
    private fun warningStyle(w: RoadWarning): RoleStyle {
        val tone = ToneGenerator.TONE_PROP_BEEP2
        return when {
            w.tail -> RoleStyle(Color.rgb(185, 28, 28), Color.WHITE, intArrayOf(Color.rgb(251, 146, 60), Color.rgb(250, 204, 21)), "🚧  사고 정체 끝", tone)
            w.type == EVENT_HARD_BRAKE -> RoleStyle(Color.rgb(127, 18, 18), Color.WHITE,
                intArrayOf(Color.rgb(255, 59, 48), Color.rgb(255, 176, 32)), "⚠  전방 급제동", tone)
            w.type == SecondaryAccidentDetector.EVENT_STOPPED -> RoleStyle(Color.rgb(185, 28, 28), Color.WHITE,
                intArrayOf(Color.rgb(250, 204, 21), Color.rgb(250, 204, 21)), "🚘  전방 정차 차량", tone)
            w.type == SecondaryAccidentDetector.EVENT_CRASH -> RoleStyle(Color.rgb(185, 28, 28), Color.WHITE,
                intArrayOf(Color.rgb(250, 204, 21), Color.rgb(17, 24, 39)), "💥  전방 사고", tone)
            w.type == "heavy_rain" -> RoleStyle(Color.rgb(30, 64, 175), Color.WHITE, intArrayOf(Color.rgb(147, 197, 253), Color.rgb(96, 165, 250)), "🌧  폭우 구간", tone)
            w.type == "fog" -> RoleStyle(Color.rgb(71, 85, 105), Color.WHITE, intArrayOf(Color.rgb(226, 232, 240), Color.rgb(148, 163, 184)), "🌫  안개 구간", tone)
            w.type == "slippery" -> RoleStyle(Color.rgb(14, 116, 144), Color.WHITE, intArrayOf(Color.rgb(165, 243, 252), Color.rgb(255, 255, 255)), "🧊  미끄러운 노면", tone)
            else -> RoleStyle(COLOR_WARNING, Color.WHITE, intArrayOf(Color.WHITE, Color.WHITE), "⚠  도로 위험", tone)
        }
    }

    private fun showWarning(w: RoadWarning) {
        if (w.type == "congestion") return showZone(w)
        // 멀리 있는 사고·정차 차량은 경고창 대신 구간 카드로 알리고, 가까워졌을 때만 빨간 경고를 띄운다
        if (w.type in FAR_NOTICE_TYPES && w.distanceM > RED_ALERT_M) {
            return showZone(w.copy(zoneLabel = if (w.type == SecondaryAccidentDetector.EVENT_CRASH) "사고 발생" else "정차 차량"))
        }
        val where = if (w.distanceM > 0) "전방 ${w.distanceM}m" else "현재 구간"
        val message = when {
            w.tail -> "$where 사고 정체 끝, 주의하세요"
            w.type == EVENT_HARD_BRAKE -> "$where 급제동 발생, 주의하세요"
            w.type == SecondaryAccidentDetector.EVENT_STOPPED ->
                if (w.lane == "same") "$where 같은 차로에 정차 차량, 차로를 변경하세요" else "$where 정차 차량, 주의하세요"
            w.type == SecondaryAccidentDetector.EVENT_CRASH -> "$where 사고 발생, 서행하세요"
            // 기상 (rev2): 관측 대수는 운전자에게 의미가 없어 문구에서 뺐다
            w.type == "heavy_rain" -> "$where 폭우 구간, 주의하세요"
            w.type == "fog" -> "$where 안개 구간, 안개등을 켜세요"
            w.type == "slippery" -> "$where 미끄러운 노면, 주의하세요"
            else -> "$where ${w.type}"
        }
        Log.i(TAG, "경고 수신: $message (차로 ${w.lane}, 보고 차량 ${w.reporters}대)")
        val st = warningStyle(w)
        // 카드: 제목은 위험 종류, 둘째 줄은 거리와 할 일 ("166m 앞 · 주의하세요")
        val line = (if (w.distanceM > 0) "${w.distanceM}m 앞" else "지금 이 구간") + " · " + message.substringAfter(", ")
        runOnUiThread {
            warningView.background = rounded(st.bg)
            warningStripe.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, st.stripe).apply { cornerRadius = 12f }
            warningTitle.setTextColor(st.fg); warningLine.setTextColor(st.fg)
            warningTitle.text = st.title
            warningLine.text = line
            warningView.visibility = View.VISIBLE
            ui.removeCallbacks(hideWarning)
            ui.postDelayed(hideWarning, WARNING_SHOW_MS)
        }
        popup("warning", st.title.replace("  ", " "), line, st.bg, WARNING_SHOW_MS, fg = st.fg, stripe = st.stripe,
            art = if (w.type == EVENT_HARD_BRAKE && !w.tail) AlertNotifier.ART_BRAKE else null)
        refreshDetails()
        speakAlert(message, ToneGenerator.TONE_PROP_BEEP2)
    }

    /** 경고음 뒤 음성 안내. Pleos TTS를 먼저 쓰고, 안 되면 안드로이드 TTS. */
    private fun speakAlert(message: String, toneType: Int) {
        withAudioFocus(if (pleosTtsReady || ttsReady) WARNING_VOICE_MAX_MS else 800L) {
            tone.startTone(toneType, 600)
            if (pleosTtsReady) {
                // Pleos TTS는 묵음 데이터를 앞에 붙일 수 없어 재생 시작을 묵음 길이만큼 늦춘다
                handler.postDelayed({ pleosTts?.speak(message, true) }, TTS_PREROLL_MS + 600)
            } else if (ttsReady) {
                // 음성 안내 앞에 짧은 묵음을 넣어 첫 음절이 잘리지 않게 한다 (Pleos 음성 안내 선행 묵음 가이드)
                tts?.playSilentUtterance(TTS_PREROLL_MS + 600, TextToSpeech.QUEUE_FLUSH, null)
                tts?.speak(message, TextToSpeech.QUEUE_ADD, null, UTTERANCE_WARNING)
            }
        }
    }

    private val hideWarning = Runnable { warningView.visibility = View.GONE }

    private fun playTone(type: Int, durationMs: Int) = withAudioFocus(durationMs + 200L) { tone.startTone(type, durationMs) }

    /** 포커스를 받은 경우에만 소리를 낸다. 거부되면(통화·긴급음 등) 소리를 생략하고 화면 표시만 남는다. */
    private fun withAudioFocus(holdMs: Long, play: () -> Unit) {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(alertAttributes)
            .setOnAudioFocusChangeListener(::onAudioFocusChange, handler)
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "오디오 포커스 거부: 소리 생략")
            return
        }
        focusRequest = request
        play()
        handler.removeCallbacks(releaseFocus)
        handler.postDelayed(releaseFocus, holdMs)
    }

    /** 다른 소리(통화, 음성 비서 등)가 포커스를 가져가면 우리 소리를 바로 멈추고 포커스를 돌려준다. */
    private fun onAudioFocusChange(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            Log.i(TAG, "오디오 포커스 상실($change): 소리 중단")
            tone.stopTone()
            tts?.stop()
            pleosTts?.stop()
            sounds.autoPause()
            releaseFocus.run()
        }
    }

    private val releaseFocus = Runnable {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** 이모트 수신: 꺼져 있으면 무시, 바쁜 순간이면 미뤘다가, 주행 중이면 소리만 들려준다. */
    private fun showEmote(e: Emote) = runOnUiThread {
        if (!prefs.receiveEmotes) return@runOnUiThread
        if (isBusy()) {
            Log.i(TAG, "이모트 보류 (경고·차로 변경 중): ${e.symbol}")
            pendingEmotes.addLast(e)
        } else {
            playEmote(e)
        }
    }

    private fun playSound(pack: String, name: String) {
        val id = emoteSounds["$pack:$name"] ?: emoteSounds["basic:$name"] ?: return
        withAudioFocus(EMOTE_SOUND_MS) { sounds.play(id, 1f, 1f, 1, 0, 1f) }
    }

    private fun playEmote(e: Emote) {
        val who = if (e.fromAhead) "앞차" else "뒤차"
        val caption = if (e.pack == "emergency") "지나간 긴급차량이 길을 열어 줘서 고맙대요" else "$who${EMOTE_RECEIVED[e.name] ?: ""}"
        playSound(e.pack, e.name)
        if (isSlow() && inFront) {
            Log.i(TAG, "이모트 수신: ${e.symbol} $caption · ${e.packName} 팩")
            animateEmote(e, caption)
        } else {
            // 주행 중이거나 내비가 앞에 있으면 화면을 띄우지 않는다(상단 알림도 쓰지 않음).
            // 소리만 들려주고, 정차 중 앱이 앞에 오면 모아서 보여준다
            Log.i(TAG, "이모트 수신 (소리만): ${e.symbol} $caption · ${e.packName} 팩")
            unseenEmotes += e.symbol
        }
    }

    /** 이모트 카드 (rev2): 팩 색 그라데이션 위에서 그림이 튀어 오르고, 팩 모양 조각이 흩날린다. 정차·저속일 때만. */
    private fun animateEmote(e: Emote, caption: String) {
        val pack = EmotePacks.of(e.pack)
        emoteCard.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pack.colorTop, pack.colorBottom)).apply { cornerRadius = 48f }
        emoteEmoji.text = e.symbol
        emoteCaption.text = caption
        emotePackLabel.text = if (e.pack == "basic") "" else "♦ ${pack.name} 팩"
        emoteCard.visibility = View.VISIBLE
        emoteCard.alpha = 0f
        emoteCard.animate().alpha(1f).setDuration(180).start()
        emoteEmoji.scaleX = 0.2f; emoteEmoji.scaleY = 0.2f; emoteEmoji.rotation = -25f
        emoteEmoji.animate().scaleX(1f).scaleY(1f).rotation(0f).setInterpolator(OvershootInterpolator(3f)).setDuration(650).start()
        // 흩날리는 조각
        repeat(10) { i ->
            val p = TextView(this).apply {
                text = pack.particles[i % pack.particles.size]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f + (i % 3) * 10)
                setTextColor(Color.WHITE)
                alpha = 0.9f
            }
            emoteParticles.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
            val angle = Math.toRadians(i * 36.0 + 10)
            p.animate().translationX((Math.cos(angle) * 360).toFloat()).translationY((Math.sin(angle) * 220 - 60).toFloat())
                .alpha(0f).rotation(180f).setStartDelay(120L + i * 25).setDuration(1100)
                .withEndAction { emoteParticles.removeView(p) }.start()
        }
        emoteCard.removeCallbacks(hideEmote)
        emoteCard.postDelayed(hideEmote, EMOTE_SHOW_MS)
    }

    private fun showEmoteBanner(text: String) {
        emoteCard.background = rounded(Color.rgb(30, 140, 80), 48f)
        emoteEmoji.text = ""
        emoteCaption.text = text
        emotePackLabel.text = ""
        emoteCard.alpha = 1f
        emoteCard.visibility = View.VISIBLE
        emoteCard.removeCallbacks(hideEmote)
        emoteCard.postDelayed(hideEmote, EMOTE_SHOW_MS)
    }

    private fun isSlow() = speedKph <= SLOW_KPH

    /** 경고가 떠 있거나 급제동·차로 변경 중이면 바쁜 순간: 이모트·피드백을 미룬다. */
    private fun isBusy() = warningView.visibility == View.VISIBLE || emergencyCard.visibility == View.VISIBLE ||
        laneView.visibility == View.VISIBLE || turnSignal != null || SystemClock.elapsedRealtime() < busyUntilMs

    /** 차로 변경 의도: 진입하려는 쪽이면 주의, 내 차로로 들어오려는 차가 있으면 미리 알림. */
    private fun showLane(n: LaneNotice) = runOnUiThread {
        // 진입하는 차가 왼쪽 깜빡이를 켰다면, 뒤에서 오는 차 입장에서는 오른쪽 옆에서 들어온다
        val left = (n.side == "LEFT") == n.merging
        val side = if (left) "왼쪽" else "오른쪽"
        // 표현 (2026-11-08 사용자 결정 1안): "끼어들기"는 쓰지 않고, 제목에서 누가 하는지가 드러나게
        //   내가 들어갈 때 "차로 변경 주의" / 옆 차가 들어올 때 "옆 차 합류 예정"
        val text = if (n.merging) "$side 차로 뒤 ${n.distanceM}m 차량 접근"
        else "$side 옆 차가 내 차로로 들어오려 해요"
        Log.i(TAG, "차로 변경 알림: ${if (n.merging) "⚠" else "↪"}  $text")
        laneView.text = "${if (n.merging) "⚠" else "↪"}  $text"
        laneView.visibility = View.VISIBLE
        ui.removeCallbacks(hideLane)
        ui.postDelayed(hideLane, LANE_SHOW_MS)
        popup("lane", if (n.merging) "⚠ 차로 변경 주의" else "↪ 옆 차 합류 예정", text, COLOR_LANE, LANE_SHOW_MS)
        busyUntilMs = SystemClock.elapsedRealtime() + LANE_SHOW_MS
        playTone(ToneGenerator.TONE_PROP_BEEP, 300)
    }

    private val hideLane = Runnable { laneView.visibility = View.GONE }

    /** 진입 성공: 자동 감사 인사는 이미 서버가 보냈다. 꺼져 있으면 정차·저속일 때만 버튼을 준다. */
    private fun onMergeDone(auto: Boolean) = runOnUiThread {
        if (auto) {
            update("마지막 이모트", "👍 자동 감사 인사 · 양보해 준 뒤차에게 보냈어요")
            playTone(ToneGenerator.TONE_PROP_ACK, 120)
        } else if (prefs.emoteButtons && isSlow()) {
            mergeThanksButton.visibility = View.VISIBLE
            mergeThanksButton.removeCallbacks(hideMergeThanks)
            mergeThanksButton.postDelayed(hideMergeThanks, MERGE_THANKS_SHOW_MS)
        } else {
            Log.i(TAG, "진입 성공 (주행 중이라 감사 버튼을 띄우지 않음)")
        }
    }

    private val hideMergeThanks = Runnable { mergeThanksButton.visibility = View.GONE }

    private var mapTick = 0

    /** 1초마다: 저속일 때만 조작 요소·지도를 보여주고, 미룬 이모트와 피드백 질문을 처리한다. */
    private fun updateDriverUi(now: Long) {
        val slow = isSlow()
        emoteBar.visibility = if (prefs.emoteButtons && slow) View.VISIBLE else View.GONE
        sheet.compact = emoteBar.visibility != View.VISIBLE
        tripPill.translationY = if (sheet.compact) (AssistantSheet.MINI_H - AssistantSheet.COMPACT_H).toFloat() else 0f
        if (!slow) mergeThanksButton.visibility = View.GONE
        if (!isBusy()) pendingEmotes.removeFirstOrNull()?.let { playEmote(it) }
        if (slow && inFront && unseenEmotes.isNotEmpty() && emoteCard.visibility != View.VISIBLE) {
            Log.i(TAG, "정차 후 이모트 표시: ${unseenEmotes.joinToString(" ")}")
            val seen = "주행 중 받은 이모트  ${unseenEmotes.joinToString(" ")}"
            showEmoteBanner(seen)
            unseenEmotes.clear()
        }
        if (pendingRating != null) ratingBar.visibility =
            if (slow && !isBusy() && SystemClock.elapsedRealtime() >= ratingReadyAtMs) View.VISIBLE else View.GONE
        // 전체 화면은 정차 중에만: 움직이면 중간 크기까지
        sheet.maxLevel = if (slow || prefs.drivingView) AssistantSheet.LEVEL_FULL else AssistantSheet.LEVEL_MID
        if (!slow && store.view.visibility == View.VISIBLE) store.view.visibility = View.GONE
        surround.drivingPreview = prefs.drivingView && !slow
        if (surround.visibility == View.VISIBLE) client?.fetchSurroundings { s -> if (s != null) runOnUiThread { surround.update(s) } }
        // 세부 정보는 창을 키웠을 때만 서버에 묻는다
        if (sheet.level != AssistantSheet.LEVEL_MINI && ++mapTick >= MAP_REFRESH_TICKS) refreshDetails()
    }

    /** 창을 키웠거나 새 알림이 왔을 때 1초 주기를 기다리지 않고 세부 정보를 바로 다시 읽는다. */
    private fun refreshDetails() {
        runOnUiThread {
            if (sheet.level == AssistantSheet.LEVEL_MINI) return@runOnUiThread
            mapTick = 0
            client?.fetchNearby { n -> runOnUiThread { nearby = n; renderInfoRows() } }
            if (lastForecast != null || pendingRating != null) client?.fetchChargers { m -> m?.let { runOnUiThread { mapView.data = it } } }
        }
    }

    private var nearby: NearbyInfo? = null
    private val infoRowViews = mutableMapOf<String, TextView>()

    private fun infoRow(key: String, text: String) {
        val v = infoRowViews.getOrPut(key) {
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(Color.rgb(35, 42, 54))
                setPadding(8, 10, 8, 10)
                infoRows.addView(this)
            }
        }
        if (v.text.toString() != text) v.text = text
    }

    /**
     * 전체 화면(정차 중): 창이 투명해지고 배경에 연결망 시각화가 깔린다. 위쪽 제목 줄과 아래 이모트 바만 카드로 떠 있다.
     * 그 밖의 크기: 흰 창에 세부 정보.
     */
    private fun applyLevel(level: Int) {
        val full = level == AssistantSheet.LEVEL_FULL
        surround.visibility = if (full) View.VISIBLE else View.GONE
        surround.running = full
        sheet.glass = full
        infoRows.visibility = if (full) View.GONE else View.VISIBLE
        val card = { v: View -> v.background = if (full) rounded(Color.argb(235, 255, 255, 255), 36f) else null; v.elevation = if (full) 12f else 0f }
        card(topBar); card(emoteBar)
        storeButton.visibility = if (full) View.VISIBLE else View.GONE
        if (!full) store.view.visibility = View.GONE
        if (full) {
            // 제목 줄·이모트 바 높이만큼 위아래를 비워 그림이 가리지 않게
            surround.insetTop = (AssistantSheet.MARGIN + topBar.bottom + 30).toFloat()
            surround.insetBottom = (AssistantSheet.MARGIN + emoteBar.height + 40).toFloat()
            client?.fetchSurroundings { s -> if (s != null) runOnUiThread { surround.update(s) } }
        }
    }

    private fun renderInfoRows() {
        val n = nearby
        fun list(items: List<String>?, none: String) = items?.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: none
        infoRow("weather", "🌦  기상  ${list(n?.weather, "주변에 확정된 기상 위험 없음")}")
        infoRow("accident", "🚧  사고·정체  ${list(n?.accidents, "주변에 알려진 사고 없음")}")
        infoRow("emergency", "🚨  긴급차량  ${list(n?.emergency, "접근 중인 차량 없음")}")
        val c = lastForecast
        infoRow("charging", "⚡  충전  " + if (c == null) "목적지 충전소 없음"
            else "${c.station} · ${hm(c.etaMs)} 도착 → ${hm(c.doneMs)} 완료" + if (c.waitMin > 0) " · ${c.waitMin}분 대기" else " · 바로 충전")
        infoRow("nearby", "🛰  주변 연결 차량  ${n?.connected?.let { "${it}대 (1km 안)" } ?: "-"}")
        // 충전 여정 막대는 중간 크기에서 (EV rev4: 지도 대신). 가장 크게 키우면 배경이 연결망 시각화로 바뀐다
        val showMap = sheet.level == AssistantSheet.LEVEL_MID && trip != null
        mapView.visibility = View.GONE
        tripBar.visibility = if (showMap) View.VISIBLE else View.GONE
        // 지도가 보일 때는 충전 줄만 남겨 지도를 크게
        infoRowViews.forEach { (k, v) -> val vis = if (showMap && k != "charging") View.GONE else View.VISIBLE; if (v.visibility != vis) v.visibility = vis }
    }


    /** 이모트 상점: 정차 중 전체 화면에서만. 주행을 시작하면 닫힌다. */
    private fun openStore() {
        if (!isSlow()) {
            Toast.makeText(this, "정차 중에 열 수 있어요", Toast.LENGTH_SHORT).show()
            return
        }
        store.view.visibility = View.VISIBLE
        refreshStore()
    }

    private fun refreshStore() {
        client?.fetchStore { info ->
            runOnUiThread {
                if (info == null) return@runOnUiThread
                // 가진 팩이 아니면(지급 조건이 풀렸거나 이전 시연 값) 기본으로 돌린다
                if (prefs.emotePack !in info.owned) { prefs.emotePack = "basic"; paintEmoteButtons() }
                store.show(info.copy(equipped = prefs.emotePack))
            }
        }
    }

    /** 설정·내 기여 화면은 플랫폼 주행 규제상 주차(P) 상태에서만 연다. */
    private fun openSettings() {
        if (setupRestricted || !isSlow()) {
            Toast.makeText(this, "주차(P) 상태에서 열 수 있어요", Toast.LENGTH_SHORT).show()
            return
        }
        settings.view.visibility = View.VISIBLE
        render()
        client?.fetchStats { s -> runOnUiThread { settings.showStats(s) } }
    }

    // 시연용: 차로는 입력값이라 adb 브로드캐스트로 바꾼다 (실차는 카메라 차로 인식, Tier 2)
    //   adb shell am broadcast --user 10 -a com.hyperconnect.app.SET_LANE --ei lane 1
    private val laneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_ROUTE) {
                Log.i(TAG, "시연 정리: 경로 안내 종료")
                naviTracker?.cancelRoute()
                return
            }
            val newLane = intent.getIntExtra("lane", 0).takeIf { it > 0 } ?: return
            lane = newLane
            position.setRightOffset((newLane - BASE_LANE) * LANE_WIDTH_M)
            update("차로", "${newLane}차로")
        }
    }

    private fun registerLaneReceiver() {
        val filter = IntentFilter(ACTION_SET_LANE).apply { addAction(ACTION_CANCEL_ROUTE) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(laneReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(laneReceiver, filter)
        }
    }

    private val hideEmote = Runnable { emoteCard.visibility = View.GONE }

    private fun rounded(color: Int, radius: Float = 28f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    // 정차 중 한 번 탭으로 보낼 수 있도록 큰 버튼 세 개만 둔다. 버튼 색은 내 이모트 팩 색
    private val emoteButtons = mutableListOf<Button>()

    private fun emoteButton(emoji: String, word: String, name: String, toAhead: Boolean) = Button(this).apply {
        val target = if (toAhead) "▲ 앞차" else "▼ 뒤차"
        val label = "$emoji $word · ${target.drop(2)}에게"
        // 접힌 시트는 버튼 높이가 한 줄뿐이라 받는 차를 같은 줄 뒤에 작게 붙인다
        text = android.text.SpannableString("$emoji $word  $target").apply {
            val from = length - target.length
            setSpan(android.text.style.RelativeSizeSpan(0.8f), from, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(Color.argb(210, 255, 255, 255)), from, length, 0)
        }
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setPadding(18, 22, 18, 22)
        setOnClickListener {
            client?.sendEmote(name, toAhead) { result -> update("마지막 이모트", "$label · $result") }
            animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 10, 0) }
        emoteButtons += this
    }

    private fun paintEmoteButtons() {
        val pack = EmotePacks.of(prefs.emotePack)
        emoteButtons.forEach { it.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pack.colorTop, pack.colorBottom)).apply { cornerRadius = 36f } }
    }

    private fun card(bg: Int, sp: Float, fg: Int = Color.WHITE) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(fg)
        background = rounded(bg)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(40, 28, 40, 28)
        setLineSpacing(0f, 1.1f)
        visibility = View.GONE
        elevation = POP_ELEVATION
        isClickable = true  // 카드를 눌러도 뒤의 빈 곳 동작이 실행되지 않게
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 14, 0, 0) }
    }

    /** 왼쪽 색 띠 + 제목 + 한 줄 카드 (긴급차량·도로 위험). */
    private fun stripeCard(stripe: View, title: TextView, line: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(24, 24, 40, 24)
        visibility = View.GONE
        elevation = POP_ELEVATION
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 14 }
        addView(stripe, LinearLayout.LayoutParams(22, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = 32 })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(line)
        })
    }

    /** 알림 카드가 뿅 하고 튀어나왔다가 사라지는 움직임. */
    private fun popTransition() = LayoutTransition().apply {
        setDuration(LayoutTransition.APPEARING, 260)
        setDuration(LayoutTransition.DISAPPEARING, 180)
        setStartDelay(LayoutTransition.APPEARING, 0)
        setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0)
        setInterpolator(LayoutTransition.APPEARING, OvershootInterpolator(1.6f))
        setAnimator(LayoutTransition.APPEARING, ObjectAnimator.ofPropertyValuesHolder(null as Any?,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)))
    }

    /**
     * 화면 (어시스턴트 개편): 창 전체는 투명하고, 아래쪽 이모트 바([AssistantSheet])만 작게 떠 있다.
     * 알림은 위쪽에 잠깐 떴다 사라지고, 세부 정보(기상·사고·긴급차량·충전·지도)는 바를 끌어 올렸을 때만 보인다.
     * 빈 곳을 누르면 내비로 돌아간다. (Pleos는 오른쪽 패널에 앱 하나만 보여 주므로 내비 위에 떠 있을 수는 없다)
     */
    private fun buildUi() {
        warningStripe = View(this)
        warningTitle = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f); typeface = Typeface.DEFAULT_BOLD }
        warningLine = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f); setPadding(0, 8, 0, 0) }
        warningView = stripeCard(warningStripe, warningTitle, warningLine)
        laneView = card(COLOR_LANE, 34f)
        noticeView = card(COLOR_NOTICE, 34f)
        chargingView = card(COLOR_CHARGING, 30f).apply {
            setOnClickListener { mapTick = MAP_REFRESH_TICKS }
        }
        emergencyStripe = View(this)
        emergencyTitle = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f); typeface = Typeface.DEFAULT_BOLD }
        emergencyLine = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f); setPadding(0, 8, 0, 0) }
        emergencyCard = stripeCard(emergencyStripe, emergencyTitle, emergencyLine)
        statusChip = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.rgb(90, 100, 115))
            setPadding(28, 0, 0, 0)
        }
        mapView = ChargerMapView(this).apply {
            visibility = View.GONE
            background = rounded(Color.rgb(22, 26, 34), 28f)
            clipToOutline = true
        }
        emoteEmoji = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 120f); gravity = Gravity.CENTER }
        emoteCaption = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        emotePackLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTextColor(Color.argb(220, 255, 255, 255)); gravity = Gravity.CENTER
        }
        emoteParticles = FrameLayout(this)
        emoteCard = FrameLayout(this).apply {
            visibility = View.GONE
            minimumHeight = 380  // 조각이 흩날릴 공간
            elevation = POP_ELEVATION + 4f
            addView(emoteParticles, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 24, 48, 40)
                addView(emoteEmoji)
                addView(emoteCaption)
                addView(emotePackLabel)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 10, 0) }
        }
        fun bar() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(36, 41, 52))
            setPadding(40, 14, 28, 14)
            visibility = View.GONE
            elevation = POP_ELEVATION
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 14 }
        }
        ratingBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(245, 255, 255, 255), 40f)
            setPadding(44, 18, 32, 18)
            visibility = View.GONE
            elevation = POP_ELEVATION
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 14 }
            addView(TextView(this@MainActivity).apply {
                text = "이 충전소 어땠나요?"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(TripView.INK)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            for (n in 1..5) addView(TextView(this@MainActivity).apply {
                text = "★"
                contentDescription = "별 ${n}개"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
                setTextColor(TripView.STAR_EMPTY)
                setPadding(10, 0, 10, 0)
                setOnClickListener { answerRating(n) }
            }.also { ratingStars += it })
        }
        mergeThanksButton = Button(this).apply {
            text = "👍  양보해 준 뒤차에게 고마워요"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            visibility = View.GONE
            setOnClickListener {
                client?.sendEmote("thanks", toAhead = false) { r -> update("마지막 이모트", "👍 양보 감사 · $r") }
                visibility = View.GONE
            }
        }
        emoteBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 2, 16, 22)
            // 순서·문구는 컨셉 이미지(docs/concept)와 같게: 표현 + 받는 차
            addView(emoteButton("👍", "고마워요", "thanks", toAhead = true))
            addView(emoteButton("🙏", "미안해요", "sorry", toAhead = false))
            addView(emoteButton("👍", "고마워요", "thanks", toAhead = false))
        }
        paintEmoteButtons()
        offlineView = TextView(this).apply {
            text = "📡  서버 연결 끊김 · 재연결 중 (지금은 경고를 받을 수 없어요)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(90, 90, 100))
            gravity = Gravity.CENTER
            setPadding(32, 12, 32, 12)
            visibility = View.GONE
        }
        gearButton = Button(this).apply {
            text = "⚙"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(Color.rgb(60, 68, 82))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openSettings() }
        }
        storeButton = Button(this).apply {
            text = "🛍 상점"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.rgb(37, 99, 235))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { openStore() }
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(36, 0, 20, 4)
            addView(TextView(this@MainActivity).apply {
                text = "HyperConnect"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setTextColor(Color.rgb(22, 50, 79))
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(statusChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(storeButton)
            addView(gearButton)
        }
        // 잠깐 떴다 사라지는 것들: 위쪽 가운데에 쌓인다
        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = popTransition()
            elevation = POP_ELEVATION
            isClickable = true  // 카드 사이 틈을 눌러도 내비로 넘어가지 않게
            outlineProvider = null  // 줄 자체에는 그림자가 없고 카드마다 둥근 그림자를 그린다
            clipChildren = false
            clipToPadding = false
            setPadding(24, 0, 24, 40)  // 카드 그림자가 잘리지 않을 여백
            // 경고·긴급차량·차로·구간·충전 안내는 OS 상단 알림으로만 보인다. 이 카드들은 화면에 붙이지 않고
            // '지금 알림 중' 상태(이모트를 미루는 기준)로만 쓴다
            addView(offlineView); addView(ratingBar); addView(mergeThanksButton)
        }
        infoRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 6, 36, 10) }
        tripBar = TripView(this, TripView.Mode.BAR).apply { visibility = View.GONE }
        tripPill = TripView(this, TripView.Mode.PILL).apply {
            visibility = View.GONE
            elevation = 14f
            setOnClickListener { sheet.snapTo(AssistantSheet.LEVEL_MID) }
        }
        surround = SurroundView(this).apply { visibility = View.GONE }
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar)
            addView(infoRows)
            addView(tripBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 260).apply { setMargins(40, 8, 40, 8) })
            addView(mapView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(24, 0, 24, 14) })
        }
        sheet = AssistantSheet(this, detail, emoteBar) { level ->
            applyLevel(level)
            updateTripPill()
            if (level != AssistantSheet.LEVEL_MINI) { renderInfoRows(); render(); refreshDetails() }
        }
        renderInfoRows()
        settings = SettingsPanel(this, prefs, onClose = { settings.view.visibility = View.GONE }, onWithdraw = ::withdrawConsent)
        consent = ConsentView(this, onAgree = {
            prefs.consented = true
            startSharing()
        }, onDecline = { update("서버", "공유 안 함 (동의하지 않음)") })
        val inset = { FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { setMargins(48, 36, 48, 36) } }
        val darkPanel = { v: View -> v.background = rounded(Color.rgb(20, 23, 30), 40f); v.clipToOutline = true }
        darkPanel(settings.view); darkPanel(consent.view)
        store = StorePanel(this,
            onPreview = { id -> playSound(id, "thanks") },
            onEquip = { id ->
                prefs.emotePack = id
                paintEmoteButtons()
                playSound(id, "thanks")
                Log.i(TAG, "이모트 팩 장착: ${EmotePacks.of(id).name}")
                Handler(Looper.getMainLooper()).postDelayed({ refreshStore() }, 1200)  // 서버가 새 팩을 받은 뒤 다시 읽는다
            },
            onBuy = { id -> client?.buyPack(id) { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } } },
            onLocked = { why -> Toast.makeText(this, "특별 지급 · $why", Toast.LENGTH_LONG).show() },
            onClose = { store.view.visibility = View.GONE })
        store.view.background = rounded(Color.rgb(248, 250, 252), 40f); store.view.clipToOutline = true
        setContentView(FrameLayout(this).apply {
            // 빈 곳을 누르면 내비로 돌아간다
            setOnClickListener { if (settings.view.visibility != View.VISIBLE && consent.view.visibility != View.VISIBLE && store.view.visibility != View.VISIBLE) showNavigation() }
            addView(surround, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(sheet, FrameLayout.LayoutParams(AssistantSheet.MINI_W, AssistantSheet.MINI_H, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = AssistantSheet.MARGIN })
            addView(tripPill, FrameLayout.LayoutParams(TRIP_PILL_W, TRIP_PILL_H, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = AssistantSheet.MARGIN + AssistantSheet.MINI_H + 14
            }.also { tripPill.translationX = -(AssistantSheet.MINI_W - TRIP_PILL_W) / 2f })
            addView(emoteCard, FrameLayout.LayoutParams(900, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = AssistantSheet.MINI_H + 56 })
            addView(cards, FrameLayout.LayoutParams(AssistantSheet.MINI_W + 48, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 20 })
            addView(settings.view, inset())
            addView(store.view, inset())
            addView(consent.view, inset())
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

    /** 위쪽 상태 줄은 운전자에게 필요한 것만, 차량 신호 전체는 설정 안의 진단 화면에. */
    private fun render() {
        val server = signals["서버"] ?: "-"
        val dot = if (server == "연결됨") "●" else "○"
        // 값이 그대로면 다시 그리지 않고, 보이지 않는 화면은 건드리지 않는다 (정차 중 불필요한 그리기 방지)
        if (sheet.level != AssistantSheet.LEVEL_MINI) {
            val status = "$dot $server · 차량 $vehicleId · ${signals["차로"]} · ${signals["속도"]}"
            if (statusChip.text.toString() != status) statusChip.text = status
        }
        if (settings.view.visibility == View.VISIBLE) settings.showDiagnostics(signals.entries.joinToString("\n") { (k, v) -> "$k : $v" })
    }

    companion object {
        private const val TAG = "HyperConnect"
        private const val EVENT_HARD_BRAKE = "hard_brake"
        private const val EVENT_WEATHER_OBS = "weather_obs"
        private const val REQUEST_SPEED = 1
        private const val TICK_MS = 1000L
        // 상단 알림은 OS 공용 영역을 가리므로 짧게: 위험 4초, 안내 5초 (음성이 함께 나온다)
        private const val WARNING_SHOW_MS = 4000L
        private const val TRIP_PILL_W = 440
        private const val TRIP_PILL_H = 128
        private const val INFO_ALERT_MS = 5000L
        private const val EMOTE_SHOW_MS = 3000L
        private const val NOTICE_SHOW_MS = 5000L
        private const val SLOW_KPH = 5f               // 플랫폼 주행 규제가 켜지는 기준(5km/h 초과)에 맞춘다
        private const val TTS_PREROLL_MS = 180L        // 음성 안내 선행 묵음 (가이드 권장 150~200ms)
        private const val WARNING_VOICE_MAX_MS = 8000L // 음성 경고 중 포커스 최대 유지 시간 (끝나면 바로 반납)
        private const val EMOTE_SOUND_MS = 1500L
        private const val UTTERANCE_WARNING = "warning"
        private const val BUSY_HOLD_MS = 3000L        // 급제동 뒤 이 시간 동안 이모트를 미룬다
        private const val LANE_SHOW_MS = 5000L
        private const val MERGE_THANKS_SHOW_MS = 5000L
        private const val ACTION_SET_LANE = "com.hyperconnect.app.SET_LANE"
        private const val ACTION_CANCEL_ROUTE = "com.hyperconnect.app.CANCEL_ROUTE"  // 시연용: 경로 안내 종료
        private const val RED_ALERT_M = 300
        private const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
        private const val MAP_REFRESH_TICKS = 3
        private const val CHARGING_SHOW_MS = 8000L
        private const val POP_ELEVATION = 40f
        private val COLOR_WARNING = Color.rgb(200, 30, 30)
        private val COLOR_LANE = Color.rgb(126, 58, 160)
        private val COLOR_NOTICE = Color.rgb(196, 112, 16)
        private val COLOR_CHARGING = Color.rgb(15, 100, 112)
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
