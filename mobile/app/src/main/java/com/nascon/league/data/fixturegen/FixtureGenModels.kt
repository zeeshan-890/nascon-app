package com.nascon.league.data.fixturegen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Formats shown on the picker (F09). HYBRID is a client flow that ends in a knockout request. */
enum class GenFormat { ROUND_ROBIN, SWISS, SINGLE_ELIM, DOUBLE_ELIM, HYBRID }

object GenApiFormat {
    const val ROUND_ROBIN = "round_robin"
    const val SWISS = "swiss"
    const val SINGLE_ELIM = "single_elim"
    const val DOUBLE_ELIM = "double_elim"
    val all = listOf(ROUND_ROBIN, SWISS, SINGLE_ELIM, DOUBLE_ELIM)
}

// ---------------------------------------------------------------------------------------------
// Response DTOs (one tolerant shape for all four generators; every field has a safe default)
// ---------------------------------------------------------------------------------------------

@Serializable
data class GenRefDto(
    @SerialName("match_id") val matchId: String = "",
    @SerialName("slot") val slot: String = "",
)

@Serializable
data class GenMatchDto(
    @SerialName("match_id") val matchId: String = "",
    @SerialName("leg") val leg: Int = 1,
    @SerialName("home") val home: String? = null,
    @SerialName("away") val away: String? = null,
    @SerialName("status") val status: String = "scheduled",
    @SerialName("awarded_to") val awardedTo: String? = null,
    @SerialName("bracket") val bracket: String = "",
    @SerialName("round") val round: Int = 0,
    @SerialName("index") val index: Int = 0,
    @SerialName("home_seed") val homeSeed: Int? = null,
    @SerialName("away_seed") val awaySeed: Int? = null,
    @SerialName("bye") val bye: Boolean = false,
    @SerialName("winner") val winner: String? = null,
    @SerialName("loser") val loser: String? = null,
    @SerialName("winner_to") val winnerTo: GenRefDto? = null,
    @SerialName("loser_to") val loserTo: GenRefDto? = null,
    @SerialName("conditional") val conditional: Boolean = false,
    @SerialName("required") val required: Boolean? = null,
)

@Serializable
data class GenRoundDto(
    @SerialName("round") val round: Int = 0,
    @SerialName("matches") val matches: List<GenMatchDto> = emptyList(),
    @SerialName("byes") val byes: List<String> = emptyList(),
)

@Serializable
data class GenPairingDto(
    @SerialName("match_id") val matchId: String = "",
    @SerialName("home") val home: String = "",
    @SerialName("away") val away: String = "",
)

@Serializable
data class GenStandingDto(
    @SerialName("rank") val rank: Int = 0,
    @SerialName("team_id") val teamId: String = "",
    @SerialName("score") val score: Double = 0.0,
    @SerialName("buchholz") val buchholz: Double = 0.0,
)

@Serializable
data class GenSummaryDto(
    @SerialName("total") val total: Int = 0,
    @SerialName("scheduled") val scheduled: Int = 0,
    @SerialName("forfeit") val forfeit: Int = 0,
    @SerialName("voided") val voided: Int = 0,
)

@Serializable
data class GeneratedDto(
    @SerialName("format") val format: String = "",
    // round robin
    @SerialName("legs") val legs: Int = 1,
    @SerialName("team_count") val teamCount: Int = 0,
    @SerialName("round_count") val roundCount: Int = 0,
    @SerialName("rounds") val rounds: List<GenRoundDto> = emptyList(),
    @SerialName("summary") val summary: GenSummaryDto = GenSummaryDto(),
    // swiss
    @SerialName("round") val round: Int = 0,
    @SerialName("total_rounds") val totalRounds: Int = 0,
    @SerialName("pairings") val pairings: List<GenPairingDto> = emptyList(),
    @SerialName("bye") val bye: String? = null,
    @SerialName("standings") val standings: List<GenStandingDto> = emptyList(),
    // elimination
    @SerialName("bracket_size") val bracketSize: Int = 0,
    @SerialName("bracket_reset") val bracketReset: Boolean = false,
    @SerialName("winners") val winners: List<GenRoundDto> = emptyList(),
    @SerialName("losers") val losers: List<GenRoundDto> = emptyList(),
    @SerialName("grand_final") val grandFinal: List<GenMatchDto> = emptyList(),
)

