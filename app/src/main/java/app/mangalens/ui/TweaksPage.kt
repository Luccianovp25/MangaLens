package app.mangalens.ui

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mangalens.scroll.ScrollPace
import app.mangalens.gaps.ShadeLevel
import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import app.mangalens.translate.GeminiDiagnostics
import app.mangalens.translate.LlmHttp
import app.mangalens.translate.ModelCatalog
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where Tweaks opens: at the top, at the Your AI card (a missing or refused
 * key, a server to set up), at the other providers, unfolded under More
 * options, or at Auto-scroll (its switch in Accessibility is off).
 */
internal enum class TweaksTarget { TOP, AI, OTHER_AI, SCROLL }

/**
 * Tweaks, kept to what a reader actually changes: the language, hands-free
 * or tap, auto-scroll and how it goes, the size of the lettering and
 * whether the original may show through it, and whether the AI's key
 * works. That is all the page shows. Everything else —
 * timing, data, the thinking setting, the model, other AI providers,
 * diagnostics — has defaults that are right for nearly everyone, and waits
 * folded under More options, where it can be found without being in the
 * way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TweaksPage(
    settings: AppSettings,
    sink: SettingsSink,
    drafts: AiDrafts,
    sayHi: SayHi,
    target: TweaksTarget,
    versionName: String,
    solidOffered: Boolean,
    solidLettering: Boolean,
    ghostsOffered: Boolean,
    onTurnOnSolid: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onClose: () -> Unit,
    autoScrollOn: Boolean,
    onTurnOnAutoScroll: () -> Unit,
) {
    val pop = LocalPop.current
    var moreOpen by rememberSaveable { mutableStateOf(target == TweaksTarget.OTHER_AI) }
    val scrollRequester = remember { BringIntoViewRequester() }
    val aiRequester = remember { BringIntoViewRequester() }
    val otherAiRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(target) {
        when (target) {
            TweaksTarget.TOP -> Unit
            TweaksTarget.AI -> {
                delay(250)
                aiRequester.bringIntoView()
            }
            TweaksTarget.OTHER_AI -> {
                moreOpen = true
                delay(250)
                otherAiRequester.bringIntoView()
            }
            TweaksTarget.SCROLL -> {
                delay(250)
                scrollRequester.bringIntoView()
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ajustes",
                        style = MaterialTheme.typography.headlineSmall,
                        color = pop.ink,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Cerrar ajustes", tint = pop.ink)
                    }
                }
                Text(
                    "La configuración predeterminada funciona bien. En general, no hace falta cambiar nada más.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pop.inkSoft,
                )

                ReadingSection(settings, sink, columns = if (narrow) 2 else 4)
                AutoScrollSection(
                    settings, sink, autoScrollOn, onTurnOnAutoScroll, onOpenAppInfo,
                    stacked = narrow, modifier = Modifier.bringIntoViewRequester(scrollRequester),
                )
                TextSizeSection(settings, sink)
                if (solidOffered) SolidLetteringRow(solidLettering, onTurnOnSolid, onOpenAppInfo, stacked = narrow)
                if (ghostsOffered && !solidLettering) NoGhostsRow(settings, sink)
                YourAiSection(drafts, sayHi, Modifier.bringIntoViewRequester(aiRequester))

                Spacer(Modifier.height(28.dp))
                MoreOptions(
                    settings, sink, drafts,
                    open = moreOpen,
                    onToggle = { moreOpen = !moreOpen },
                    otherAi = Modifier.bringIntoViewRequester(otherAiRequester),
                )
                GeminiDiagnosticsSection()

                Spacer(Modifier.height(32.dp))
                Text(
                    listOf("MangaLens", versionName).filter { it.isNotEmpty() }.joinToString(" ") +
                        " · ¿Te gusta MangaLens? Apoya la versión oficial cuando esté disponible.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pop.inkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}


/** Temporary local report controls; this section is removed after Gemini is diagnosed. */
@Composable
private fun GeminiDiagnosticsSection() {
    val clipboard = LocalClipboardManager.current
    val pop = LocalPop.current
    GroupTitle("Informe temporal de Gemini")
    Helper("Registra solicitudes, respuestas, errores HTTP, tiempos, reintentos y cambios de modelo. Se guarda solo en este dispositivo. Las API Keys no se incluyen y las imágenes se identifican por huella, sin copiar sus bytes.")
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Copiar informe",
            color = pop.ink,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f).clickable {
                clipboard.setText(AnnotatedString(GeminiDiagnostics.exportText()))
            }.padding(vertical = 12.dp),
        )
        Text(
            "Borrar informe",
            color = pop.inkSoft,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable {
                GeminiDiagnostics.clear()
            }.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = LocalPop.current.inkSoft,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
}

