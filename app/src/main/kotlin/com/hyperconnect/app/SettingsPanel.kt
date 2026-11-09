package com.hyperconnect.app

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** 운전자 설정. 기본값은 조작 없이도 안전하게 쓰이도록 정한다. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("hyperconnect", Context.MODE_PRIVATE)

    /** 이모트 받기. 끄면 "항상 감정표현 받지 않기": 서버가 이 차에게 아무 이모트도(자동 감사·긴급차량 감사 포함) 보내지 않고,
     *  보낸 쪽에는 "받지 않아요"로 알린다. 초보운전자는 끄고 쓸 수 있다. */
    var receiveEmotes: Boolean
        get() = p.getBoolean("receive_emotes", true)
        set(v) = p.edit().putBoolean("receive_emotes", v).apply()

    /** 이모트 보내기 버튼 (켜져 있어도 정차·저속일 때만 보인다). */
    var emoteButtons: Boolean
        get() = p.getBoolean("emote_buttons", true)
        set(v) = p.edit().putBoolean("emote_buttons", v).apply()

    /** 위치·차량 신호를 서버와 공유하는 데 동의했는지. 동의 전에는 아무것도 보내지 않는다. */
    var consented: Boolean
        get() = p.getBoolean("consented", false)
        set(v) = p.edit().putBoolean("consented", v).apply()

    /** 이모트 팩: 내가 보내는 이모트가 상대에게 이 팩의 소리·그림으로 전해진다. */
    var emotePack: String
        get() = p.getString("emote_pack", "basic") ?: "basic"
        set(v) = p.edit().putString("emote_pack", v).apply()

    /**
     * 주행 중 연결망 화면 (상위 티어 SVS 미리보기): 켜면 전체 화면 연결망 시각화를 보고 있을 때 출발해도 내비로 물러나지 않는다.
     * 그동안 내비 지도는 보이지 않으므로 기본은 꺼 둔다.
     */
    var drivingView: Boolean
        get() = p.getBoolean("driving_view", false)
        set(v) = p.edit().putBoolean("driving_view", v).apply()

    /** 자동 감사 인사: 차로 진입에 성공하면 조작 없이 양보해 준 뒤차에게 👍를 보낸다. */
    var autoThanks: Boolean
        get() = p.getBoolean("auto_thanks", true)
        set(v) = p.edit().putBoolean("auto_thanks", v).apply()
}

/**
 * 설정 화면: 내 기여 통계·배지, 내 보고 기록, 이모트 설정. 주차 중에만 연다.
 * 이모트 팩 고르기·구매는 여기 두지 않고, 나중에 별도 상점 화면에서 한다 (결제 SDK 출시 후).
 * 공개 순위표 대신 내 위치(상위 %)와 배지만 보여준다.
 */
class SettingsPanel(
    context: Context, private val prefs: Prefs, onClose: () -> Unit, onWithdraw: () -> Unit,
) {

    private val statsView = text(context, 30f, Color.WHITE)
    private val diagnostics = text(context, 22f, Color.rgb(150, 160, 175)).apply { setLineSpacing(0f, 1.15f) }

    private val historyList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)

    /** 내 보고 기록: 평가가 아니라 기여가 쌓이는 모습 ("급제동 보고 · 뒤차 2대에게 전달"). */
    private fun historySection(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 8, 0, 24)
        addView(text(context, 28f, Color.WHITE).apply { text = "📋  내 보고 기록"; setPadding(0, 16, 0, 6) })
        addView(historyList)
    }

    private fun showHistory(s: MyStats) {
        val ctx = historyList.context
        historyList.removeAllViews()
        if (s.history.isEmpty()) {
            historyList.addView(text(ctx, 22f, Color.rgb(150, 160, 175)).apply { text = "아직 보고한 위험이 없어요. 차량 신호로 자동 감지해 보고해요." })
        }
        s.history.forEach { r ->
            val label = when (r.type) {
                "hard_brake" -> "급제동"; "stopped_vehicle" -> "정차 차량"; "crash" -> "사고"; else -> r.type
            }
            historyList.addView(text(ctx, 24f, Color.WHITE).apply {
                text = "${clock.format(java.util.Date(r.atMs))}  $label 보고 · " +
                    if (r.notified > 0) "뒤차 ${r.notified}대에게 전달" else "주변에 받을 차가 없었어요"
                setPadding(0, 6, 0, 6)
            })
        }
    }

    val view: View = ScrollView(context).apply {
        setBackgroundColor(Color.rgb(18, 20, 26))
        visibility = View.GONE
        elevation = 100f
        isClickable = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 48, 72, 48)
            // 제목 줄 오른쪽에 닫기 (아래까지 스크롤하지 않아도 닫을 수 있게)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(text(context, 40f, Color.WHITE).apply { text = "⚙  설정 · 내 기여" },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply {
                    text = "닫기"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    setOnClickListener { onClose() }
                })
            })
            addView(statsView.apply { setPadding(0, 32, 0, 8) })
            addView(historySection(context))
            addView(text(context, 26f, Color.rgb(150, 160, 175)).apply {
                text = "운전 중 주의를 빼앗지 않도록, 이모트는 주행 중에 소리로만 알려드려요."
            })
            addView(toggle(context, "항상 감정표현 받지 않기 (보낸 쪽에는 '받지 않아요'로 표시)", !prefs.receiveEmotes) { prefs.receiveEmotes = !it })
            addView(toggle(context, "이모트 보내기 버튼 (정차·저속일 때만 표시)", prefs.emoteButtons) { prefs.emoteButtons = it })
            addView(toggle(context, "자동 감사 인사 (차로 진입 후 뒤차에게 👍)", prefs.autoThanks) { prefs.autoThanks = it })
            addView(toggle(context, "주행 중에도 연결망 화면 유지 (상위 티어 미리보기 · 내비 지도가 가려짐)", prefs.drivingView) { prefs.drivingView = it })
            addView(Button(context).apply {
                text = "데이터 공유 동의 철회"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setOnClickListener { onWithdraw() }
            })
            addView(text(context, 26f, Color.WHITE).apply { text = "🔧  차량 신호 (진단)"; setPadding(0, 32, 0, 8) })
            addView(diagnostics)
        })
    }

    fun showDiagnostics(text: String) {
        diagnostics.text = text
    }

    fun showStats(s: MyStats?) {
        statsView.text = if (s == null) "통계를 불러오지 못했어요" else buildString {
            appendLine(if (s.badges.isEmpty()) "배지: 아직 없어요" else "🏅 ${s.badges.joinToString(" · ")}")
            s.topPct?.let { appendLine("이번 주 상위 $it%") }
            appendLine()
            appendLine("내 보고 ${s.reports}건 → ${s.helped}대에게 전달")
            append("받은 감사  👍 ${s.thanks}   🙏 ${s.sorry}   👋 ${s.hello}")
        }
        if (s != null) showHistory(s)
    }

    private fun text(context: Context, sp: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
    }

    private fun toggle(context: Context, label: String, initial: Boolean, onChange: (Boolean) -> Unit) =
        Switch(context).apply {
            text = label
            isChecked = initial
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(Color.WHITE)
            setPadding(0, 28, 0, 28)
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
}
