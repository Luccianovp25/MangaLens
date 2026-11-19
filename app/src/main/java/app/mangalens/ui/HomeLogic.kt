package app.mangalens.ui

import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import app.mangalens.translate.LlmEngine
import app.mangalens.translate.LlmHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/*
 * The home screen's decisions, kept free of Compose so every branch is a
 * plain unit test: which face the screen shows, whether a paste looks like
 * a key, and what Fuki says when a test line fails.
 */

internal enum class Stage { LOADING, SETUP, READY, RUNNING, PAUSED }

/**
 * The home screen shows one of five faces; running wins so a reader can
 * always stop. [setupOpen] is the latch that keeps the checklist up after
 * its last step completes, so the finished ticket and the "All set"
 * button are seen instead of the screen jumping away mid-tap.
 */
internal fun homeStage(
    loaded: Boolean,
    running: Boolean,
    paused: Boolean,
    overlayGranted: Boolean,
    aiReady: Boolean,
    setupOpen: Boolean,
): Stage = when {
    !loaded -> Stage.LOADING
    running -> if (paused) Stage.PAUSED else Stage.RUNNING
    setupOpen || !overlayGranted || !aiReady -> Stage.SETUP
    else -> Stage.READY
}

internal enum class PasteCheck { EMPTY, NOT_A_KEY, OK }

/**
 * Whether [raw] clipboard text could be an API key. It catches the usual
 * wrong copies — a sentence, a page URL, a few characters — without
 * guessing at any provider's format. A custom endpoint's token can be any
 * length, so only the shape checks apply to it.
 */
internal fun pasteCheck(provider: LlmProvider, raw: String): PasteCheck {
    val k = raw.trim()
    if (k.isEmpty()) return PasteCheck.EMPTY
    if (k.any { it.isWhitespace() }) return PasteCheck.NOT_A_KEY
    if (k.startsWith("http", ignoreCase = true)) return PasteCheck.NOT_A_KEY
    if (provider != LlmProvider.CUSTOM && k.length !in 20..300) return PasteCheck.NOT_A_KEY
    return PasteCheck.OK
}

/**
 * A soft hint only: Gemini keys have looked like this for years, but a
 * new format must never be refused, so a miss shows a note and the key is
 * still tried.
 */
internal fun looksLikeGeminiKey(k: String): Boolean =
    k.startsWith("AIza") && k.length in 35..45 && k.all { it.isLetterOrDigit() || it == '-' || it == '_' }

/**
 * What Fuki says when the test line fails, from the [cause] the pipeline
 * reports (see AiFailure) or "timeout". Each message names what the
 * reader can do about it, in [provider]'s name.
 */
internal fun friendlyTestFailure(cause: String, provider: String): String = when {
    cause == "key rejected" -> "$provider rechazó la API Key. Cópiala completa de nuevo y toca Pegar."
    cause == "network" -> "No puedo conectarme a internet. Comprueba tu conexión y vuelve a intentarlo."
    cause == "rate limited" ->
        "$provider solicita que esperes: el servicio está ocupado o se agotó la cuota gratuita de hoy. Vuelve a intentarlo en un minuto."
    cause == "model unavailable" || cause == "HTTP 404" ->
        "Ese modelo no está disponible para tu API Key. En Ajustes → Más opciones, vuelve a seleccionar Automático."
    cause == "declined" -> "$provider rechazó la línea de prueba. Es extraño. Vuelve a intentarlo."
    cause.startsWith("HTTP 5") -> "$provider está experimentando problemas. Vuelve a intentarlo dentro de unos instantes."
    cause == "timeout" -> "$provider tardó demasiado en responder. ¿Quieres volver a intentarlo?"
    else -> "Se produjo un error ($cause). ¿Quieres volver a intentarlo?"
}

/**
 * Why the Tweaks test cannot ask the AI yet, in Fuki's words, or null
 * when it can. A missing key or endpoint is caught here rather than sent:
 * a blank key comes back as a refusal and a blank URL as an odd error,
 * and neither tells the reader the real fix, which is to fill the field in.
 */
internal fun sayHiBlocker(s: AppSettings): String? = when {
    LlmHttp.setupNeeded(s) == null -> null
    s.provider == LlmProvider.CUSTOM -> "Primero añade arriba la URL de tu servidor y después prueba la conexión."
    else -> "Primero pega arriba tu API Key de ${LlmHttp.providerLabel(s)} y después prueba la conexión."
}

/** The one-line summary on the home screen's Tweaks sticker. */
internal fun tweaksSummary(s: AppSettings): String {
    val lang = when (s.sourceLang) {
        SourceLang.EN -> "Inglés"
        SourceLang.AUTO -> "Idioma automático"
        SourceLang.KO -> "Coreano"
        SourceLang.JA -> "Japonés"
        SourceLang.ZH -> "Chino"
    }
    val mode = if (s.mode == CaptureMode.AUTO) "Modo automático" else "Tocar para traducir"
    return lang + " · " + mode + " · " + LlmHttp.providerLabel(s)
}

