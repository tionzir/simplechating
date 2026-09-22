package com.smiplechating.net

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.smiplechating.Bus
import com.smiplechating.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** ---- 与本地服务 (~/laya_serve/server.py) 约定的数据模型 ---- */

data class JudgeReq(
    val state: String,
    val questions: Map<String, Question> = emptyMap()
)

data class Question(
    val type: String,
    val instructions: String = "",
    val criteria: List<String> = emptyList()
)

data class JudgeResp(
    val model: String = "",
    val answers: Map<String, Any> = emptyMap(),
    val usage: Map<String, Any> = emptyMap()
)

data class ReplyReq(
    val state: String,
    val answers: Map<String, Any> = emptyMap()
)

data class ReplyResp(
    val candidates: List<String> = emptyList()
)

/** 服务返回的 /health */
data class HealthResp(
    val ok: Boolean = false,
    val mode: String = "",
    @SerializedName("model_dir") val modelDir: String = ""
)

/**
 * 本地服务客户端。
 * 仅与 127.0.0.1:8765 之类的回环地址通信（可在设置里关闭该限制）。
 */
object Api {
    private val gson = Gson()

    private fun client(ctx: Context): OkHttpClient {
        val t = Prefs.timeoutMs(ctx)
        return OkHttpClient.Builder()
            .connectTimeout(t, TimeUnit.MILLISECONDS)
            .readTimeout(t, TimeUnit.MILLISECONDS)
            .writeTimeout(t, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun base(ctx: Context): String = Prefs.serverBase(ctx).trimEnd('/')

    private fun checkAllowed(ctx: Context, url: String): String? {
        if (!Prefs.localOnly(ctx)) return null
        return if (url.contains("127.0.0.1") || url.contains("localhost") || url.contains("[::1]")) {
            null
        } else {
            "当前设置为「仅本地」，已拒绝访问：$url"
        }
    }

    private suspend fun post(ctx: Context, path: String, body: String): String? =
        withContext(Dispatchers.IO) {
            val url = base(ctx) + path
            checkAllowed(ctx, url)?.let {
                Bus.log(it)
                return@withContext null
            }
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client(ctx).newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        Bus.log("HTTP ${resp.code} $path -> $text")
                        return@withContext null
                    }
                    text
                }
            } catch (e: Exception) {
                Bus.log("请求失败 $path: ${e.javaClass.simpleName} ${e.message}")
                null
            }
        }

    suspend fun health(ctx: Context): HealthResp? {
        val url = base(ctx) + "/health"
        checkAllowed(ctx, url)?.let {
            Bus.log(it)
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).get().build()
                client(ctx).newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        Bus.log("HTTP ${resp.code} /health")
                        return@withContext null
                    }
                    runCatching { gson.fromJson(text, HealthResp::class.java) }.getOrNull()
                }
            } catch (e: Exception) {
                Bus.log("健康检查失败: ${e.javaClass.simpleName} ${e.message}")
                null
            }
        }
    }

    /** 判断：把聊天上下文交给服务，返回模型信息 */
    suspend fun judge(ctx: Context, state: String): JudgeResp? {
        val body = gson.toJson(JudgeReq(state = state))
        val text = post(ctx, "/judge", body) ?: return null
        return runCatching { gson.fromJson(text, JudgeResp::class.java) }.getOrNull()
    }

    /** 生成候选回复 */
    suspend fun reply(ctx: Context, state: String): List<String> {
        val body = gson.toJson(ReplyReq(state = state))
        val text = post(ctx, "/reply", body) ?: return emptyList()
        val resp = runCatching { gson.fromJson(text, ReplyResp::class.java) }.getOrNull()
        return resp?.candidates ?: emptyList()
    }
}
