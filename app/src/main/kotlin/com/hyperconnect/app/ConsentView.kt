package com.hyperconnect.app

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 처음 실행할 때의 데이터 공유 안내·동의 화면.
 * 동의 버튼을 누르기 전에는 서버에 아무것도 보내지 않는다 (Pleos 앱 심사 가이드: 명시적 동의, 철회 가능).
 */
class ConsentView(context: Context, onAgree: () -> Unit, onDecline: () -> Unit) {

    private val declinedNote = text(context, 26f, Color.rgb(240, 180, 90)).apply {
        text = "동의하지 않으면 주변 차량의 위험 경고를 받을 수 없어요. 설정에서 언제든 다시 동의할 수 있어요."
        visibility = View.GONE
    }

    val view: View = ScrollView(context).apply {
        setBackgroundColor(Color.rgb(18, 20, 26))
        visibility = View.GONE
        isClickable = true  // 뒤쪽 화면이 눌리지 않게
        elevation = 100f    // 버튼 그림자(elevation)보다 위에 그려지게
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 48, 72, 48)
            addView(text(context, 40f, Color.WHITE).apply { text = "OverRoad 데이터 공유 안내" })
            addView(text(context, 28f, Color.rgb(210, 215, 225)).apply {
                setPadding(0, 32, 0, 32)
                setLineSpacing(0f, 1.2f)
                text = """
                    |주변 차량과 위험 정보를 주고받으려면 아래 정보를 OverRoad 서버와 공유해야 해요.
                    |
                    |· 보내는 정보: 차량 위치·진행 방향·속도·차로, 급제동·경고등·와이퍼·방향지시등 같은 차량 신호, 배터리 잔량과 목적지 충전소
                    |· 쓰는 곳: 뒤따르는 차량에 위험 경고 전달, 정체·충전 대기 안내, 이모트 전달, 내 기여 통계
                    |· 보관: 위치는 실시간 판단에만 쓰고 10초간 갱신이 없으면 지워요. 통계는 계정과 연결되지 않은 가명 ID로만 남아요
                    |· 언제든 설정에서 동의를 철회할 수 있고, 철회하면 전송을 멈추고 서버의 내 데이터를 지워요
                """.trimMargin()
            })
            addView(Button(context).apply {
                text = "동의하고 시작"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                setOnClickListener { onAgree() }
            })
            addView(Button(context).apply {
                text = "동의하지 않음"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setOnClickListener {
                    declinedNote.visibility = View.VISIBLE
                    onDecline()
                }
            })
            addView(declinedNote.apply { setPadding(0, 24, 0, 0) })
        })
    }

    fun show() {
        declinedNote.visibility = View.GONE
        view.visibility = View.VISIBLE
    }

    fun hide() {
        view.visibility = View.GONE
    }

    private fun text(context: Context, sp: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
    }
}