/** A heading inside More options: smaller than a section, to keep the fold one piece. */
@Composable
private fun GroupTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = LocalPop.current.ink,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Helper(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalPop.current.inkSoft, modifier = modifier)
}

@Composable
private fun ReadingSection(settings: AppSettings, sink: SettingsSink, columns: Int) {
    val pop = LocalPop.current
    SectionTitle("Lectura")
    Text("Idioma del texto de origen", style = MaterialTheme.typography.titleMedium, color = pop.ink)
    Spacer(Modifier.height(8.dp))
    PopTiles(
        listOf(
            PopTile(SourceLang.EN, "Inglés", glyph = "EN"),
            PopTile(SourceLang.AUTO, "Automático", glyph = "★"),
            PopTile(SourceLang.KO, "Coreano", glyph = "한"),
            PopTile(SourceLang.JA, "Japonés", glyph = "日"),
            PopTile(SourceLang.ZH, "Chino", glyph = "中"),
        ),
        selected = settings.sourceLang,
        onSelect = sink::setSourceLang,
        columns = columns,
    )
    Spacer(Modifier.height(8.dp))
    Helper(
        when (settings.sourceLang) {
            SourceLang.EN -> "Se traducirá del inglés al español."
            SourceLang.AUTO -> "Detecto automáticamente el idioma de cada página."
            SourceLang.KO -> "Se traducirá del coreano al español (manhwa)."
            SourceLang.JA -> "Se traducirá del japonés al español (manga)."
            SourceLang.ZH -> "Se traducirá del chino al español (manhua)."
        }
    )
    Spacer(Modifier.height(12.dp))
    val handsFree = settings.mode == CaptureMode.AUTO
    PopToggleRow(
        "Modo automático",
        if (handsFree) "Traduzco cada página cuando dejas de desplazarte."
        else "Espero a que toques la burbuja $MARK para traducir. Así ahorras datos.",
        handsFree,
        { sink.setMode(if (it) CaptureMode.AUTO else CaptureMode.MANUAL) },
    )
    Spacer(Modifier.height(12.dp))
    PopToggleRow(
        "Oscurecer márgenes",
        if (settings.darkGaps) "Los espacios blancos entre las viñetas se oscurecen. El dibujo y los textos originales no se modifican. Los niveles Oscuro y Negro requieren activar Texto opaco en Accesibilidad; de lo contrario, Android deja la superposición ligeramente transparente."
        else "Oscurece los espacios blancos entre las viñetas para leer de noche. También puedes activarlo manteniendo pulsado el botón $MARK.",
        settings.darkGaps,
        sink::setDarkGaps,
    )
    if (settings.darkGaps) {
        Spacer(Modifier.height(8.dp))
        PopTiles(
            listOf(
                PopTile(ShadeLevel.DIM, "Tenue", glyph = "◐"),
                PopTile(ShadeLevel.DARK, "Oscuro", glyph = "◑"),
                PopTile(ShadeLevel.BLACK, "Negro", glyph = "●"),
            ),
            selected = settings.gapShade,
            onSelect = sink::setGapShade,
            columns = columns,
        )
    }
}

/**
 * Auto-scroll: whether its switch in Accessibility is on — only an
 * accessibility service may move another app's page — and when it is not,
 * the way there, with App info a tap away for a switch Android has greyed
 * out; then how fast it goes, whether it slows down for balloons, and
 * whether its button sits beside 文A.
 */
