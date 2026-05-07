package com.sclassfencing.app

import com.sclassfencing.app.tournament.TournamentEngine
import com.sclassfencing.app.tournament.SeedEntry
import org.junit.Assert.*
import org.junit.Test

class TournamentEngineTest {

    // ── generatePoules ────────────────────────────────────────────────────────

    @Test
    fun `generatePoules splits even list into two poules`() {
        val fencers = listOf("Alice", "Bob", "Carol", "Dave")
        val poules = TournamentEngine.generatePoules(fencers)
        assertEquals(2, poules.size)
        assertEquals(4, poules.sumOf { it.fencers.size })
        // Every fencer appears exactly once
        assertEquals(fencers.sorted(), poules.flatMap { it.fencers }.sorted())
    }

    @Test
    fun `generatePoules splits odd list into two poules with larger first`() {
        val fencers = listOf("A", "B", "C", "D", "E")
        val poules = TournamentEngine.generatePoules(fencers)
        assertEquals(2, poules.size)
        assertEquals(3, poules[0].fencers.size)
        assertEquals(2, poules[1].fencers.size)
    }

    @Test
    fun `generatePoules with two fencers makes one poule`() {
        val poules = TournamentEngine.generatePoules(listOf("X", "Y"))
        assertEquals(1, poules.size)
        assertEquals(2, poules[0].fencers.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generatePoules with one fencer throws`() {
        TournamentEngine.generatePoules(listOf("Solo"))
    }

    // ── calculateSeeding ──────────────────────────────────────────────────────

    @Test
    fun `calculateSeeding ranks by wins then indicator`() {
        // Alice beats Bob 5-0, Alice beats Carol 5-0, Bob beats Carol 5-0
        val poules = TournamentEngine.generatePoules(listOf("Alice", "Bob", "Carol"))
        // Deterministic scores regardless of shuffled order
        val allFencers = poules.flatMap { it.fencers }
        val scores = buildScores(allFencers, mapOf(
            "Alice" to mapOf("Bob" to Pair(5, 0), "Carol" to Pair(5, 0)),
            "Bob"   to mapOf("Carol" to Pair(5, 0))
        ))
        val seeding = TournamentEngine.calculateSeeding(poules, scores)
        assertEquals(3, seeding.size)
        assertEquals("Alice", seeding[0].name)
        assertEquals(2, seeding[0].wins)
        assertEquals("Bob", seeding[1].name)
        assertEquals(1, seeding[1].wins)
        assertEquals("Carol", seeding[2].name)
        assertEquals(0, seeding[2].wins)
    }

    @Test
    fun `calculateSeeding breaks wins tie by indicator`() {
        // Alice beats Bob 5-3; Carol beats Dave 5-3 — same wins, Alice has higher indicator
        val poules = listOf(
            com.sclassfencing.app.tournament.Poule(1, listOf("Alice", "Bob")),
            com.sclassfencing.app.tournament.Poule(2, listOf("Carol", "Dave"))
        )
        val scores = mapOf(
            "Alice-Bob-A" to 5, "Alice-Bob-B" to 3,
            "Carol-Dave-A" to 5, "Carol-Dave-B" to 3
        )
        val seeding = TournamentEngine.calculateSeeding(poules, scores)
        // Both Alice and Carol have 1 win, indicator = +2 — order could be either
        assertEquals(1, seeding[0].wins)
        assertEquals(2, seeding[0].indicator)
    }

    @Test
    fun `calculateSeeding with no scores gives all zeros`() {
        val poules = TournamentEngine.generatePoules(listOf("A", "B", "C", "D"))
        val seeding = TournamentEngine.calculateSeeding(poules, emptyMap())
        seeding.forEach {
            assertEquals(0, it.wins)
            assertEquals(0, it.indicator)
        }
    }

    // ── buildTableau ──────────────────────────────────────────────────────────

    @Test
    fun `buildTableau pairs seeds correctly`() {
        val seeding = listOf(
            SeedEntry("Alice", 3, 10),
            SeedEntry("Bob",   2,  5),
            SeedEntry("Carol", 1,  2),
            SeedEntry("Dave",  0, -7)
        )
        val tableau = TournamentEngine.buildTableau(seeding)
        assertEquals(2, tableau.size)
        assertEquals("Alice", tableau[0].first?.name)
        assertEquals("Bob",   tableau[0].second?.name)
        assertEquals("Carol", tableau[1].first?.name)
        assertEquals("Dave",  tableau[1].second?.name)
    }

    @Test
    fun `buildTableau with odd seeding gives bye for last`() {
        val seeding = listOf(
            SeedEntry("A", 2, 4),
            SeedEntry("B", 1, 1),
            SeedEntry("C", 0, -5)
        )
        val tableau = TournamentEngine.buildTableau(seeding)
        assertEquals(2, tableau.size)
        assertNull(tableau[1].second)  // bye
    }

    @Test
    fun `buildTableau with empty seeding gives empty tableau`() {
        val tableau = TournamentEngine.buildTableau(emptyList())
        assertTrue(tableau.isEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a score map from a readable wins table.
     * [results] maps winner → {loser → (winnerScore, loserScore)}.
     */
    private fun buildScores(
        allFencers: List<String>,
        results: Map<String, Map<String, Pair<Int, Int>>>
    ): Map<String, Int> {
        val scores = mutableMapOf<String, Int>()
        for (i in allFencers.indices) {
            for (j in i + 1 until allFencers.size) {
                val a = allFencers[i]; val b = allFencers[j]
                val pair = results[a]?.get(b) ?: results[b]?.get(a)?.let { Pair(it.second, it.first) }
                if (pair != null) {
                    scores["$a-$b-A"] = pair.first
                    scores["$a-$b-B"] = pair.second
                }
            }
        }
        return scores
    }
}
