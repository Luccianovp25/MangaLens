package app.mangalens.translate

import app.mangalens.ocr.Script
import app.mangalens.settings.SourceLang
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Zero-setup translation through Google's public web endpoint.
 * This is not an official, unlimited API: Google may throttle or change it.
 *
 * Requests are serialized across engine instances. In particular, a 429 from
 * the page batch must never be mistaken for a bad batch and fanned out into
 * several per-bubble requests.
 */
internal class GoogleFreeEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun translate(items: List<String>, lang: SourceLang): List<String> {
        if (items.isEmpty()) return emptyList()

        if (items.size > 1) {
            val batch = try {
                translateBatch(items, lang)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A rate limit is not a malformed batch. Do not amplify it.
                if (isRateLimited(failure)) throw failure
                null
            }
            if (batch != null) return batch
        }

        // A malformed batch may need individual requests. Keep them strictly
        // sequential; never launch a burst of concurrent requests.
        val out = ArrayList<String>(items.size)
        for (value in items) out += translateOne(value, lang)
        return out
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
            requestMutex.withLock {
                val now = System.currentTimeMillis()
                val blockedFor = blockedUntilMs - now
                if (blockedFor > 0L) {
                    throw IllegalStateException("Google Translate HTTP 429 (cooldown)")
                }

                val waitFor = MIN_REQUEST_GAP_MS - (now - lastRequestAtMs)
                if (waitFor > 0L) delay(waitFor)
                lastRequestAtMs = System.currentTimeMillis()

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
                    if (response.code == 429) {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            ?.coerceIn(15L, 300L) ?: DEFAULT_COOLDOWN_SECONDS
                        blockedUntilMs = System.currentTimeMillis() + retryAfter * 1_000L
                        throw IllegalStateException("Google Translate HTTP 429")
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Google Translate HTTP ${response.code}")
                    }
                    val body = response.body?.string()
                        ?: throw IllegalStateException("Google Translate returned an empty response")
                    val segments = JSONArray(body).optJSONArray(0) ?: return@withLock emptyList()
                    buildList {
                        for (i in 0 until segments.length()) {
                            val row = segments.optJSONArray(i) ?: continue
                            add(row.optString(0, "") to row.optString(1, ""))
                        }
                    }
                }
            }
        }

    private fun isRateLimited(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause.message.orEmpty().contains("HTTP 429")) return true
            cause = cause.cause
        }
        return false
    }

    private fun squash(value: String) = value.filterNot(Char::isWhitespace)

    private companion object {
        // One request at a time for the whole process, even across pages.
        val requestMutex = Mutex()
        @Volatile var blockedUntilMs = 0L
        @Volatile var lastRequestAtMs = 0L
        const val MIN_REQUEST_GAP_MS = 350L
        const val DEFAULT_COOLDOWN_SECONDS = 60L
    }
}