@Composable
private fun AutoScrollSection(
    settings: AppSettings,
    sink: SettingsSink,
    on: Boolean,
    onTurnOn: () -> Unit,
    onOpenAppInfo: () -> Unit,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionTitle("Desplazamiento automático")
        val pop = LocalPop.current
        PopSurface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface) {
            val words: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    Text(
                        if (on) "Desplazamiento automático · activo ✓" else "Desplazamiento automático · falta activar un permiso",
                        style = MaterialTheme.typography.titleMedium,
                        color = pop.ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    // With Scroll button off there is no ▼ to tap: the words
                    // point to the long-press, as that row below does.
                    Helper(
                        when {
                            !on -> "Para desplazar otras aplicaciones necesito el permiso de Accesibilidad. Activa «MangaLens auto-scroll»."
                            settings.autoScrollButton -> "Toca ▼ junto a la burbuja $MARK y desplazaré la página por ti en cualquier aplicación."
                            else -> "Mantén pulsada la burbuja $MARK → Desplazamiento automático, en cualquier aplicación."
                        }
                    )
                }
            }
            val button: @Composable (Modifier) -> Unit = { m ->
                StickerButton(
                    "Activar ↗", onTurnOn, modifier = m, style = StickerStyle.Surface, height = 48.dp,
                    fillWidth = stacked, contentDescription = "Activar el desplazamiento automático en Accesibilidad",
                )
            }
            when {
                on -> words(Modifier.fillMaxWidth().padding(16.dp))
                stacked -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    words(Modifier)
                    Spacer(Modifier.height(12.dp))
                    button(Modifier)
                }
                else -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    words(Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    button(Modifier)
                }
            }
        }
        if (!on && Build.VERSION.SDK_INT >= 33) {
            Spacer(Modifier.height(4.dp))
            TextLink("¿El desplazamiento automático aparece deshabilitado? Permítelo en Información de la aplicación ↗", onOpenAppInfo)
        }
        Spacer(Modifier.height(16.dp))
        PopSlider(
            "Velocidad",
            settings.scrollLevel.toFloat(),
            ScrollPace.MIN_LEVEL.toFloat()..ScrollPace.MAX_LEVEL.toFloat(),
            { "${it.roundToInt()}" },
            { sink.setScrollLevel(it.roundToInt()) },
            startLabel = "lento",
            endLabel = "rápido",
        )
        Spacer(Modifier.height(12.dp))
        PopToggleRow(
            "Reducir la velocidad en globos grandes",
            if (settings.smartScroll) "Analizo la página: reduzco la velocidad al pasar por globos grandes y acelero en los espacios vacíos."
            else "Mantengo una velocidad constante, sin importar el contenido.",
            settings.smartScroll,
            sink::setSmartScroll,
        )
        Spacer(Modifier.height(12.dp))
        PopToggleRow(
            "Botón de desplazamiento",
            if (settings.autoScrollButton) "▼ aparece junto a la burbuja $MARK. Toca la pantalla para pausar el desplazamiento."
            else "Oculto. Mantén pulsada la burbuja $MARK para usar el desplazamiento automático.",
            settings.autoScrollButton,
            sink::setAutoScrollButton,
        )
        Spacer(Modifier.height(6.dp))
        Helper(
            "Con la traducción en pausa, la página se desplaza sin detenerse. Con la traducción activa, me detengo en cada página, " +
                "la traduzco y te doy tiempo para leer antes de continuar."
        )
    }
}

@Composable
private fun TextSizeSection(settings: AppSettings, sink: SettingsSink) {
    val pop = LocalPop.current
    SectionTitle("Apariencia")
    PopSlider(
        "Tamaño del texto",
        settings.textScale,
        0.8f..1.5f,
        { "${(it * 100).toInt()}%" },
        sink::setTextScale,
        below = { v ->
            SpeechBalloon(pop.surface, tail = Tail.Start, tailAt = 0.5f) {
                Text(
                    "¡Eh! ¡Estoy aquí!",
                    fontFamily = ComicNeue,
                    fontWeight = FontWeight.Bold,
                    fontSize = (16 * v).sp,
                    lineHeight = (20 * v).sp,
                    color = pop.ink,
                )
            }
        },
    )
    Spacer(Modifier.height(6.dp))
    Helper("Ajusta el tamaño del texto traducido en la página.")
}

/**
 * Under Look: whether the page is veiled so nothing of the original shows
 * through the English, at the price of a page a fifth darker while
 * MangaLens is awake. Android draws the lettering at no more than 80%.
 */
