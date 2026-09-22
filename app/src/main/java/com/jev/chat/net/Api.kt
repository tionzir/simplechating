package com.jev.chat.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 与本地 Python 服务（~/laya_serve/server.py）通信。
 * 接口：GET /health，POST /judge，POST /reply（或 /draft）
 * 绝不自动发送，只取候选。
 */
object Api {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Health(val ok: Boolean, val mode: String, val modelDir: String)

    /** 探活 */
    fun health(baseUrl: String): Health? {
        return try {
            val req = Request.Builder().url("${baseUrl.trimEnd('/')}/health").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val j = JSONObject(body)
                Health(
                    ok = j.optBoolean("ok", false),
                    mode = j.optString("mode", "?"),
                    modelDir = j.optString("model_dir", "")
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 取候选回复。
     * 优先 POST /reply，失败则回退 /draft。
     * @param state 当前聊天上下文（最近消息拼接）
     * @param count 候选条数
     * @return 候选列表，失败返回空
     */
    fun reply(baseUrl: String, state: String, count: Int = 3): List<String> {
        val base = baseUrl.trimEnd('/')
        // 先 /reply
        postCandidates("$base/reply", state, count)?.let { if (it.isNotEmpty()) return it }
        // 回退 /draft
        postCandidates("$base/draft", state, count)?.let { if (it.isNotEmpty()) return it }
        return emptyList()
    }

    /** 判断接口（可选，用于打分场景） */
    fun judge(baseUrl: String, state: String): String? {
        return try {
            val payload = JSONObject().apply {
                put("state", state)
                put("questions", JSONObject())
            }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/judge")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun postCandidates(url: String, state: String, count: Int): List<String>? {
        return try {
            val payload = JSONObject().apply {
                put("state", state)
                put("answers", JSONArray())   // /reply 需要 answers
                put("count", count)
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val j = JSONObject(body)
                val arr = j.optJSONArray("candidates") ?: return emptyList()
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) out.add(s)
                }
                out
            }
        } catch (e: Exception) {
            null
        }
    }
}
