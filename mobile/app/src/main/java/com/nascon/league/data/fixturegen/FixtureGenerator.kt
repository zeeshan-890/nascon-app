package com.nascon.league.data.fixturegen

/**
 * Plain-Kotlin port of backend/src/domain/fixtures (all modules) used by the demo fake. Same ids, same shapes; the swiss
 * pairing uses an exact minimum-cost search (fine for the small leagues the demo uses).
 */
object FixtureGenerator {

    fun generate(req: GenRequest): GeneratedDto {
        val bad = FixtureGenRules.validate(req)
        if (bad.isNotEmpty()) throw GenException("VALIDATION_ERROR", bad)
        return when (req.format) {
            GenApiFormat.ROUND_ROBIN -> roundRobin(req)
            GenApiFormat.SWISS -> swiss(req)
            GenApiFormat.SINGLE_ELIM -> singleElim(req)
            else -> doubleElim(req)
        }
    }

    // ------------------------------------------------------------------ round robin

    private fun mod(a: Int, m: Int) = ((a % m) + m) % m

    internal class RrPair(val home: Int, val away: Int, val key: Int)
    internal class RrRound(val matches: List<RrPair>, val bye: Int?)

    /** One leg of the cyclic construction (circle method). Returns k rounds. */
    internal fun buildLeg(n: Int): List<RrRound> {
        val k = if (n % 2 == 1) n else n - 1
        val half = (k - 1) / 2
        val rounds = ArrayList<RrRound>()
        for (r in 0 until k) {
            val ms = ArrayList<RrPair>()
            var bye: Int? = null
            for (i in 0 until k) {
                val j = mod(r - i, k)
                if (i < j) {
                    val d = mod(j - i, k)
                    val iHome = d in 1..half
                    ms += if (iHome) RrPair(i, j, i) else RrPair(j, i, i)
                } else if (i == j) {
                    if (n % 2 == 1) bye = i
                    else ms += if (r % 2 == 0) RrPair(n - 1, i, i) else RrPair(i, n - 1, i)
                }
            }
            ms.sortBy { it.key }
            rounds += RrRound(ms, bye)
        }
        return rounds
    }

    private fun roundRobin(req: GenRequest): GeneratedDto {
        val teams = req.teams
        val n = teams.size
        val leg = buildLeg(n)
        val k = leg.size
        val legs = req.legs
        val policy = req.withdrawalPolicy ?: "forfeit_remaining"
        val withdrawn = req.withdrawals.associate { it.teamId to it.afterRound }
        var total = 0; var scheduled = 0; var forfeit = 0; var voided = 0
        val rounds = ArrayList<GenRoundDto>()
        for (l in 1..legs) {
            leg.forEachIndexed { idx, src ->
                val round = (l - 1) * k + idx + 1
                val matches = src.matches.mapIndexed { mi, m ->
                    val swap = l == 2
                    val home = teams[if (swap) m.away else m.home]
                    val away = teams[if (swap) m.home else m.away]
                    val wh = withdrawn[home]?.let { policy == "void_all" || round > it } ?: false
                    val wa = withdrawn[away]?.let { policy == "void_all" || round > it } ?: false
                    var status = "scheduled"
                    var awarded: String? = null
                    if (wh && wa) status = "voided"
                    else if (wh || wa) {
                        if (policy == "void_all") status = "voided"
                        else { status = "forfeit"; awarded = if (wh) away else home }
                    }
                    total++
                    when (status) { "scheduled" -> scheduled++; "forfeit" -> forfeit++; else -> voided++ }
                    GenMatchDto(
                        matchId = "RR-L$l-R$round-M${mi + 1}", leg = l, home = home, away = away,
                        status = status, awardedTo = awarded,
                    )
                }
                rounds += GenRoundDto(round, matches, src.bye?.let { listOf(teams[it]) } ?: emptyList())
            }
        }
        return GeneratedDto(
            format = "round_robin", legs = legs, teamCount = n, roundCount = k * legs, rounds = rounds,
            summary = GenSummaryDto(total, scheduled, forfeit, voided),
        )
    }

    // ------------------------------------------------------------------ swiss

    private class Standing(val score: IntArray, val rank: IntArray, val rows: List<GenStandingDto>)