@Composable
private fun NoGhostsRow(settings: AppSettings, sink: SettingsSink) {
    Spacer(Modifier.height(16.dp))
    PopToggleRow(
        "Ocultar texto original",
        if (settings.noGhosts) "El texto original no se transparenta debajo de la traducción. La página se verá un poco más oscura mientras MangaLens esté activo."
        else "La página mantiene su brillo, pero el texto original puede verse ligeramente debajo de la traducción.",
        settings.noGhosts,
        sink::setNoGhosts,
    )
}

/**
 * Under Look: whether the lettering is solid, and when it is not, the way
 * to switch it on — the same trip to Accessibility as the optional setup
 * step, with App info a tap away for a switch Android has greyed out.
 * [stacked] puts the button under the words, for a narrow screen or large
 * text.
 */
@Composable
private fun SolidLetteringRow(on: Boolean, onTurnOn: () -> Unit, onOpenAppInfo: () -> Unit, stacked: Boolean) {
    val pop = LocalPop.current
    Spacer(Modifier.height(16.dp))
    PopSurface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface) {
        val words: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Text(
                    if (on) "Texto opaco · activado ✓" else "Texto opaco · desactivado",
                    style = MaterialTheme.typography.titleMedium,
                    color = pop.ink,
                )
                Spacer(Modifier.height(2.dp))
                Helper(
                    if (on) "El texto original no se transparenta debajo de la traducción."
                    else "El texto original se transparenta ligeramente. Activa MangaLens en Accesibilidad."
                )
            }
        }
        val button: @Composable (Modifier) -> Unit = { m ->
            StickerButton(
                "Activar ↗", onTurnOn, modifier = m, style = StickerStyle.Surface, height = 48.dp,
                fillWidth = stacked, contentDescription = "Activar texto opaco en Accesibilidad",
            )
        }
        when {
            on -> words(Modifier.fillMaxWidth().padding(16.dp))
            stacked -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                words(Modifier)
                Spacer(Modifier.height(12.dp))
                button(Modifier)
            }
            else -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                words(Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                button(Modifier)
            }
        }
    }
    if (!on && Build.VERSION.SDK_INT >= 33) {
        Spacer(Modifier.height(4.dp))
        TextLink("¿Está deshabilitado? Permítelo en Información de la aplicación ↗", onOpenAppInfo)
    }
}

/**
 * "Gemini · key saved ✓", or what is still missing, as the Your AI card
 * and the home screen say it. [refused] is whether the provider turned
 * away the key held now: setup then asks for a new key, and a line still
 * ticking it off would contradict it.
 */
internal fun aiBrainSummary(s: AppSettings, refused: Boolean = false): Pair<String, Boolean> {
    val label = LlmHttp.providerLabel(s)
    val custom = s.provider == LlmProvider.CUSTOM
    if (s.provider == LlmProvider.GOOGLE_FREE) return "Google Translate · gratis, sin API Key" to true
    return when {
        LlmHttp.setupNeeded(s) != null -> (if (custom) "$label · falta la URL del servidor" else "$label · falta la API Key") to false
        refused -> (if (custom) "$label · token rechazado" else "$label · API Key rechazada") to false
        else -> (if (custom) "$label · servidor configurado ✓" else "$label · API Key guardada ✓") to true
    }
}

/**
 * The AI, reduced to what a reader needs to know: is the key in, and does
 * it work. A saved key shows as a line with Test it and Change key; the
 * key box itself appears only when there is no key yet, the key was
 * refused, or the reader asked to change it. A custom server's URL is
 * asked for here too, since nothing works without it.
 */
