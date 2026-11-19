package app.mangalens.translate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Temporary, local-only Gemini diagnostics. API credentials are never written.
 * Image bytes are represented by their size and SHA-256 so the request shape
 * can be audited without duplicating every manga page in the report.
 */
internal object GeminiDiagnostics {
    private val lock = Any()
    private val sequence = AtomicLong()
    @Volatile private var appContext: Context? = null
    private const val MAX_FILE_BYTES = 4L * 1024L * 1024L

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun newId(): String = "${System.currentTimeMillis()}-${sequence.incrementAndGet()}"

    fun recordRequest(
        id: String,
        model: String,
        body: JSONObject,
        endpoint: String = "streamGenerateContent?alt=sse",
    ) = runCatching {
        val safeBody = JSONObject(body.toString())
        val images = JSONArray()
        val contents = safeBody.optJSONArray("contents")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val parts = contents.optJSONObject(i)?.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    val inline = parts.optJSONObject(j)?.optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data", "")
                    if (data.isNotEmpty()) {
                        images.put(JSONObject()
                            .put("part", j)
                            .put("mimeType", inline.optString("mimeType"))
                            .put("base64Characters", data.length)
                            .put("sha256Base64", sha256(data))
                        )
                        inline.put("data", "[IMAGEN OMITIDA DEL INFORME]")
                    }
                }
            }
        }
        record("REQUEST", JSONObject()
            .put("requestId", id)
            .put("model", model)
            .put("endpoint", endpoint)
            .put("httpMethod", "POST")
            .put("contentEncoding", "gzip")
            .put("authorization", "x-goog-api-key: [REDACTADA]")
            .put("requestBody", safeBody)
            .put("imagePayloads", images)
            .put("note", "La API Key viaja en un encabezado y se excluye deliberadamente.")
        )
    }

    fun recordStreamEvent(id: String, model: String, payload: String) {
        record("STREAM_EVENT", JSONObject()
            .put("requestId", id)
            .put("model", model)
            .put("rawEvent", payload)
        )
    }

    fun recordResponse(id: String, model: String, elapsedMs: Long, finish: String, text: String) {
        record("RESPONSE", JSONObject()
            .put("requestId", id)
            .put("model", model)
            .put("httpStatus", 200)
            .put("elapsedMs", elapsedMs)
            .put("finishReason", finish)
            .put("visibleText", text)
        )
    }

    fun recordFailure(id: String, model: String, elapsedMs: Long, error: Throwable) {
        record("REQUEST_FAILURE", JSONObject()
            .put("requestId", id)
            .put("model", model)
            .put("elapsedMs", elapsedMs)
            .put("exception", error.javaClass.name)
            .put("message", error.message ?: "")
        )
    }

    fun event(readId: String, name: String, details: JSONObject = JSONObject()) {
        record("PIPELINE_EVENT", JSONObject()
            .put("readId", readId)
            .put("event", name)
            .put("details", details)
        )
    }

    fun recordHttpError(id: String?, model: String, code: Int, rawBody: String) {
        record("HTTP_ERROR", JSONObject()
            .put("requestId", id ?: "")
            .put("model", model)
            .put("httpStatus", code)
            .put("rawErrorBody", rawBody)
        )
    }

    fun exportText(): String = synchronized(lock) {
        val ctx = appContext ?: return@synchronized "Diagnóstico no inicializado."
        val old = File(ctx.cacheDir, "gemini-diagnostics.previous.jsonl")
        val current = File(ctx.cacheDir, "gemini-diagnostics.jsonl")
        buildString {
            appendLine("MANGALENS — INFORME TEMPORAL DE GEMINI")
            appendLine("Generado: ${System.currentTimeMillis()}")
            appendLine("Las API Keys no se registran. Las imágenes se identifican por tamaño y SHA-256.")
            if (old.exists()) append(old.readText())
            if (current.exists()) append(current.readText())
        }
    }

    fun entryCount(): Int = synchronized(lock) {
        val ctx = appContext ?: return@synchronized 0
        listOf("gemini-diagnostics.previous.jsonl", "gemini-diagnostics.jsonl")
            .sumOf { name -> File(ctx.cacheDir, name).takeIf { it.exists() }?.useLines { lines -> lines.count() } ?: 0 }
    }

    fun clear() = synchronized(lock) {
        appContext?.cacheDir?.let { dir ->
            File(dir, "gemini-diagnostics.jsonl").delete()
            File(dir, "gemini-diagnostics.previous.jsonl").delete()
        }
    }

    private fun record(type: String, fields: JSONObject) {
        synchronized(lock) {
            val dir = appContext?.cacheDir ?: return
            val file = File(dir, "gemini-diagnostics.jsonl")
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val previous = File(dir, "gemini-diagnostics.previous.jsonl")
                previous.delete()
                file.renameTo(previous)
            }
            val entry = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("type", type)
            val keys = fields.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                entry.put(key, fields.opt(key))
            }
            runCatching { file.appendText(entry.toString() + "\n", Charsets.UTF_8) }
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
