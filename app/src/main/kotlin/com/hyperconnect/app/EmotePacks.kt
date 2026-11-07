package com.hyperconnect.app

import android.graphics.Color

/**
 * 이모트 팩: 같은 세 가지 표현(고마워요·미안해요·안녕)을 팩마다 다른 소리와 그림으로 전한다.
 * 받는 쪽은 보낸 차의 팩으로 듣는다 → 도로 위에서 들어 본 소리가 곧 미리 듣기다.
 * 주행 중에는 어떤 팩이든 소리만 나고, 그림(애니메이션)은 정차·저속일 때만 보인다.
 *
 * 상점(정차 중 전체 화면, [StorePanel])의 네 구역:
 *  - 기본: 무료
 *  - 현대차 오리지널: 유료 (Pleos 결제 SDK 출시 후 구매)
 *  - 특별 지급: 판매하지 않고 조건을 충족하면 서버가 지급 (출고 기념, 베스트 드라이버, 긴급차량 전용)
 *  - 창작마당: 심사를 통과한 크리에이터 팩, 유료 (올리기는 차 밖 웹에서)
 * 보유 여부는 서버가 정한다(`GET /store`). 소리는 scripts/make_emote_sounds.py로 직접 합성 — 사이렌·경적과 닮지 않게, 1.5초 이하.
 */
data class EmotePack(
    val id: String, val name: String, val section: String, val maker: String, val price: Int?, val unlock: String?,
    val colorTop: Int, val colorBottom: Int, val particles: List<String>, val sounds: Map<String, Int>,
)

object EmotePacks {
    private fun s(t: Int, so: Int, h: Int) = mapOf("thanks" to t, "sorry" to so, "hello" to h)

    val all = listOf(
        EmotePack("basic", "기본", "basic", "HyperConnect", 0, null, Color.rgb(34, 160, 100), Color.rgb(16, 110, 70),
            listOf("✨", "💚", "·"), s(R.raw.emote_thanks, R.raw.emote_sorry, R.raw.emote_hello)),
        EmotePack("retro", "레트로 8비트", "original", "현대차", 2200, null, Color.rgb(124, 58, 237), Color.rgb(219, 39, 119),
            listOf("✦", "▪", "★", "◆"), s(R.raw.emote_retro_thanks, R.raw.emote_retro_sorry, R.raw.emote_retro_hello)),
        EmotePack("orchestra", "오케스트라", "original", "현대차", 2200, null, Color.rgb(202, 138, 4), Color.rgb(124, 45, 18),
            listOf("♪", "♫", "♩", "✧"), s(R.raw.emote_orchestra_thanks, R.raw.emote_orchestra_sorry, R.raw.emote_orchestra_hello)),
        EmotePack("electric", "아이오닉 전자음", "original", "현대차 · 차종 사운드", 3300, null, Color.rgb(14, 116, 144), Color.rgb(30, 64, 175),
            listOf("⚡", "◇", "·"), s(R.raw.emote_electric_thanks, R.raw.emote_electric_sorry, R.raw.emote_electric_hello)),
        EmotePack("launch", "출고 기념", "special", "현대차", null, "신차 출고 30일 이내", Color.rgb(236, 72, 153), Color.rgb(249, 115, 22),
            listOf("🎉", "✦", "·"), s(R.raw.emote_launch_thanks, R.raw.emote_launch_sorry, R.raw.emote_launch_hello)),
        EmotePack("best", "베스트 드라이버", "special", "현대차", null, "베스트 드라이버 배지를 받으면", Color.rgb(202, 138, 4), Color.rgb(161, 98, 7),
            listOf("🏅", "✧", "·"), s(R.raw.emote_best_thanks, R.raw.emote_best_sorry, R.raw.emote_best_hello)),
        EmotePack("emergency", "긴급차량 감사", "special", "현대차", null, "구급·소방·경찰차 전용 · 길을 열어 준 차에게 자동으로",
            Color.rgb(71, 85, 105), Color.rgb(30, 41, 59), listOf("✚", "·"),
            s(R.raw.emote_emergency_thanks, R.raw.emote_emergency_thanks, R.raw.emote_emergency_thanks)),
        EmotePack("gugak", "국악 한 소절", "creator", "크리에이터 한소리", 1100, null, Color.rgb(190, 18, 60), Color.rgb(127, 29, 29),
            listOf("〰", "·", "◦"), s(R.raw.emote_gugak_thanks, R.raw.emote_gugak_sorry, R.raw.emote_gugak_hello)),
        EmotePack("marimba", "마림바", "creator", "크리에이터 우드톤", 1100, null, Color.rgb(180, 83, 9), Color.rgb(120, 53, 15),
            listOf("♪", "▫", "·"), s(R.raw.emote_marimba_thanks, R.raw.emote_marimba_sorry, R.raw.emote_marimba_hello)),
    )

    val sections = listOf("basic" to "기본", "original" to "현대차 오리지널", "special" to "특별 지급", "creator" to "창작마당")

    fun of(id: String?) = all.firstOrNull { it.id == id } ?: all.first()
}