@Composable
private fun YourAiSection(drafts: AiDrafts, sayHi: SayHi, modifier: Modifier) {
    val pop = LocalPop.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val pasteWiggle = rememberWiggle()
    val s = drafts.settings
    val provider = s.provider
    val (summary, ready) = aiBrainSummary(s, sayHi.rejects(s))
    var changing by remember(provider) { mutableStateOf(false) }
    var notice by remember(provider) { mutableStateOf<String?>(null) }
    val phase = sayHi.phase
    LaunchedEffect(phase) {
        when {
            // A refused key can only be fixed by a new one: open the box and point at Paste.
            phase is SayHi.Phase.Failed && phase.rejected -> {
                changing = true
                view.buzz(Buzz.REJECT)
                pasteWiggle.play(reduced)
            }
            phase is SayHi.Phase.Ok -> changing = false
        }
    }

    SectionTitle("Tu IA")
    PopSurface(modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                summary,
                style = MaterialTheme.typography.titleMedium,
                color = if (ready) pop.ink else pop.punchText,
            )
            if (provider == LlmProvider.GOOGLE_FREE) {
                Spacer(Modifier.height(8.dp))
                Helper("Google Translate gratis · sin API Key. El texto en inglés se detecta en el dispositivo y la traducción se realiza por internet.")
            } else if (provider == LlmProvider.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                SecretField(
                    value = drafts.customUrl,
                    onValueChange = drafts::editCustomUrl,
                    label = "URL del servidor (Chat Completions)",
                    secret = false,
                    keyboardType = KeyboardType.Uri,
                    isError = drafts.customUrl.isBlank(),
                    placeholder = "https://host/v1/chat/completions",
                )
            }
            if (provider != LlmProvider.GOOGLE_FREE && ready && !changing) {
                Spacer(Modifier.height(12.dp))
                val testing = phase == SayHi.Phase.Running
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StickerButton(
                        if (testing) "Probando…" else "Probar",
                        { if (!testing && !sayHi.runIfReady(s)) view.buzz(Buzz.REJECT) },
                        modifier = Modifier.weight(1f),
                        style = StickerStyle.Surface,
                        height = 48.dp,
                        contentDescription = if (provider == LlmProvider.CUSTOM) "Probar el servidor con una línea" else "Probar la API Key con una línea",
                    )
                    StickerButton(
                        if (provider == LlmProvider.CUSTOM) "Cambiar token" else "Cambiar API Key",
                        { changing = true },
                        modifier = Modifier.weight(1f),
                        style = StickerStyle.Surface,
                        height = 48.dp,
                    )
                }
            } else if (provider != LlmProvider.GOOGLE_FREE && (provider != LlmProvider.CUSTOM || drafts.customUrl.isNotBlank())) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    SecretField(
                        value = drafts.key,
                        onValueChange = {
                            notice = null
                            drafts.editKey(it)
                        },
                        label = apiKeyLabel(provider),
                        modifier = Modifier.weight(1f),
                        showLabel = false,
                        placeholder = apiKeyLabel(provider),
                    )
                    Spacer(Modifier.width(10.dp))
                    StickerButton(
                        "Pegar",
                        {
                            notice = null
                            val check = drafts.submitKey(clipboard.getText()?.text.orEmpty(), sayHi)
                            pasteNotice(check)?.let {
                                notice = it
                                view.buzz(Buzz.REJECT)
                                scope.launch { pasteWiggle.play(reduced) }
                            }
                        },
                        modifier = Modifier.fillMaxHeight(),
                        style = StickerStyle.Zap,
                        height = 48.dp,
                        fillWidth = false,
                        wiggle = pasteWiggle,
                        contentDescription = "Pegar API Key desde el portapapeles",
                    )
                }
                notice?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = pop.punchText)
                }
                providerKeyHelp(provider).url?.let { url ->
                    Spacer(Modifier.height(4.dp))
                    TextLink(providerKeyHelp(provider).linkLabel, { uri.openUri(url) })
                }
            }
        }
    }
    if (provider != LlmProvider.GOOGLE_FREE) SayHiResult(phase, onRetry = { sayHi.runIfReady(s) })
}

/**
 * Everything whose default is right for nearly everyone, folded: timing
 * and data, how the AI works, which AI, and troubleshooting.
 */
@Composable
private fun MoreOptions(
    settings: AppSettings,
    sink: SettingsSink,
    drafts: AiDrafts,
    open: Boolean,
    onToggle: () -> Unit,
    otherAi: Modifier,
) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val sunk = rememberSink(interaction)
    val chevron = animateFloatAsState(if (open) 180f else 0f, if (reduced) snap() else tween(200), label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        PopSurface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface, sunk = { sunk.value }) {
            Row(
                Modifier
                    .clickable(interaction, indication = null, role = Role.Button, onClick = onToggle)
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (open) "Expandido" else "Contraído"
                    }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Más opciones",
                        style = MaterialTheme.typography.titleLarge,
                        color = pop.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "Tiempo de respuesta, datos, modelo y otros proveedores de IA. Puedes dejar estos ajustes como están si todo funciona bien.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = pop.inkSoft,
                    )
                }
                Text(
                    "▾",
                    style = MaterialTheme.typography.titleLarge,
                    color = pop.ink,
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .graphicsLayer { rotationZ = chevron.value },
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (reduced) Modifier else Modifier.animateContentSize())
        ) {
            if (open) MoreOptionsBody(settings, sink, drafts, otherAi)
        }
    }
}

