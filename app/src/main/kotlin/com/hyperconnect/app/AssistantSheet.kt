package com.hyperconnect.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 어시스턴트 창: 평소에는 아래쪽에 이모트 바만 작게 떠 있고, 손잡이를 끌면 창 크기를 조절하듯 커진다.
 * 끌면 가장 가까운 단계(작게 · 중간 · 크게)에 맞춰 멈추고, 손잡이를 탭하면 다음 단계로 넘어간다.
 * 세부 정보([detail])는 커졌을 때만 보이고, 이모트 바([bottom])는 항상 맨 아래에 남는다.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class AssistantSheet(context: Context, detail: View, bottom: View, private val onLevel: (Int) -> Unit) : LinearLayout(context) {

    var level = LEVEL_MINI
        private set

    /** 가장 크게 열 수 있는 단계: 주행 중에는 중간까지, 정차 중에만 전체 화면. 지금 더 크면 줄인다. */
    var maxLevel = LEVEL_FULL
        set(v) {
            if (field == v) return
            field = v
            if (level > v) snapTo(v)
        }

    /** 작은 창에 보여 줄 것이 없을 때(주행 중이라 이모트 바가 숨으면) 손잡이만 남긴 얇은 바로 줄인다. */
    var compact = false
        set(v) {
            if (field == v) return
            field = v
            if (level == LEVEL_MINI) setHeightPx(heightOf(LEVEL_MINI))
        }

    /** 전체 화면에서는 창이 투명해져 뒤의 연결망 시각화가 보인다. */
    var glass = false
        set(v) {
            if (field == v) return
            field = v
            background = if (v) null else whiteBg
            elevation = if (v) 0f else 28f
        }
    private val whiteBg = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 44f }

    private val label = TextView(context).apply {
        text = "자세히"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(Color.rgb(90, 100, 115))
        typeface = Typeface.DEFAULT_BOLD
    }
    private val detailView = detail
    private var animator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        background = whiteBg
        clipToOutline = true
        elevation = 28f
        isClickable = true  // 창을 눌러도 뒤의 빈 곳 동작(내비로 돌아가기)이 실행되지 않게
        val handle = FrameLayout(context).apply {
            addView(View(context).apply {
                background = GradientDrawable().apply { setColor(Color.rgb(195, 200, 210)); cornerRadius = 8f }
            }, FrameLayout.LayoutParams(150, 12, Gravity.CENTER))
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = 36 })
        }
        addView(handle, LayoutParams(LayoutParams.MATCH_PARENT, HANDLE_H))
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        detail.visibility = View.GONE

        var startY = 0f
        var startH = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { animator?.cancel(); startY = e.rawY; startH = height; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = startY - e.rawY
                    if (abs(dy) > TOUCH_SLOP) moved = true
                    if (moved) setHeightPx((startH + dy).toInt())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) snapTo((LEVEL_MINI..maxLevel).minByOrNull { abs(heightOf(it) - height) } ?: LEVEL_MINI)
                    else snapTo((level + 1) % (maxLevel + 1))
                }
            }
            true
        }
    }

    private fun parentH() = (parent as? View)?.height ?: 0
    private fun parentW() = (parent as? View)?.width ?: 0
    private fun heightOf(l: Int) = when (l) {
        LEVEL_MID -> (parentH() * 0.7f).toInt()
        LEVEL_FULL -> parentH() - MARGIN * 2
        else -> if (compact) COMPACT_H else MINI_H
    }

    /** 창이 커질수록 폭도 함께 넓어진다. */
    private fun setHeightPx(h: Int) {
        val full = heightOf(LEVEL_FULL)
        val minH = heightOf(LEVEL_MINI)
        val clamped = h.coerceIn(minH, heightOf(maxLevel).coerceAtLeast(minH))
        val t = if (full > MINI_H) (clamped - MINI_H).coerceAtLeast(0).toFloat() / (full - MINI_H) else 0f
        val midT = ((heightOf(LEVEL_MID) - MINI_H).toFloat() / (full - MINI_H).coerceAtLeast(1)).coerceAtLeast(0.01f)
        val w = MINI_W + (((parentW() - MARGIN * 2) - MINI_W) * (t / midT).coerceAtMost(1f)).toInt()
        val lp = layoutParams
        lp.height = clamped
        lp.width = w
        layoutParams = lp
        detailView.visibility = if (clamped > MINI_H + 60) View.VISIBLE else View.GONE
    }

    fun snapTo(wanted: Int) {
        val target = wanted.coerceAtMost(maxLevel)
        animator?.cancel()
        val changed = target != level
        level = target
        label.text = if (target == LEVEL_FULL) "접기" else "자세히"
        animator = ValueAnimator.ofInt(height.coerceAtLeast(MINI_H), heightOf(target)).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { setHeightPx(it.animatedValue as Int) }
            start()
        }
        if (changed) onLevel(target)
    }

    companion object {
        const val LEVEL_MINI = 0
        const val LEVEL_MID = 1
        const val LEVEL_FULL = 2
        const val MINI_W = 1240
        const val MINI_H = 196
        const val COMPACT_H = 70
        const val MARGIN = 24
        private const val HANDLE_H = 58
        private const val TOUCH_SLOP = 14f
    }
}