    private fun swissStandings(teams: List<String>, history: List<SwissHistoryRound>): Standing {
        val n = teams.size
        val idx = teams.withIndex().associate { it.value to it.index }
        val score = IntArray(n) // half-points
        for (h in history) {
            h.bye?.let { score[idx.getValue(it)] += 2 }
            for (m in h.matches) {
                val a = idx.getValue(m.home); val b = idx.getValue(m.away)
                when (m.result) {
                    "home" -> score[a] += 2
                    "away" -> score[b] += 2
                    else -> { score[a] += 1; score[b] += 1 }
                }
            }
        }
        val buch = IntArray(n)
        for (h in history) for (m in h.matches) {
            val a = idx.getValue(m.home); val b = idx.getValue(m.away)
            buch[a] += score[b]; buch[b] += score[a]
        }
        val order = (0 until n).sortedWith(
            compareByDescending<Int> { score[it] }.thenByDescending { buch[it] }.thenBy { it },
        )
        val rank = IntArray(n)
        order.forEachIndexed { r, t -> rank[t] = r + 1 }
        val rows = order.mapIndexed { r, t -> GenStandingDto(r + 1, teams[t], score[t] / 2.0, buch[t] / 2.0) }
        return Standing(score, rank, rows)
    }

    private fun validateHistory(req: GenRequest, round: Int) {
        val idx = req.teams.toSet()
        if (req.history.size != round - 1) throw GenException("VALIDATION_ERROR", listOf("params.history"))
        req.history.forEachIndexed { i, h ->
            val seen = HashSet<String>()
            val ok = h.round == i + 1 &&
                (h.bye == null || (h.bye in idx && seen.add(h.bye))) &&
                h.matches.all {
                    it.result in listOf("home", "away", "draw") && it.home != it.away &&
                        it.home in idx && it.away in idx && seen.add(it.home) && seen.add(it.away)
                }
            if (!ok) throw GenException("VALIDATION_ERROR", listOf("params.history"))
        }
    }

    private fun swiss(req: GenRequest): GeneratedDto {
        val teams = req.teams
        val n = teams.size
        val total = req.totalRounds ?: FixtureGenRules.defaultSwissRounds(n)
        val round = req.swissRound ?: 1
        validateHistory(req, round)
        val standing = swissStandings(teams, req.history)
        val pairings: List<GenPairingDto>
        val bye: String?
        if (round == 1) {
            val m = n / 2
            pairings = (1..m).map { GenPairingDto("SW-R1-M$it", teams[it - 1], teams[it + m - 1]) }
            bye = if (n % 2 == 1) teams[n - 1] else null
        } else {
            val idx = teams.withIndex().associate { it.value to it.index }
            val met = HashSet<Long>()
            val hadBye = HashSet<Int>()
            for (h in req.history) {
                h.bye?.let { hadBye += idx.getValue(it) }
                for (m in h.matches) {
                    val a = idx.getValue(m.home); val b = idx.getValue(m.away)
                    met += key(a, b)
                }
            }
            val everyone = (0 until n).toList()
            val candidates: List<Int?> = if (n % 2 == 1) {
                everyone.filter { it !in hadBye }
                    .sortedWith(compareBy<Int> { standing.score[it] }.thenByDescending { it })
            } else listOf(null)
            var found: Pair<List<Pair<Int, Int>>, Int?>? = null
            for (byeIdx in candidates) {
                val rest = everyone.filter { it != byeIdx }
                val pairs = minCostMatching(rest, met, standing.score)
                if (pairs != null) { found = pairs to byeIdx; break }
            }
            val (pairs, byeIdx) = found ?: throw GenException("NO_VALID_PAIRING", listOf("round"))
            val oriented = pairs.map { (x, y) -> if (standing.rank[x] < standing.rank[y]) x to y else y to x }
                .sortedBy { standing.rank[it.first] }
            pairings = oriented.mapIndexed { i, (h, a) -> GenPairingDto("SW-R$round-M${i + 1}", teams[h], teams[a]) }
            bye = byeIdx?.let { teams[it] }
        }
        return GeneratedDto(
            format = "swiss", round = round, totalRounds = total, pairings = pairings, bye = bye,
            standings = standing.rows,
        )
    }

    private fun key(a: Int, b: Int): Long = minOf(a, b).toLong() * 1000L + maxOf(a, b)

    /** Exact minimum-cost perfect matching on [nodes] avoiding [met] pairs; null when none exists. */
    internal fun minCostMatching(nodes: List<Int>, met: Set<Long>, score: IntArray): List<Pair<Int, Int>>? {
        val m = nodes.size
        if (m == 0) return emptyList()
        if (m % 2 == 1) return null
        val memo = HashMap<Long, Int>()
        val inf = Int.MAX_VALUE / 2
        val choice = HashMap<Long, Int>()
        // mask bit set = already paired (use Long so up to 62 nodes work; search is exponential, demo sizes are small)
        fun solve(mask: Long): Int {
            val full = (1L shl m) - 1
            if (mask == full) return 0
            memo[mask]?.let { return it }
            var i = 0
            while (mask and (1L shl i) != 0L) i++
            var best = inf
            var bestJ = -1
            for (j in i + 1 until m) {
                if (mask and (1L shl j) != 0L) continue
                if (key(nodes[i], nodes[j]) in met) continue
                val sub = solve(mask or (1L shl i) or (1L shl j))
                if (sub >= inf) continue
                val c = Math.abs(score[nodes[i]] - score[nodes[j]]) + sub
                if (c < best) { best = c; bestJ = j }
            }
            memo[mask] = best
            choice[mask] = bestJ
            return best
        }
        if (solve(0L) >= inf) return null
        val out = ArrayList<Pair<Int, Int>>()
        var mask = 0L
        val full = (1L shl m) - 1
        while (mask != full) {
            var i = 0
            while (mask and (1L shl i) != 0L) i++
            val j = choice.getValue(mask)
            out += nodes[i] to nodes[j]
            mask = mask or (1L shl i) or (1L shl j)
        }
        return out
    }

