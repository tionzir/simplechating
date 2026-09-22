package com.simplechating.jev

import android.util.Log
import com.simplechating.core.Analysis
import com.simplechating.core.ChatSnapshot
import com.simplechating.core.Choice
import com.simplechating.core.Prefs
import com.simplechating.core.RankedReply
import com.simplechating.core.Score
import com.simplechating.core.kb.ChatContext
import org.json.JSONObject

/**
 * The Jev judgment route: the 7 judgment questions in one call, and the ranking
 * question over already-drafted candidates.
 *
 * === SimpleChating 改版 ===
 * Supports TWO answer shapes so the same client works against:
 *
 *  1. Upstream Jev (OpenRouter / typesafe), which returns per question:
 *       {"choice":"...","confidence":0.9,"probabilities":{...}}
 *       {"score":7,"confidence":0.9,"legend":{...}}
 *       {"noul":0.83}
 *
 *  2. The local Termux Laya service (laya_serve `/judge`), which returns:
 *       {"type":"choice","distribution":{"a":0.6,"b":0.4},"choice":"a","confidence":0.6}
 *       {"type":"score","score":7,"distribution":{"0":0.1,...},"confidence":0.9}
 *       {"type":"noul","distribution":{"false":0.2,"true":0.8},"noul":0.8}
 *
 * The adapters below normalize both into the app's internal Choice/Score.
 */
class JudgeClient(private val prefs: Prefs) {

    /**
     * The 7 judgment questions (fast, ~1s). Errors are returned, not thrown.
     *
     * @param ctx D-stage knowledge context; null or empty means the request body
     *        is byte-for-byte what v1.2 sent.
     */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val start = System.currentTimeMillis()
        return try {
            val answers = postDecisions(
                snapshot, relationship, ctx,
                JevQuestions.judge()
            )
            Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = parseNoul(answers.optJSONObject("should_reply_now")),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = parseNoul(answers.optJSONObject("tension_resolved")),
                literalQuestion = parseNoul(answers.optJSONObject("literal_question")),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "判断接口请求失败")
        }
    }

    /** Ask the judge which of the candidate replies is best; throws on failure. */
    fun rank(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val answers = postDecisions(snapshot, relationship, ctx, questions)
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    /**
     * POST one decisions request, with the knowledge fields when there are any.
     *
     * Defensive retry: whether the live endpoint accepts the new `background` /
     * `history` state fields or rejects unknown ones with a 4xx is not verified
     * against production yet. If a request carrying them comes back 4xx, it is
     * sent again once without them, so an unverified field can degrade the
     * analysis but never break it.
     *
     * The local Laya service ignores the key entirely and is never retried as
     * "plain" — it accepts the enriched body directly.
     */
    private fun postDecisions(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?,
        questions: JSONObject
    ): JSONObject {
        val background = ctx?.background(relationship) ?: ""
        val history = ctx?.history ?: emptyList()
        val enriched = background.isNotBlank() || history.isNotEmpty()
        return try {
            send(JevQuestions.buildState(snapshot, relationship, background, history), questions)
        } catch (e: ApiException) {
            if (enriched && e.status != null && e.status in 400..499) {
                Log.w(TAG, "judge HTTP ${e.status} with background/history; retrying plain")
                send(JevQuestions.buildState(snapshot, relationship), questions)
            } else throw e
        }
    }

    private fun send(state: JSONObject, questions: JSONObject): JSONObject {
        val url = prefs.judgeEndpoint()
        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("state", state)
            .put("questions", questions)
        val resp = HttpJson.post(url, prefs.judgeKey, body, Route.JUDGE, HttpJson.headersFor(url))
        return resp.optJSONObject("answers") ?: JSONObject()
    }

    // ------------------------------------------------------------- adapters

    /**
     * Covers both shapes:
     *  - upstream: {"choice":k,"confidence":c,"probabilities":{k:p}}
     *  - laya    : {"type":"choice","choice":k,"confidence":c,"distribution":{k:p}}
     *
     * If neither `probabilities` nor `distribution` is present, but a `choice`
     * is, we synthesize a distribution that puts everything on that choice.
     */
    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        // upstream first, then the local Laya service.
        (o.optJSONObject("probabilities") ?: o.optJSONObject("distribution"))?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        var choice = o.optString("choice", "")
        var confidence = o.optDouble("confidence", 0.0)
        // Laya may omit `choice` on a parse hiccup; infer from the argmax.
        if (choice.isBlank() && probs.isNotEmpty()) {
            choice = probs.maxByOrNull { it.value }?.key ?: ""
        }
        if (confidence <= 0.0 && choice.isNotBlank()) {
            confidence = probs[choice] ?: 0.0
        }
        if (choice.isBlank() && probs.isEmpty()) return null
        return Choice(choice, confidence, probs)
    }

    /**
     * Covers both shapes:
     *  - upstream: {"score":7,"confidence":c,"legend":{"1":"...",...}}
     *  - laya    : {"type":"score","score":7,"distribution":{"0":p,...},"confidence":c}
     *
     * maxLevel comes from the legend when present, else from the distribution
     * key range, else defaults to 9 (the Jev danger_level scale).
     */
    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val dist = o.optJSONObject("distribution")
        val maxLevel = when {
            legend != null && legend.length() > 0 ->
                legend.keys().asSequence().mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 9
            dist != null && dist.length() > 0 ->
                dist.keys().asSequence().mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 9
            else -> 9
        }
        val raw = o.optDouble("score", Double.NaN)
        // No score at all: derive it from the distribution's argmax.
        val score = if (!raw.isNaN()) raw else argmaxKey(dist)?.toDoubleOrNull() ?: 0.0
        return Score(score, o.optDouble("confidence", 0.0), maxLevel)
    }

    /**
     * Covers both shapes of a yes/no question:
     *  - upstream: {"noul":0.83}
     *  - laya    : {"type":"noul","noul":0.83,"distribution":{"false":0.17,"true":0.83}}
     */
    private fun parseNoul(o: JSONObject?): Double? {
        o ?: return null
        if (o.has("noul")) return o.optDouble("noul", 0.0)
        val dist = o.optJSONObject("distribution") ?: return null
        if (dist.has("true")) return dist.optDouble("true", 0.0)
        return null
    }

    private fun argmaxKey(o: JSONObject?): String? {
        o ?: return null
        var best: String? = null
        var bestV = Double.NEGATIVE_INFINITY
        o.keys().forEach { k ->
            val v = o.optDouble(k, Double.NEGATIVE_INFINITY)
            if (v > bestV) { bestV = v; best = k }
        }
        return best
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        // Preferred shape: a per-candidate probability map.
        val probs = o?.optJSONObject("probabilities") ?: o?.optJSONObject("distribution")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        // If there is no usable probability at all, keep the draft order rather
        // than sorting everything to 0.0 (which would shuffle best-first).
        if (probs == null || probs.length() == 0) return list
        return list.sortedByDescending { it.prob }
    }

    companion object { private const val TAG = "JEVASSIST" }
}
