package com.hyperconnect.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** 내비에서 얻은 차량 위치. */
data class VehiclePosition(val lat: Double, val lon: Double, val heading: Double, val address: String?)

/**
 * NaviHelper로 현재 위치·다음 안내(TBT)·목적지를 주기적으로 조회한다.
 * NaviHelper의 get*Info()는 결과를 리스너 콜백으로 돌려준다.
 */
class NaviTracker(
    context: Context,
    private val onPosition: (VehiclePosition) -> Unit,
    private val onNextTurn: (TBTInfo?) -> Unit,
    private val onDestination: (DestinationInfo?) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val navi = NaviHelper(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var connected = false
    private var pendingRoute: RouteInfo? = null
    private var stopped = false
    private val retry = Runnable { if (!stopped && !connected) start() }

    /** 진행 중인 경로 안내를 끝낸다 (시연 정리용). */
    fun cancelRoute() = try {
        navi.cancelRoute()
    } catch (e: Exception) {
        Log.w(TAG, "경로 취소 실패", e)
    }

    /** 목적지로 경로 안내를 요청한다. 아직 연결 전이면 연결 직후 요청한다. */
    fun routeTo(lat: Double, lon: Double, name: String) {
        val route = RouteInfo(
            latitude = lat, longitude = lon, displayLongitude = null, displayLatitude = null,
            poiName = name, poiId = "", poiSubId = "0", address = "",
        )
        if (connected) navi.requestRoute(route) else pendingRoute = route
    }

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) =
            onPosition(VehiclePosition(info.latitude, info.longitude, info.heading, info.address))

        override fun onTBTInfo(list: List<TBTInfo>) = onNextTurn(list.firstOrNull())

        override fun onDestinationInfo(info: DestinationInfo) = onDestination(info)

        override fun onNavigationStatus(status: NavigationStatus) = onStatus(status.name)

        override fun onRouteCancelled() = onDestination(null)

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "NaviHelper 오류: $errorCode")
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            navi.getCurrentLocationInfo()
            navi.getTBTInfo()
            navi.getDestinationInfo()
            if (polling) handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        navi.initialize(
            onConnected = {
                Log.i(TAG, "NaviHelper 연결됨")
                onStatus("연결됨")
                navi.addListener(listener)
                connected = true
                pendingRoute?.let { navi.requestRoute(it) }
                pendingRoute = null
                polling = true
                handler.post(poll)
            },
            onFailed = {
                // 부팅 직후처럼 내비 서비스가 아직 준비되지 않았을 수 있다 → 몇 초 뒤 다시 연결한다
                Log.w(TAG, "NaviHelper 연결 실패, ${RETRY_MS / 1000}초 뒤 재시도", it)
                onStatus("연결 실패 · 재시도 중")
                handler.removeCallbacks(retry)
                if (!stopped) handler.postDelayed(retry, RETRY_MS)
            },
        )
    }

    fun stop() {
        stopped = true
        polling = false
        handler.removeCallbacks(poll)
        handler.removeCallbacks(retry)
        navi.removeListener(listener)
        navi.release()
    }

    companion object {
        private const val TAG = "HyperConnect"
        private const val POLL_MS = 1000L
        private const val RETRY_MS = 4000L
    }
}