    // ------------------------------------------------------------------ brackets

    private class M(val id: String, val bracket: String, val round: Int, val index: Int) {
        var home: String? = null
        var away: String? = null
        var homeSeed: Int? = null
        var awaySeed: Int? = null
        var bye = false
        var winner: String? = null
        var loser: String? = null
        var winnerTo: GenRefDto? = null
        var loserTo: GenRefDto? = null
        var conditional = false
        var required: Boolean? = null

        fun dto() = GenMatchDto(
            matchId = id, bracket = bracket, round = round, index = index, home = home, away = away,
            homeSeed = homeSeed, awaySeed = awaySeed, bye = bye, winner = winner, loser = loser,
            winnerTo = winnerTo, loserTo = loserTo, conditional = conditional, required = required,
        )
    }

    private class Ctx {
        val matches = LinkedHashMap<String, M>()
        val dead = HashSet<String>()
        val deadMatches = HashSet<String>()
    }

    private fun ref(id: String, slot: String) = GenRefDto(id, slot)
    private fun slotOf(i: Int) = if (i % 2 == 1) "home" else "away"

    internal fun seedOrder(size: Int): List<Int> {
        var order = listOf(1, 2)
        while (order.size < size) {
            val newSize = order.size * 2
            order = order.flatMap { listOf(it, newSize + 1 - it) }
        }
        return order
    }

    private fun place(ctx: Ctx, target: GenRefDto, team: String?, seed: Int?) {
        val m = ctx.matches.getValue(target.matchId)
        if (m.conditional) return
        if (target.slot == "home") { m.home = team; m.homeSeed = seed } else { m.away = team; m.awaySeed = seed }
    }

    private fun decide(ctx: Ctx, m: M, winner: String) {
        val winnerIsHome = winner == m.home
        val other = if (winnerIsHome) m.away else m.home
        m.winner = winner
        m.loser = other
        m.winnerTo?.let { place(ctx, it, winner, if (winnerIsHome) m.homeSeed else m.awaySeed) }
        m.loserTo?.let {
            if (other == null) ctx.dead += "${it.matchId}:${it.slot}"
            else place(ctx, it, other, if (winnerIsHome) m.awaySeed else m.homeSeed)
        }
    }

    private fun buildTree(
        ctx: Ctx, prefix: String, bracket: String, teams: List<String>, size: Int,
        finalWinnerTo: GenRefDto?, loserTo: ((Int, Int, Int) -> GenRefDto?)?,
    ): List<List<M>> {
        val k = Integer.numberOfTrailingZeros(size)
        val rounds = ArrayList<List<M>>()
        for (r in 1..k) {
            val count = size / (1 shl r)
            val list = ArrayList<M>()
            for (i in 1..count) {
                val m = M("$prefix${r}M$i", bracket, r, i)
                m.winnerTo = if (r < k) ref("$prefix${r + 1}M${(i + 1) / 2}", slotOf(i)) else finalWinnerTo
                m.loserTo = loserTo?.invoke(r, i, k)
                ctx.matches[m.id] = m
                list += m
            }
            rounds += list
        }
        val order = seedOrder(size)
        val n = teams.size
        rounds[0].forEachIndexed { idx, m ->
            val a = order[2 * idx]; val b = order[2 * idx + 1]
            val lo = minOf(a, b); val hi = maxOf(a, b)
            m.home = teams[lo - 1]; m.homeSeed = lo
            if (hi <= n) { m.away = teams[hi - 1]; m.awaySeed = hi } else m.bye = true
        }
        return rounds
    }

    private fun resolveByes(ctx: Ctx, first: List<M>) {
        for (m in first) if (m.bye) decide(ctx, m, m.home!!)
    }

    private fun applyResults(ctx: Ctx, results: Map<String, String>, afterApply: () -> Unit) {
        val bad = GenException("VALIDATION_ERROR", listOf("params.results"))
        for (id in results.keys) if (id !in ctx.matches) throw bad
        var pending = results.entries.toList()
        while (pending.isNotEmpty()) {
            val next = ArrayList<Map.Entry<String, String>>()
            for (r in pending) {
                val m = ctx.matches.getValue(r.key)
                if (m.winner != null) {
                    if (!m.bye || r.value != m.winner) throw bad
                } else if (m.home != null && m.away != null && (!m.conditional || m.required == true)) {
                    if (r.value != m.home && r.value != m.away) throw bad
                    decide(ctx, m, r.value)
                    afterApply()
                } else next += r
            }
            if (next.size == pending.size) throw bad
            pending = next
        }
    }