internal fun apiKeyLabel(provider: LlmProvider): String = when (provider) {
    LlmProvider.GOOGLE_FREE -> "No requiere API Key"
    LlmProvider.ANTHROPIC -> "API Key de Anthropic"
    LlmProvider.OPENAI -> "API Key de OpenAI"
    LlmProvider.GEMINI -> "API Key de Gemini"
    LlmProvider.OPENROUTER -> "API Key de OpenRouter"
    LlmProvider.CUSTOM -> "Token Bearer (opcional)"
}

internal data class ProviderKeyHelp(val message: String, val linkLabel: String = "", val url: String? = null)

/** Where each provider's key comes from. Each key is saved for its own provider only. */
internal fun providerKeyHelp(provider: LlmProvider): ProviderKeyHelp = when (provider) {
    LlmProvider.GOOGLE_FREE -> { ProviderKeyHelp("Google Translate es gratis y no necesita API Key.") }
    LlmProvider.GEMINI -> ProviderKeyHelp(
        "Recomendado: es el más rápido aquí, tiene nivel gratuito y no requiere tarjeta. Se guarda exclusivamente para Gemini.",
        "Obtener API Key gratuita de Gemini ↗",
        "https://aistudio.google.com/apikey",
    )
    LlmProvider.ANTHROPIC -> ProviderKeyHelp(
        "Se guarda exclusivamente para Claude.",
        "Crear API Key de Anthropic ↗",
        "https://console.anthropic.com/settings/keys",
    )
    LlmProvider.OPENAI -> ProviderKeyHelp(
        "Se guarda exclusivamente para OpenAI.",
        "Crear API Key de OpenAI ↗",
        "https://platform.openai.com/api-keys",
    )
    LlmProvider.OPENROUTER -> ProviderKeyHelp(
        "Se guarda exclusivamente para OpenRouter.",
        "Crear API Key de OpenRouter ↗",
        "https://openrouter.ai/settings/keys",
    )
    LlmProvider.CUSTOM -> ProviderKeyHelp(
        "Token Bearer opcional para este endpoint. Las API Keys antiguas compartidas nunca se envían aquí; introduce la que corresponda a esta URL.",
    )
}

/** Where a new Gemini key is made; step 2 of setup sends the reader straight there. */
internal const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"

/**
 * The floating button's mark as copy writes it. A word joiner holds the
 * two characters together: a line may otherwise break between an
 * ideograph and a Latin letter, leaving "文" at the end of one line and
 * "A" at the start of the next.
 */
internal const val MARK = "文⁠A"

/** Fuki's tips, cycled one at a time on the home screen; the first explains hands-free mode. */
internal val TIPS = listOf(
    "Modo automático: te desplazas y espero; te detienes y traduzco. Así de sencillo.",
    "Mantén pulsada la burbuja $MARK para abrir el menú rápido: traducir ahora, ver el original y más opciones.",
    "Las pestañas privadas de Brave ocultan la página (se ve en negro). Utiliza una pestaña normal.",
    "La traducción nunca bloquea los toques. Puedes desplazarte normalmente sobre ella.",
    "Las líneas traducidas aparecen una por una, directamente desde la IA.",
    "Recuerdo los nombres mientras lees para mantener la coherencia de los personajes.",
    "¿Vas a empezar otra serie? Mantén pulsado $MARK → Nueva serie para borrar los nombres anteriores.",
    "Las páginas se envían únicamente al proveedor de IA que elegiste.",
    "¿Te gusta MangaLens? Apoya la versión oficial cuando esté disponible.",
)

/**
 * The tips for [mode]. In tap-to-translate mode the hands-free tip would
 * contradict the caption right above it, so that mode opens with its own.
 */
internal fun tipsFor(mode: CaptureMode): List<String> =
    if (mode == CaptureMode.AUTO) TIPS
    else listOf("Modo manual: abre una página, toca $MARK y la traduzco. El modo automático está en Ajustes.") + TIPS.drop(1)

/** The line Fuki letters to prove a key works: short, warm, and unmistakably Korean. */
internal const val SAMPLE = "괜찮아. 내가 지켜줄게."

/** Asks the AI in [s] to translate [SAMPLE]; the answer is the first proof a key works. */
internal suspend fun tryKey(s: AppSettings): String = withContext(Dispatchers.IO) {
    withTimeout(30_000) { LlmEngine(s).translate(listOf(SAMPLE), SourceLang.KO).first().trim() }
}
