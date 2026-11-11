package com.hyperconnect.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 서버 `GET /store`: 장착 중인 팩과 이 차량이 가진 팩. */
data class StoreInfo(val equipped: String, val owned: Set<String>)

/**
 * 이모트 스토어 (정차 중 전체 화면에서만 연다): 기본 · 현대차 오리지널 · 특별 지급 · 창작마당 · 내 보관함.
 * 모든 팩은 미리 들어 볼 수 있고, 가진 팩만 장착한다. 구매는 Pleos 결제 SDK 출시 후 (지금은 안내만).
 * 표현은 고마워요·미안해요·안녕 세 가지로 고정 — 팩은 소리와 정차 중 애니메이션만 바꾼다(자유 문구 없음).
 */
class StorePanel(
    context: Context,
    private val onPreview: (String) -> Unit,
    private val onEquip: (String) -> Unit,
    private val onBuy: (String) -> Unit,
    private val onLocked: (String) -> Unit,
    onClose: () -> Unit,
) {
    private var info = StoreInfo("basic", setOf("basic"))
    private var tab = "all"
    private val equippedChip = text(context, 20f, Color.rgb(21, 128, 61), bold = true).apply {
        background = round(Color.rgb(220, 252, 231), 18f); setPadding(22, 8, 22, 8)
    }
    private val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 24) }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(248, 250, 252))
        visibility = View.GONE
        elevation = 100f
        isClickable = true
        setPadding(48, 32, 48, 0)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(context, 34f, Color.rgb(15, 23, 42), bold = true).apply { text = "이모트 스토어" })
            addView(text(context, 19f, Color.rgb(100, 116, 139)).apply { text = "  정차 중에만 열려요"; setPadding(12, 0, 0, 0) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(equippedChip)
            addView(text(context, 24f, Color.rgb(37, 99, 235), bold = true).apply {
                text = "닫기"; setPadding(40, 10, 8, 10); setOnClickListener { onClose() }
            })
        })
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 22, 0, 10)
            addView(tabs)
        })
        addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun show(i: StoreInfo) {
        info = i
        render()
    }

    /** 장착을 누른 즉시 그 카드를 '장착 중'으로 바꾸고 한 번 튀게 한다 (서버 응답을 기다리지 않는다). */
    fun markEquipped(id: String) {
        pulse = id
        info = info.copy(equipped = id)
        render()
    }

    private var pulse: String? = null

    private fun render() {
        val ctx = view.context
        equippedChip.text = "장착 중 · ${EmotePacks.of(info.equipped).name}"
        tabs.removeAllViews()
        (listOf("all" to "전체") + EmotePacks.sections + listOf("mine" to "내 보관함")).forEach { (id, label) ->
            tabs.addView(text(ctx, 21f, if (id == tab) Color.rgb(30, 64, 175) else Color.rgb(51, 65, 85), bold = id == tab).apply {
                text = label
                background = if (id == tab) round(Color.rgb(219, 234, 254), 20f) else stroke(Color.rgb(203, 213, 225), 20f)
                setPadding(26, 10, 26, 10)
                setOnClickListener { tab = id; render() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12 })
        }
        list.removeAllViews()
        for ((sec, label) in EmotePacks.sections) {
            val packs = EmotePacks.all.filter { it.section == sec && (tab == "all" || tab == sec || (tab == "mine" && it.id in info.owned)) }
            if (packs.isEmpty()) continue
            list.addView(text(ctx, 20f, Color.rgb(100, 116, 139)).apply {
                text = label + when (sec) {
                    "basic" -> " · 무료"; "original" -> " · 유료"; "special" -> " · 판매하지 않음, 조건을 충족하면 지급"; else -> " · 심사를 통과한 크리에이터 팩"
                }
                setPadding(4, 20, 0, 10)
            })
            packs.chunked(3).forEach { row ->
                list.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEach { addView(card(ctx, it), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 16, 16) }) }
                    repeat(3 - row.size) { addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = 16 }) }
                })
            }
            if (sec == "creator") list.addView(text(ctx, 18f, Color.rgb(148, 163, 184)).apply {
                text = "내 팩 올리기는 차 밖 웹 창작마당에서 · 사이렌·경적과 닮은 소리, 1.5초 넘는 소리는 심사에서 거절돼요"
                setPadding(4, 0, 0, 0)
            })
        }
    }

    private fun card(ctx: Context, p: EmotePack): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        // 장착 중인 팩은 굵은 청록 테두리와 옅은 바탕으로 한눈에 구분한다
        val equipped = p.id == info.equipped
        background = GradientDrawable().apply {
            setColor(if (equipped) Color.rgb(236, 253, 245) else Color.WHITE)
            cornerRadius = 22f
            setStroke(if (equipped) 7 else 2, if (equipped) EQUIPPED else Color.rgb(226, 232, 240))
        }
        setPadding(18, 18, 18, 18)
        val owned = p.id in info.owned
        if (p.id == pulse) {
            pulse = null
            scaleX = 0.9f; scaleY = 0.9f
            animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(OvershootInterpolator(3.5f)).start()
        }
        // 팩 색 견본 + 조각
        addView(FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(p.colorTop, p.colorBottom)).apply { cornerRadius = 14f }
            if (!owned && p.section == "special") alpha = 0.45f
            addView(text(ctx, 26f, Color.WHITE).apply { text = p.particles.joinToString("  "); gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 88))
        addView(text(ctx, 23f, Color.rgb(15, 23, 42), bold = true).apply { text = p.name; setPadding(0, 14, 0, 0) })
        addView(text(ctx, 17f, Color.rgb(100, 116, 139)).apply {
            text = when {
                p.unlock != null -> if (owned) "${p.maker} · 지급됨" else "🔒 ${p.unlock}"
                p.price == 0 -> "${p.maker} · 무료"
                owned -> "${p.maker} · 보유"
                else -> "${p.maker} · ${"%,d".format(p.price)}원"
            }
            maxLines = 2
            setPadding(0, 4, 0, 12)
        })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(ctx, "▶ 들어보기", false) { onPreview(p.id) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 10 })
            val (label, primary, action) = when {
                p.id == info.equipped -> Triple("✔ 장착 중", false) { }
                owned -> Triple("장착", true) { onEquip(p.id) }
                p.unlock != null -> Triple("잠김", false) { onLocked(p.unlock) }
                else -> Triple("구매", true) { onBuy(p.id) }
            }
            addView(button(ctx, label, primary, action).apply {
                if (equipped) { background = round(EQUIPPED, 16f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun button(ctx: Context, label: String, primary: Boolean, onClick: () -> Unit) = text(ctx, 19f,
        if (primary) Color.WHITE else Color.rgb(51, 65, 85), bold = primary).apply {
        text = label
        gravity = Gravity.CENTER
        background = if (primary) round(Color.rgb(37, 99, 235), 16f) else stroke(Color.rgb(203, 213, 225), 16f)
        setPadding(8, 14, 8, 14)
        setOnClickListener { onClick() }
    }

    private fun text(ctx: Context, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private val EQUIPPED = Color.rgb(13, 148, 136)

    private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }
    private fun stroke(color: Int, r: Float) = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = r; setStroke(2, color) }
}