    private fun singleElim(req: GenRequest): GeneratedDto {
        val teams = req.teams
        val size = FixtureGenRules.nextPow2(teams.size)
        val k = Integer.numberOfTrailingZeros(size)
        val withThird = req.thirdPlace && size >= 4
        val ctx = Ctx()
        val rounds = buildTree(
            ctx, "R", "main", teams, size, null,
        ) { r, i, _ ->
            if (withThird && k > 1 && r == k - 1) ref("TP", if (i == 1) "home" else "away") else null
        }.map { it.toMutableList() }
        if (withThird) {
            val tp = M("TP", "third_place", k, 1)
            ctx.matches["TP"] = tp
            rounds[k - 1].add(tp)
        }
        resolveByes(ctx, rounds[0])
        if (req.results.isNotEmpty()) applyResults(ctx, req.results) {}
        return GeneratedDto(
            format = "single_elim", bracketSize = size,
            rounds = rounds.mapIndexed { i, ms -> GenRoundDto(i + 1, ms.map { it.dto() }) },
        )
    }

    private fun doubleElim(req: GenRequest): GeneratedDto {
        val teams = req.teams
        val reset = req.bracketReset
        val size = FixtureGenRules.nextPow2(teams.size)
        val k = Integer.numberOfTrailingZeros(size)
        val ctx = Ctx()
        val winners = buildTree(
            ctx, "WR", "winners", teams, size, ref("GF1", "home"),
        ) { r, i, _ ->
            if (r == 1) ref("LR1M${(i + 1) / 2}", slotOf(i)) else ref("LR${2 * r - 2}M$i", "away")
        }
        val losers = ArrayList<List<M>>()
        for (round in 1..(2 * (k - 1))) {
            val j = (round + 1) / 2
            val count = size / (1 shl (j + 1))
            val list = ArrayList<M>()
            for (i in 1..count) {
                val m = M("LR${round}M$i", "losers", round, i)
                m.winnerTo = when {
                    round == 2 * (k - 1) -> ref("GF1", "away")
                    round % 2 == 1 -> ref("LR${round + 1}M$i", "home")
                    else -> ref("LR${round + 1}M${(i + 1) / 2}", slotOf(i))
                }
                ctx.matches[m.id] = m
                list += m
            }
            losers += list
        }
        val gf1 = M("GF1", "grand_final", 1, 1)
        val grand = arrayListOf(gf1)
        var gf2: M? = null
        if (reset) {
            gf1.winnerTo = ref("GF2", "home")
            gf1.loserTo = ref("GF2", "away")
            gf2 = M("GF2", "grand_final", 2, 1).also { it.conditional = true }
            grand += gf2
        }
        for (m in grand) ctx.matches[m.id] = m
        for (m in ctx.matches.values) m.required = if (m.conditional) null else true

        val loserMatches = losers.flatten()
        fun settle() {
            var changed = true
            while (changed) {
                changed = false
                for (m in loserMatches) {
                    if (m.winner == null && m.id !in ctx.deadMatches) {
                        val homeDead = "${m.id}:home" in ctx.dead
                        val awayDead = "${m.id}:away" in ctx.dead
                        if (homeDead && awayDead) {
                            ctx.deadMatches += m.id
                            m.bye = true
                            ctx.dead += "${m.winnerTo!!.matchId}:${m.winnerTo!!.slot}"
                            changed = true
                        } else if ((homeDead && m.away != null) || (awayDead && m.home != null)) {
                            m.bye = true
                            decide(ctx, m, (if (homeDead) m.away else m.home)!!)
                            changed = true
                        }
                    }
                }
            }
            gf2?.let {
                it.home = gf1.home; it.away = gf1.away; it.homeSeed = gf1.homeSeed; it.awaySeed = gf1.awaySeed
                it.required = if (gf1.winner == null) null else gf1.winner == gf1.away
            }
        }
        resolveByes(ctx, winners[0])
        settle()
        if (req.results.isNotEmpty()) applyResults(ctx, req.results) { settle() }
        return GeneratedDto(
            format = "double_elim", bracketSize = size, bracketReset = reset,
            winners = winners.mapIndexed { i, ms -> GenRoundDto(i + 1, ms.map { it.dto() }) },
            losers = losers.mapIndexed { i, ms -> GenRoundDto(i + 1, ms.map { it.dto() }) },
            grandFinal = grand.map { it.dto() },
        )
    }
}
