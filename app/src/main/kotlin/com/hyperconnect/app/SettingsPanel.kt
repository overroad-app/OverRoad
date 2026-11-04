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

    /** 이모트 받기 (끄면 소리도 나지 않는다). 초보운전자는 끄고 쓸 수 있다. */
    var receiveEmotes: Boolean
        get() = p.getBoolean("receive_emotes", true)
        set(v) = p.edit().putBoolean("receive_emotes", v).apply()

    /** 이모트 보내기 버튼 (켜져 있어도 정차·저속일 때만 보인다). */
    var emoteButtons: Boolean
        get() = p.getBoolean("emote_buttons", true)
        set(v) = p.edit().putBoolean("emote_buttons", v).apply()

    /** 자동 감사 인사: 차로 진입에 성공하면 조작 없이 양보해 준 뒤차에게 👍를 보낸다. */
    var autoThanks: Boolean
        get() = p.getBoolean("auto_thanks", true)
        set(v) = p.edit().putBoolean("auto_thanks", v).apply()
}

/**
 * 설정 화면: 내 기여 통계·배지와 이모트 관련 설정. 정차 중에만 연다.
 * 공개 순위표 대신 내 위치(상위 %)와 배지만 보여준다.
 */
class SettingsPanel(context: Context, private val prefs: Prefs, onClose: () -> Unit) {

    private val statsView = text(context, 30f, Color.WHITE)

    val view: View = ScrollView(context).apply {
        setBackgroundColor(Color.rgb(18, 20, 26))
        visibility = View.GONE
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 48, 72, 48)
            addView(text(context, 40f, Color.WHITE).apply { text = "⚙  설정 · 내 기여" })
            addView(statsView.apply { setPadding(0, 32, 0, 32) })
            addView(text(context, 26f, Color.rgb(150, 160, 175)).apply {
                text = "운전 중 주의를 빼앗지 않도록, 이모트는 주행 중에 소리로만 알려드려요."
            })
            addView(toggle(context, "이모트 받기", prefs.receiveEmotes) { prefs.receiveEmotes = it })
            addView(toggle(context, "이모트 보내기 버튼 (정차·저속일 때만 표시)", prefs.emoteButtons) { prefs.emoteButtons = it })
            addView(toggle(context, "자동 감사 인사 (차로 진입 후 뒤차에게 👍)", prefs.autoThanks) { prefs.autoThanks = it })
            addView(Button(context).apply {
                text = "닫기"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setOnClickListener { onClose() }
            })
        })
    }

    fun showStats(s: MyStats?) {
        statsView.text = if (s == null) "통계를 불러오지 못했어요" else buildString {
            appendLine(if (s.badges.isEmpty()) "배지: 아직 없어요" else "🏅 ${s.badges.joinToString(" · ")}")
            s.topPct?.let { appendLine("이번 주 상위 $it%") }
            appendLine()
            appendLine("내 보고 ${s.reports}건 → ${s.helped}대에게 전달")
            appendLine("받은 감사  👍 ${s.thanks}   🙏 ${s.sorry}   👋 ${s.hello}")
            append("내 경고 정확도  ${s.accuracy?.let { "$it%" } ?: "피드백 없음"}")
        }
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
