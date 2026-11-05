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

    /** 위치·차량 신호를 서버와 공유하는 데 동의했는지. 동의 전에는 아무것도 보내지 않는다. */
    var consented: Boolean
        get() = p.getBoolean("consented", false)
        set(v) = p.edit().putBoolean("consented", v).apply()

    /** 이모트 팩: 내가 보내는 이모트가 상대에게 이 팩의 소리·그림으로 전해진다. */
    var emotePack: String
        get() = p.getString("emote_pack", "basic") ?: "basic"
        set(v) = p.edit().putString("emote_pack", v).apply()

    /** 자동 감사 인사: 차로 진입에 성공하면 조작 없이 양보해 준 뒤차에게 👍를 보낸다. */
    var autoThanks: Boolean
        get() = p.getBoolean("auto_thanks", true)
        set(v) = p.edit().putBoolean("auto_thanks", v).apply()
}

/**
 * 설정 화면: 내 기여 통계·배지와 이모트 관련 설정. 정차 중에만 연다.
 * 공개 순위표 대신 내 위치(상위 %)와 배지만 보여준다.
 */
class SettingsPanel(
    context: Context, private val prefs: Prefs, onClose: () -> Unit, onWithdraw: () -> Unit,
    onPack: (id: String, preview: Boolean) -> Unit,
) {

    private val statsView = text(context, 30f, Color.WHITE)
    private val diagnostics = text(context, 22f, Color.rgb(150, 160, 175)).apply { setLineSpacing(0f, 1.15f) }

    /** 이모트 팩 고르기: 고르면 내가 보내는 이모트가 이 팩 소리로 들린다. 들어보기는 정차 중(설정은 주차 중에만 열림). */
    private fun packPicker(context: Context, onPack: (String, Boolean) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 24, 0, 8)
        addView(text(context, 32f, Color.WHITE).apply { text = "🎵  이모트 팩" })
        addView(text(context, 22f, Color.rgb(150, 160, 175)).apply {
            text = "내가 보낸 이모트는 상대 차에 이 팩의 소리로 들려요. 유료 팩은 Pleos 결제 SDK가 나오면 판매할 예정이고, 지금은 체험할 수 있어요."
        })
        val rows = mutableListOf<Button>()
        fun refresh() = rows.forEachIndexed { i, b ->
            val pack = EmotePacks.all[i]
            b.text = "${if (prefs.emotePack == pack.id) "✔ " else ""}${pack.name} · ${pack.priceNote}"
        }
        EmotePacks.all.forEach { pack ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 0)
                val choose = Button(context).apply {
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    setTextColor(Color.WHITE)
                    setPadding(24, 22, 24, 22)
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pack.colorTop, pack.colorBottom),
                    ).apply { cornerRadius = 28f }
                    setOnClickListener {
                        prefs.emotePack = pack.id
                        refresh()
                        onPack(pack.id, false)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                }
                rows += choose
                addView(choose)
                addView(Button(context).apply {
                    text = "▶ 들어보기"
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setOnClickListener { onPack(pack.id, true) }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 16 }
                })
            })
        }
        refresh()
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
            addView(statsView.apply { setPadding(0, 32, 0, 32) })
            addView(text(context, 26f, Color.rgb(150, 160, 175)).apply {
                text = "운전 중 주의를 빼앗지 않도록, 이모트는 주행 중에 소리로만 알려드려요."
            })
            addView(toggle(context, "이모트 받기", prefs.receiveEmotes) { prefs.receiveEmotes = it })
            addView(toggle(context, "이모트 보내기 버튼 (정차·저속일 때만 표시)", prefs.emoteButtons) { prefs.emoteButtons = it })
            addView(toggle(context, "자동 감사 인사 (차로 진입 후 뒤차에게 👍)", prefs.autoThanks) { prefs.autoThanks = it })
            addView(packPicker(context, onPack))
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