/** Response of the (invented) save endpoint. */
@Serializable
data class FixtureSaveResultDto(
    @SerialName("created") val created: Int = 0,
    @SerialName("league_id") val leagueId: String = "",
)

// ---------------------------------------------------------------------------------------------
// Request model
// ---------------------------------------------------------------------------------------------

data class Withdrawal(val teamId: String, val afterRound: Int)

data class SwissHistoryMatch(val home: String, val away: String, val result: String)

data class SwissHistoryRound(val round: Int, val bye: String?, val matches: List<SwissHistoryMatch>)

/** Everything the generator accepts, as plain Kotlin. [toJson] builds the exact body of POST /audit/v1/fixtures. */
data class GenRequest(
    val format: String,
    val teams: List<String>,
    val legs: Int = 1,
    val withdrawalPolicy: String? = null,
    val withdrawals: List<Withdrawal> = emptyList(),
    val totalRounds: Int? = null,
    val swissRound: Int? = null,
    val history: List<SwissHistoryRound> = emptyList(),
    val thirdPlace: Boolean = false,
    val bracketReset: Boolean = false,
    /** match_id -> winning team id, for the bracket formats. */
    val results: Map<String, String> = emptyMap(),
    /** Client-only scheduling hints; sent to the save endpoint, never to the generator. */
    val startDate: String? = null,
    val matchDays: List<Int> = emptyList(),
) {
    fun params(): JsonObject = buildJsonObject {
        when (format) {
            GenApiFormat.ROUND_ROBIN -> {
                put("legs", legs)
                if (withdrawalPolicy != null) put("withdrawal_policy", withdrawalPolicy)
                if (withdrawals.isNotEmpty()) {
                    putJsonArray("withdrawals") {
                        withdrawals.forEach {
                            add(buildJsonObject { put("team_id", it.teamId); put("after_round", it.afterRound) })
                        }
                    }
                }
            }
            GenApiFormat.SWISS -> {
                if (totalRounds != null) put("total_rounds", totalRounds)
                if (swissRound != null) put("round", swissRound)
                if (history.isNotEmpty()) {
                    putJsonArray("history") {
                        history.forEach { h ->
                            add(
                                buildJsonObject {
                                    put("round", h.round)
                                    if (h.bye != null) put("bye", h.bye)
                                    putJsonArray("matches") {
                                        h.matches.forEach { m ->
                                            add(
                                                buildJsonObject {
                                                    put("home", m.home); put("away", m.away); put("result", m.result)
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            GenApiFormat.SINGLE_ELIM -> {
                if (thirdPlace) put("third_place_match", true)
                putResults(results)
            }
            GenApiFormat.DOUBLE_ELIM -> {
                if (bracketReset) put("bracket_reset", true)
                putResults(results)
            }
            else -> Unit
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putResults(r: Map<String, String>) {
        if (r.isEmpty()) return
        putJsonArray("results") {
            r.forEach { (id, w) -> add(buildJsonObject { put("match_id", id); put("winner", w) }) }
        }
    }

    /** Body for the generator. With [save] true it is the body of the save endpoint (generator request + save + hints). */
    fun toJson(save: Boolean = false): JsonObject = buildJsonObject {
        put("format", format)
        put("teams", buildJsonArray { teams.forEach { add(JsonPrimitive(it)) } })
        put("params", params())
        if (save) {
            put("save", true)
            if (startDate != null) put("start_date", startDate)
            if (matchDays.isNotEmpty()) put("match_days", buildJsonArray { matchDays.forEach { add(JsonPrimitive(it)) } })
        }
    }

    companion object {
        /** Tolerant reader used by the demo fake (mirrors what the server accepts). Unknown shapes become invalid fields. */
        fun fromJson(body: JsonObject): GenRequest {
            val teams = (body["teams"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: emptyList()
            val p = body["params"] as? JsonObject ?: JsonObject(emptyMap())
            fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
            fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            val withdrawals = (p["withdrawals"] as? JsonArray)?.mapNotNull {
                val o = it as? JsonObject ?: return@mapNotNull null
                val t = o["team_id"].str() ?: return@mapNotNull null
                val a = o["after_round"].int() ?: return@mapNotNull null
                Withdrawal(t, a)
            } ?: emptyList()
            val history = (p["history"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val ms = (o["matches"] as? JsonArray)?.mapNotNull { m ->
                    val mo = m as? JsonObject ?: return@mapNotNull null
                    SwissHistoryMatch(mo["home"].str() ?: "", mo["away"].str() ?: "", mo["result"].str() ?: "")
                } ?: emptyList()
                SwissHistoryRound(o["round"].int() ?: 0, o["bye"].str(), ms)
            } ?: emptyList()
            val results = (p["results"] as? JsonArray)?.mapNotNull {
                val o = it as? JsonObject ?: return@mapNotNull null
                val id = o["match_id"].str() ?: return@mapNotNull null
                val w = o["winner"].str() ?: return@mapNotNull null
                id to w
            }?.toMap() ?: emptyMap()
            return GenRequest(
                format = body["format"].str() ?: "",
                teams = teams,
                legs = p["legs"].int() ?: 1,
                withdrawalPolicy = p["withdrawal_policy"].str(),
                withdrawals = withdrawals,
                totalRounds = p["total_rounds"].int(),
                swissRound = p["round"].int(),
                history = history,
                thirdPlace = (p["third_place_match"] as? JsonPrimitive)?.booleanOrNull == true,
                bracketReset = (p["bracket_reset"] as? JsonPrimitive)?.booleanOrNull == true,
                results = results,
            )
        }
    }
}

/** Thrown by the pure generator / validator; the fake turns it into an HTTP 400 / 409 like the server. */
class GenException(val code: String, val fields: List<String> = emptyList(), message: String = code) : Exception(message)

/** Client-side mirrors of the server validation rules (validate.js) so the UI can explain problems before calling. */
object FixtureGenRules {
    private val idRe = Regex("^[A-Za-z0-9_.:-]{1,64}$")

    fun teamRange(format: String): IntRange = when (format) {
        GenApiFormat.ROUND_ROBIN -> 2..40
        GenApiFormat.SWISS -> 2..64
        GenApiFormat.SINGLE_ELIM -> 2..128
        GenApiFormat.DOUBLE_ELIM -> 3..128
        else -> 2..128
    }

    fun isValidId(id: String): Boolean = idRe.matches(id)

    fun teamCountOk(format: String, count: Int): Boolean = count in teamRange(format)

    fun nextPow2(n: Int): Int {
        var b = 1
        while (b < n) b *= 2
        return b
    }

    /** Swiss: server default ceil(log2 n); allowed 1..n-1. */
    fun defaultSwissRounds(n: Int): Int {
        if (n <= 1) return 1
        var r = 0
        var v = 1
        while (v < n) { v *= 2; r++ }
        return r.coerceAtLeast(1).coerceAtMost((n - 1).coerceAtLeast(1))
    }

    fun swissRoundsRange(n: Int): IntRange = 1..(n - 1).coerceAtLeast(1)

    /** Round robin round count for [n] teams and [legs]. */
    fun roundRobinRounds(n: Int, legs: Int): Int = (if (n % 2 == 1) n else n - 1) * (if (legs == 2) 2 else 1)

    /** Returns the list of invalid top-level / param field names, mirroring the server (empty = valid). */
    fun validate(req: GenRequest): List<String> {
        val bad = linkedSetOf<String>()
        if (req.format !in GenApiFormat.all) bad += "format"
        if (!req.teams.all(::isValidId) || req.teams.toSet().size != req.teams.size) bad += "teams"
        else if (req.format in GenApiFormat.all && !teamCountOk(req.format, req.teams.size)) bad += "teams"
        if (bad.isNotEmpty()) return bad.toList()
        val n = req.teams.size
        when (req.format) {
            GenApiFormat.ROUND_ROBIN -> {
                if (req.legs != 1 && req.legs != 2) bad += "params.legs"
                if (req.withdrawalPolicy != null && req.withdrawalPolicy != "void_all" && req.withdrawalPolicy != "forfeit_remaining") {
                    bad += "params.withdrawal_policy"
                }
                val max = roundRobinRounds(n, req.legs)
                val seen = HashSet<String>()
                for (w in req.withdrawals) {
                    if (w.teamId !in req.teams || w.afterRound !in 0..max || !seen.add(w.teamId)) bad += "params.withdrawals"
                }
            }
            GenApiFormat.SWISS -> {
                val total = req.totalRounds ?: defaultSwissRounds(n)
                if (total !in 1..(n - 1)) bad += "params.total_rounds"
                val round = req.swissRound ?: 1
                if (round < 1 || (bad.isEmpty() && round > total)) bad += "params.round"
                if (bad.isEmpty() && req.history.size != round - 1) bad += "params.history"
            }
        }
        return bad.toList()
    }
}