@Composable
private fun MoreOptionsBody(settings: AppSettings, sink: SettingsSink, drafts: AiDrafts, otherAi: Modifier) {
    val pop = LocalPop.current
    val provider = settings.provider

    GroupTitle("Tiempo y datos")
    PopSlider(
        "Tiempo de respuesta",
        settings.stabilityMs.toFloat(),
        200f..900f,
        { "${it.toInt()} ms" },
        { sink.setStabilityMs(it.toInt()) },
        startLabel = "rápido",
        endLabel = "pausado",
    )
    Helper("Tiempo que espero sin movimiento antes de leer la página.")
    Spacer(Modifier.height(16.dp))
    PopSlider(
        "Ignorar la parte superior de la pantalla",
        settings.ignoreTopPct,
        0f..0.15f,
        { "${(it * 100).toInt()}%" },
        sink::setIgnoreTopPct,
    )
    Helper("Evita que procese la barra de direcciones del navegador.")
    Spacer(Modifier.height(16.dp))
    PopToggleRow(
        "Ahorro de datos",
        "Reduce el tamaño de las imágenes enviadas. Los textos muy pequeños podrían detectarse con menos precisión.",
        settings.dataSaver,
        sink::setDataSaver,
    )

    if (provider != LlmProvider.GOOGLE_FREE) {
    GroupTitle("Cómo funciona la IA")
    Text("Tiempo de razonamiento", style = MaterialTheme.typography.bodyLarge, color = pop.ink)
    Spacer(Modifier.height(8.dp))
    PopTiles(
        listOf(
            PopTile(AiReasoning.FAST, "Rápido"),
            PopTile(AiReasoning.BALANCED, "Equilibrado"),
            PopTile(AiReasoning.THOROUGH, "Detallado"),
        ),
        selected = settings.aiReasoning,
        onSelect = sink::setAiReasoning,
        columns = 3,
        minHeight = 56.dp,
    )
    Spacer(Modifier.height(8.dp))
    Helper(
        when (settings.aiReasoning) {
            AiReasoning.FAST -> "Respuesta inicial más rápida. Ideal para textos claros."
            AiReasoning.BALANCED -> "El mejor equilibrio entre velocidad y precisión. Recomendado."
            AiReasoning.THOROUGH -> "Mayor atención a quién habla y a los textos difíciles, pero es más lento."
        }
    )
    Spacer(Modifier.height(16.dp))
    ModelChoice(drafts)
    if (provider == LlmProvider.GEMINI) {
        Spacer(Modifier.height(16.dp))
        PopToggleRow(
            "Reconstrucción del dibujo con IA",
            "Pide a un modelo de imagen que reconstruya el dibujo detrás del texto. Puede rechazarse en páginas explícitas; puedes dejarlo desactivado.",
            settings.aiCleanup,
            sink::setAiCleanup,
        )
    } else {
        Spacer(Modifier.height(16.dp))
        val sees = settings.aiVision != AiVisionMode.OFF
        PopToggleRow(
            "Permitir que la IA vea la página",
            if (sees) "Envía la imagen completa para que también pueda interpretar manuscritos y tipografías difíciles."
            else "Envía solo el texto detectado por tu teléfono. Consume menos datos, pero podría omitir tipografías especiales.",
            sees,
            { sink.setAiVision(if (it) AiVisionMode.AUTO else AiVisionMode.OFF) },
        )
    }

    }

    Column(otherAi) {
        GroupTitle("Usar otro proveedor de IA")
        Helper("Google Translate funciona sin API Key. Gemini y los demás proveedores de modelos de lenguaje son opcionales y pueden tener límites de uso o costos.")
    }
    Spacer(Modifier.height(8.dp))
    PopTiles(
        listOf(
            PopTile(LlmProvider.GOOGLE_FREE, "Google Translate", caption = "Sin API Key · predeterminado"),
            PopTile(LlmProvider.GEMINI, "Gemini", caption = "API Key opcional"),
            PopTile(LlmProvider.ANTHROPIC, "Claude", caption = "de Anthropic"),
            PopTile(LlmProvider.OPENAI, "OpenAI", caption = "API Key de pago"),
            PopTile(LlmProvider.OPENROUTER, "OpenRouter", caption = "Muchos modelos"),
            PopTile(LlmProvider.CUSTOM, "Personalizado", caption = "Tu propio servidor"),
        ),
        selected = provider,
        onSelect = sink::setProvider,
        columns = 2,
        minHeight = 56.dp,
    )
    Spacer(Modifier.height(8.dp))
    Helper("La API Key y, para el proveedor Personalizado, la URL del servidor se configuran en la sección Tu IA.")

    GroupTitle("Diagnóstico de problemas")
    PopToggleRow(
        "Diagnóstico",
        "Muestra lo que detecta el OCR (motor de reconocimiento óptico), los elementos de la página y una línea de estado para identificar por qué no se reconoció un globo.",
        settings.diagnostics,
        sink::setDiagnostics,
    )
}

