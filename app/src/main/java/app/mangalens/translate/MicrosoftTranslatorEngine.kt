package app.mangalens.translate

import app.mangalens.settings.SourceLang
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Official Azure AI Translator Text API v3. The free F0 resource is
 * configured in Azure's Global location, so no regional header is needed.
 * Requests are batched and never retried automatically.
 */
internal class MicrosoftTranslatorEngine(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun translate(items: List<String>, lang: SourceLang): List<String> {
        if (items.isEmpty()) return emptyList()
        require(LlmHttp.cleanKey(apiKey).isNotEmpty()) { "Falta la API Key de Microsoft Translator" }
        return withContext(Dispatchers.IO) {
            buildList {
                for (chunk in requestChunks(items)) {
                    val source = sourceCode(lang)
                    val url = buildString {
                        append(ENDPOINT).append("?api-version=3.0&to=es")
                        if (source != null) append("&from=").append(source)
                    }
                    val body = JSONArray().apply {
                        chunk.forEach { put(JSONObject().put("Text", it)) }
                    }.toString().toRequestBody(JSON)
                    val request = Request.Builder()
                        .url(url)
                        .let { LlmHttp.keyHeader(it, "Ocp-Apim-Subscription-Key", apiKey) }
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .post(body)
                        .build()
                    LlmHttp.await(client.newCall(request)).use { response ->
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw IllegalStateException("Microsoft Translator HTTP ${response.code}: ${raw.take(240)}")
                        }
                        val rows = JSONArray(raw)
                        if (rows.length() != chunk.size) {
                            throw IllegalStateException("Microsoft Translator devolvió ${rows.length()} resultados para ${chunk.size} textos")
                        }
                        for (i in 0 until rows.length()) {
                            val translations = rows.optJSONObject(i)?.optJSONArray("translations")
                            val translated = translations?.optJSONObject(0)?.optString("text").orEmpty()
                            add(translated.ifBlank { chunk[i] })
                        }
                    }
                }
            }
        }
    }

    private fun requestChunks(items: List<String>): List<List<String>> {
        val chunks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var chars = 0
        for (item in items) {
            val size = item.length
            if (current.isNotEmpty() && (current.size >= MAX_ITEMS || chars + size > MAX_CHARS)) {
                chunks += current
                current = ArrayList()
                chars = 0
            }
            current += item
            chars += size
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun sourceCode(lang: SourceLang): String? = when (lang) {
        SourceLang.EN -> "en"
        SourceLang.KO -> "ko"
        SourceLang.JA -> "ja"
        SourceLang.ZH -> "zh-Hans"
        SourceLang.AUTO -> null
    }

    private companion object {
        const val ENDPOINT = "https://api.cognitive.microsofttranslator.com/translate"
        const val MAX_ITEMS = 100
        const val MAX_CHARS = 45_000
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
