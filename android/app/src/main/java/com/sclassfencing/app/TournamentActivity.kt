package com.sclassfencing.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.sclassfencing.app.databinding.ActivityTournamentBinding

// Shared tournament state (in-memory for now)
object TournamentState {
    val fencers = mutableListOf<String>()
    val poules = mutableListOf<Poule>()
    val scores = mutableMapOf<String, Int>()
    val seeding = mutableListOf<SeedEntry>()
    val tableau = mutableListOf<Pair<SeedEntry?, SeedEntry?>>()
}

data class Poule(val id: Int, val fencers: List<String>)
data class SeedEntry(val name: String, val wins: Int, val indicator: Int)

class TournamentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTournamentBinding

    private val tabs = listOf("Register", "Poules", "Seeding", "Tableau")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTournamentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Tournament Manager"

        binding.viewPager.adapter = TournamentPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = tabs[pos]
        }.attach()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

private class TournamentPagerAdapter(activity: TournamentActivity) :
    FragmentStateAdapter(activity) {
    override fun getItemCount() = 4
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> RegisterFragment()
        1 -> PoulesFragment()
        2 -> SeedingFragment()
        3 -> TableauFragment()
        else -> RegisterFragment()
    }
}

// ── Register Tab ──────────────────────────────────────────────────────────────

class RegisterFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_register, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etName = view.findViewById<EditText>(R.id.etFencerName)
        val btnAdd = view.findViewById<Button>(R.id.btnAddFencer)
        val lvFencers = view.findViewById<ListView>(R.id.lvFencers)
        val btnGenerate = view.findViewById<Button>(R.id.btnGeneratePoules)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, TournamentState.fencers)
        lvFencers.adapter = adapter

        btnAdd.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isNotEmpty() && !TournamentState.fencers.contains(name)) {
                TournamentState.fencers.add(name)
                adapter.notifyDataSetChanged()
                etName.text.clear()
            }
        }

        btnGenerate.setOnClickListener {
            if (TournamentState.fencers.size < 2) {
                Toast.makeText(requireContext(), "Add at least 2 fencers", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            generatePoules()
            Toast.makeText(requireContext(), "Poules generated!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generatePoules() {
        val shuffled = TournamentState.fencers.shuffled()
        val mid = (shuffled.size + 1) / 2
        TournamentState.poules.clear()
        TournamentState.poules.add(Poule(1, shuffled.subList(0, mid)))
        if (shuffled.size > mid) {
            TournamentState.poules.add(Poule(2, shuffled.subList(mid, shuffled.size)))
        }
        TournamentState.scores.clear()
    }
}

// ── Poules Tab ────────────────────────────────────────────────────────────────

class PoulesFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_poules, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val container = view.findViewById<LinearLayout>(R.id.poulesContainer)
        container.removeAllViews()

        if (TournamentState.poules.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No poules yet. Go to Register tab and generate poules."
            tv.setPadding(24, 24, 24, 24)
            container.addView(tv)
            return
        }

        for (poule in TournamentState.poules) {
            val header = TextView(requireContext())
            header.text = "Poule ${poule.id}"
            header.textSize = 16f
            header.setPadding(8, 16, 8, 8)
            container.addView(header)

            val fencers = poule.fencers
            for (i in fencers.indices) {
                for (j in i + 1 until fencers.size) {
                    val row = LinearLayout(requireContext())
                    row.orientation = LinearLayout.HORIZONTAL
                    row.setPadding(8, 4, 8, 4)

                    val keyA = "${fencers[i]}-${fencers[j]}-A"
                    val keyB = "${fencers[i]}-${fencers[j]}-B"

                    val tvA = TextView(requireContext()).apply { text = fencers[i]; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                    val etA = EditText(requireContext()).apply {
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        hint = "0-5"
                        layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setText(TournamentState.scores[keyA]?.toString() ?: "")
                    }
                    etA.addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) {
                            TournamentState.scores[keyA] = s.toString().toIntOrNull() ?: 0
                        }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })

                    val tvVs = TextView(requireContext()).apply { text = " vs "; setPadding(8, 0, 8, 0) }

                    val etB = EditText(requireContext()).apply {
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        hint = "0-5"
                        layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setText(TournamentState.scores[keyB]?.toString() ?: "")
                    }
                    etB.addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) {
                            TournamentState.scores[keyB] = s.toString().toIntOrNull() ?: 0
                        }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })

                    val tvB = TextView(requireContext()).apply { text = fencers[j]; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setPadding(8, 0, 0, 0) }

                    row.addView(tvA); row.addView(etA); row.addView(tvVs); row.addView(etB); row.addView(tvB)
                    container.addView(row)
                }
            }
        }
    }
}

// ── Seeding Tab ───────────────────────────────────────────────────────────────

class SeedingFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_seeding, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val btnCalc = view.findViewById<Button>(R.id.btnCalculateSeeding)
        val lvSeeding = view.findViewById<ListView>(R.id.lvSeeding)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf<String>())
        lvSeeding.adapter = adapter

        fun refreshList() {
            adapter.clear()
            TournamentState.seeding.forEachIndexed { i, s ->
                adapter.add("${i + 1}. ${s.name}  W:${s.wins}  Ind:${s.indicator}")
            }
            adapter.notifyDataSetChanged()
        }

        refreshList()

        btnCalc.setOnClickListener {
            calculateSeeding()
            buildTableau()
            refreshList()
        }
    }

    private fun calculateSeeding() {
        val stats = mutableMapOf<String, IntArray>() // [wins, indicator]
        for (poule in TournamentState.poules) {
            val fencers = poule.fencers
            for (i in fencers.indices) {
                for (j in i + 1 until fencers.size) {
                    val a = fencers[i]; val b = fencers[j]
                    val aScore = TournamentState.scores["$a-$b-A"] ?: 0
                    val bScore = TournamentState.scores["$a-$b-B"] ?: 0
                    val sa = stats.getOrPut(a) { intArrayOf(0, 0) }
                    val sb = stats.getOrPut(b) { intArrayOf(0, 0) }
                    if (aScore > bScore) sa[0]++ else if (bScore > aScore) sb[0]++
                    sa[1] += aScore - bScore
                    sb[1] += bScore - aScore
                }
            }
        }
        TournamentState.seeding.clear()
        stats.entries
            .sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[0] }
                .thenByDescending { it.value[1] })
            .mapTo(TournamentState.seeding) { SeedEntry(it.key, it.value[0], it.value[1]) }
    }

    private fun buildTableau() {
        TournamentState.tableau.clear()
        val seeds = TournamentState.seeding
        var i = 0
        while (i < seeds.size) {
            TournamentState.tableau.add(Pair(seeds.getOrNull(i), seeds.getOrNull(i + 1)))
            i += 2
        }
    }
}

// ── Tableau Tab ───────────────────────────────────────────────────────────────

class TableauFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_tableau, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val lvTableau = view.findViewById<ListView>(R.id.lvTableau)
        val items = TournamentState.tableau.mapIndexed { i, (a, b) ->
            "Match ${i + 1}: ${a?.name ?: "TBD"} vs ${b?.name ?: "TBD"}"
        }
        lvTableau.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, items)
    }
}