/**
 * The model: automatic — the newest Flash, which follows Google's releases
 * with no app update — unless the reader picked one. For Gemini it is
 * chosen from the account's live list, never typed; other providers take
 * a model id, blank for their default.
 */
@Composable
private fun ModelChoice(drafts: AiDrafts) {
    val pop = LocalPop.current
    val s = drafts.settings
    Text("Modelo", style = MaterialTheme.typography.bodyLarge, color = pop.ink)
    Spacer(Modifier.height(6.dp))
    if (s.provider == LlmProvider.GEMINI) {
        val pinned = drafts.model.isNotBlank()
        Helper(if (pinned) drafts.model else "Automático: utiliza el modelo Gemini Flash más reciente. Recomendado para la mayoría.")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GeminiModelPicker(apiKey = drafts.key.trim(), onPick = drafts::editModel, modifier = Modifier.weight(1f))
            if (pinned) {
                StickerButton(
                    "Automático",
                    { drafts.editModel("") },
                    modifier = Modifier.weight(1f),
                    style = StickerStyle.Surface,
                    height = 48.dp,
                    contentDescription = "Usar el modelo automático",
                )
            }
        }
    } else {
        SecretField(
            value = drafts.model,
            onValueChange = drafts::editModel,
            label = "Modelo",
            secret = false,
            showLabel = false,
            placeholder = "Vacío = " + s.copy(model = "").effectiveModel().ifEmpty { "el modelo predeterminado de tu servidor" },
        )
    }
}

/**
 * Fetches Gemini's live model list on demand and offers it as a menu — the
 * newest Flash first — so the picker shows models released long after this
 * build shipped. Typing in the Model field always stays available. The copy
 * names Gemini, never its maker: to a reader who found the old machine
 * translation useless, the maker's name reads as that engine coming back.
 */
@Composable
private fun GeminiModelPicker(apiKey: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelCatalog.LiveModel>>(emptyList()) }
    Box(modifier) {
        StickerButton(
            if (loading) "Consultando Gemini…" else "Elegir modelo ▾",
            {
                if (loading) return@StickerButton
                if (apiKey.isBlank()) {
                    error = "Primero añade tu API Key. La lista de modelos se obtiene de tu cuenta."
                    return@StickerButton
                }
                error = null
                if (models.isNotEmpty()) {
                    open = true
                    return@StickerButton
                }
                loading = true
                scope.launch {
                    try {
                        models = ModelCatalog.gemini(apiKey)
                        open = models.isNotEmpty()
                        if (models.isEmpty()) error = "Gemini no devolvió modelos compatibles."
                    } catch (e: Exception) {
                        error = "No se pudieron obtener los modelos: " + (e.message ?: "error de conexión")
                    } finally {
                        loading = false
                    }
                }
            },
            style = StickerStyle.Surface,
            height = 48.dp,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = pop.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(2.dp, pop.stroke),
        ) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(m.label, style = MaterialTheme.typography.titleMedium, color = pop.ink)
                            Text(m.id, style = MonoStyle.copy(fontSize = 14.sp), color = pop.inkSoft)
                        }
                    },
                    onClick = {
                        open = false
                        onPick(m.id)
                    },
                )
            }
        }
    }
    error?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = pop.punchText)
    }
}
