package app.mangalens.translate

import app.mangalens.ocr.Script
import app.mangalens.settings.SourceLang
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Zero-setup translation through Google's public web endpoint.
 * This is not an official, unlimited API: Google may throttle or change it.
 */
internal class GoogleFreeEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun translate(items: List<String>, lang: SourceLang): List<String> {
        if (items.isEmpty()) return emptyList()
        if (items.size > 1) runCatching { translateBatch(items, lang) }.getOrNull()?.let { return it }
        return coroutineScope {
            val semaphore = Semaphore(4)
            items.map { value -> async { semaphore.withPermit { translateOne(value, lang) } } }.map { it.await() }
        }
    }

    private suspend fun translateBatch(items: List<String>, lang: SourceLang): List<String>? {
        val rows = fetchRows(items.joinToString("\n"), lang)
        if (rows.isEmpty()) return null
        val boundaries = IntArray(items.size)
        var total = 0
        items.forEachIndexed { i, value -> total += squash(value).length; boundaries[i] = total }
        val builders = Array(items.size) { StringBuilder() }
        var consumed = 0
        var slot = 0
        for ((translated, original) in rows) {
            if (slot >= items.size) break
            builders[slot].append(translated)
            consumed += squash(original).length
            while (slot < items.size && consumed >= boundaries[slot]) slot++
        }
        if (slot < items.size || consumed < boundaries.last()) return null
        val out = builders.map { it.toString().replace(Regex("\\s+"), " ").trim() }
        if (out.any { it.isEmpty() || Script.cjkCount(it) > it.length * 0.4f }) return null
        return out
    }

    private suspend fun translateOne(value: String, lang: SourceLang): String {
        val rows = fetchRows(value, lang)
        return rows.joinToString("") { it.first }.trim().ifEmpty { value }
    }

    private suspend fun fetchRows(value: String, lang: SourceLang): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val source = when (lang) {
                SourceLang.EN -> "en"
                SourceLang.KO -> "ko"
                SourceLang.JA -> "ja"
                SourceLang.ZH -> "zh-CN"
                SourceLang.AUTO -> "auto"
            }
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=es&dt=t&ie=UTF-8&oe=UTF-8"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                .post(FormBody.Builder().add("q", value).build())
                .build()
            LlmHttp.await(client.newCall(request)).use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Google Translate HTTP ${response.code}")
                val body = response.body?.string() ?: throw IllegalStateException("Google Translate returned an empty response")
                val segments = JSONArray(body).optJSONArray(0) ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until segments.length()) {
                        val row = segments.optJSONArray(i) ?: continue
                        add(row.optString(0, "") to row.optString(1, ""))
                    }
                }
            }
        }

    private fun squash(value: String) = value.filterNot(Char::isWhitespace)
}
