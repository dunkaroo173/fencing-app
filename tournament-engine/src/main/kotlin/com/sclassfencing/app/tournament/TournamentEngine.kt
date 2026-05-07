package com.sclassfencing.app.tournament

data class Poule(val id: Int, val fencers: List<String>)
data class SeedEntry(val name: String, val wins: Int, val indicator: Int)

object TournamentEngine {

    fun generatePoules(fencers: List<String>): List<Poule> {
        require(fencers.size >= 2) { "Need at least 2 fencers" }
        val shuffled = fencers.shuffled()
        if (shuffled.size < 4) {
            return listOf(Poule(1, shuffled))
        }
        val mid = (shuffled.size + 1) / 2
        return listOf(
            Poule(1, shuffled.subList(0, mid)),
            Poule(2, shuffled.subList(mid, shuffled.size))
        )
    }

    fun calculateSeeding(
        poules: List<Poule>,
        scores: Map<String, Int>
    ): List<SeedEntry> {
        val stats = mutableMapOf<String, IntArray>() // [wins, indicator]
        for (poule in poules) {
            val fencers = poule.fencers
            for (i in fencers.indices) {
                for (j in i + 1 until fencers.size) {
                    val a = fencers[i]; val b = fencers[j]
                    val aScore = scores["$a-$b-A"] ?: 0
                    val bScore = scores["$a-$b-B"] ?: 0
                    val sa = stats.getOrPut(a) { intArrayOf(0, 0) }
                    val sb = stats.getOrPut(b) { intArrayOf(0, 0) }
                    if (aScore > bScore) sa[0]++ else if (bScore > aScore) sb[0]++
                    sa[1] += aScore - bScore
                    sb[1] += bScore - aScore
                }
            }
        }
        return stats.entries
            .sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[0] }
                .thenByDescending { it.value[1] })
            .map { SeedEntry(it.key, it.value[0], it.value[1]) }
    }

    fun buildTableau(seeding: List<SeedEntry>): List<Pair<SeedEntry?, SeedEntry?>> {
        val result = mutableListOf<Pair<SeedEntry?, SeedEntry?>>()
        var i = 0
        while (i < seeding.size) {
            result.add(seeding.getOrNull(i) to seeding.getOrNull(i + 1))
            i += 2
        }
        return result
    }
}
