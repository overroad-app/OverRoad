package com.hyperconnect.app

import android.graphics.Color

/**
 * 이모트 팩: 같은 세 가지 표현(고마워요·미안해요·안녕)을 팩마다 다른 소리와 그림으로 전한다.
 * 받는 쪽은 보낸 차의 팩으로 듣는다 → 들어 본 소리가 구매로 이어지는 구조.
 * 주행 중에는 어떤 팩이든 소리만 나고, 그림(애니메이션)은 정차·저속일 때만 보인다.
 * 유료 팩 판매는 Pleos 결제 SDK 출시 후 (지금은 체험으로 고를 수 있다). 소리는 scripts/make_emote_sounds.py로 직접 합성.
 */
data class EmotePack(
    val id: String, val name: String, val priceNote: String,
    val colorTop: Int, val colorBottom: Int, val particles: List<String>, val sounds: Map<String, Int>,
)

object EmotePacks {
    val all = listOf(
        EmotePack(
            "basic", "기본", "무료", Color.rgb(34, 160, 100), Color.rgb(16, 110, 70), listOf("✨", "💚", "·"),
            mapOf("thanks" to R.raw.emote_thanks, "sorry" to R.raw.emote_sorry, "hello" to R.raw.emote_hello),
        ),
        EmotePack(
            "retro", "레트로 8비트", "유료 예정 · 지금은 체험", Color.rgb(124, 58, 237), Color.rgb(219, 39, 119),
            listOf("✦", "▪", "★", "◆"),
            mapOf("thanks" to R.raw.emote_retro_thanks, "sorry" to R.raw.emote_retro_sorry, "hello" to R.raw.emote_retro_hello),
        ),
        EmotePack(
            "orchestra", "오케스트라", "유료 예정 · 지금은 체험", Color.rgb(202, 138, 4), Color.rgb(124, 45, 18),
            listOf("♪", "♫", "♩", "✧"),
            mapOf("thanks" to R.raw.emote_orchestra_thanks, "sorry" to R.raw.emote_orchestra_sorry, "hello" to R.raw.emote_orchestra_hello),
        ),
    )

    fun of(id: String?) = all.firstOrNull { it.id == id } ?: all.first()
}
