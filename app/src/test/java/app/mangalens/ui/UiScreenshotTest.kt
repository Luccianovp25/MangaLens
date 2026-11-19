package app.mangalens.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import app.mangalens.translate.GeminiHttpException
import app.mangalens.translate.GeminiRateLimited
import app.mangalens.update.UpdateChecker
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Renders every face of the app to PNG under build/ui-preview, light and
 * dark, at a 411×891dp phone (xxhdpi). The screens render from plain
 * state (see HomeContent), so each picture is a fixed state, and the
 * interactions a reader would make — "Escribirla manualmente", "All set", GO —
 * are made the same way a reader makes them.
 *
 * Most renders run with reduced motion, which is also what makes them
 * deterministic; the two sound-effect renders run with motion on and a
 * hand-driven clock to catch the effect mid-pop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/ui-preview").apply { mkdirs() }

    private var dark by mutableStateOf(false)
    private var ui by mutableStateOf(HomeUiState(null))
    private lateinit var sayHi: SayHi

    /** What the stand-in AI answers the test line with. */
    private var attempt: suspend (AppSettings) -> String = { LETTERED }

    /** What the screen asks the activity to do; nothing, unless a test counts the asks. */
    private var actions = HomeActions()

    /** A placeholder that passes the paste check; it is not in any provider's key format. */
    private val keyed = AppSettings(provider = LlmProvider.GEMINI, apiKey = "test-key-" + "x".repeat(24))

    private fun show(reduced: Boolean = true, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val base = LocalConfiguration.current
            val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val cfg = Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides cfg,
                LocalDensity provides Density(d.density, fontScale),
                LocalReducedMotion provides reduced,
            ) {
                MangaLensTheme(content)
            }
        }
    }

    private fun home(
        state: HomeUiState,
        reduced: Boolean = true,
        fontScale: Float = 1f,
        phase: SayHi.Phase = SayHi.Phase.Idle,
    ) {
        ui = state
        show(reduced, fontScale) {
            val scope = rememberCoroutineScope()
            val hi = remember { SayHi(scope) { attempt(it) }.also { it.phase = phase; sayHi = it } }
            val drafts = rememberAiDrafts(ui.settings ?: AppSettings(provider = LlmProvider.GEMINI), NoSink)
            HomeContent(ui, drafts, hi, NoSink, actions)
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        root.draw(canvas)
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.let { w ->
            // A dialog is its own window: draw the platform's dim, if the
            // window asks for one, then the dialog centred over the screen.
            val lp = w.attributes
            if (lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 && lp.dimAmount > 0f) {
                canvas.drawColor(((lp.dimAmount * 255).toInt() shl 24))
            }
            val dv = w.decorView
            canvas.save()
            canvas.translate((bmp.width - dv.width) / 2f, (bmp.height - dv.height) / 2f)
            dv.draw(canvas)
            canvas.restore()
        }
        val file = File(outDir, "$name.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${file.absolutePath}")
        assertTrue(file.length() > 0)
    }

    /** The same state, light then dark. */
    private fun both(name: String) {
        dark = false
        capture("$name-light")
        dark = true
        capture("$name-dark")
        dark = false
    }

    @Test
    fun `00 setup, Google Free needs no key`() {
        home(HomeUiState(AppSettings(), overlayGranted = false, browserName = "Brave"))
        compose.onNodeWithText("¡Listo! Google Translate está preparado; no necesita API Key.").assertExists()
        both("00-setup-google-free")
    }

    @Test
    fun `01 setup, first run`() {
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = false, browserName = "Brave"))
        both("01-setup-first-run")
    }

    @Test
    fun `02 setup, step two`() {
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = true, browserName = "Brave"))
        both("02-setup-brain")
    }

    @Test
    fun `03 setup, typing a key, clipboard empty`() {
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = true, browserName = "Brave"))
        compose.onNodeWithText("Escribirla manualmente").performScrollTo().performClick()
        // The test clipboard is empty, so Paste answers with its notice.
        compose.onNodeWithText("Pegar mi API Key").performScrollTo().performClick()
        compose.onNodeWithText("Guardar API Key").performScrollTo()
        both("03-setup-type-it")
    }

    @Test
    fun `04 setup, key saved, test line running`() {
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = true, browserName = "Brave"))
        sayHi.phase = SayHi.Phase.Running
        ui = ui.copy(settings = keyed)
        compose.waitForIdle()
        both("04-demo-before")
    }

    @Test
    fun `05 setup, test line lettered`() {
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = true, browserName = "Brave"))
        sayHi.phase = SayHi.Phase.Ok(LETTERED)
        ui = ui.copy(settings = keyed)
        compose.waitForIdle()
        compose.onNodeWithText("¡Todo listo, a leer!").performScrollTo()
        both("05-demo-after")
    }

    @Test
    fun `05b setup, the optional step open above All set`() {
        var turnOn = 0
        var appInfo = 0
        actions = HomeActions(onTurnOnSolid = { turnOn++ }, onOpenAppInfo = { appInfo++ })
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = true, solidOffered = true, browserName = "Brave"))
        sayHi.phase = SayHi.Phase.Ok(LETTERED)
        ui = ui.copy(settings = keyed)
        compose.waitForIdle()
        // Optional: the two steps done are enough for "All set", with the third still open.
        compose.onNodeWithText("¡Todo listo, a leer!").assertExists()
        compose.onNodeWithText("Activar ↗").performScrollTo().performClick()
        compose.onNodeWithText("Abrir Información de la aplicación ↗").performScrollTo().performClick()
        assertEquals(1, turnOn)
        assertEquals(1, appInfo)
        both("05b-setup-solid-step")
    }

    @Test
    fun `05c setup, solid lettering on`() {
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = true, solidOffered = true, solidLettering = true, browserName = "Brave"))
        sayHi.phase = SayHi.Phase.Ok(LETTERED)
        ui = ui.copy(settings = keyed)
        compose.waitForIdle()
        compose.onNodeWithText("¡Listo! La traducción ahora es opaca.").assertExists()
        compose.onNodeWithText("Activar ↗").assertDoesNotExist()
        compose.onNodeWithText("¡Todo listo, a leer!").performScrollTo()
        both("05c-setup-solid-done")
    }

    @Test
    fun `05d without the lettering host, or on Android 11, there is nothing to make solid`() {
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = true, solidOffered = false, browserName = "Brave"))
        sayHi.phase = SayHi.Phase.Ok(LETTERED)
        ui = ui.copy(settings = keyed)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hacer opaca la traducción", substring = true).assertDoesNotExist()
        compose.onNodeWithText("¡Todo listo, a leer!").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Ajusta el tamaño del texto traducido en la página.").performScrollTo()
        compose.onNodeWithText("Solid lettering", substring = true).assertDoesNotExist()
    }

    @Test
    fun `06 setup, key refused`() {
        attempt = { throw GeminiHttpException(400, "API key not valid. Please pass a valid API key.") }
        home(HomeUiState(keyed, overlayGranted = true, browserName = "Brave"))
        sayHi.run(keyed)
        compose.waitForIdle()
        // A refused key offers no retry: the answer is a new key, so Paste is what shows.
        compose.onNodeWithText("Gemini rechazó la API Key", substring = true).performScrollTo()
        compose.onNodeWithText("Pegar mi API Key").performScrollTo()
        both("06-setup-key-refused")
    }

    @Test
    fun `06b setup, test line rate limited`() {
        attempt = { throw GeminiRateLimited("quota") }
        // Step 1 still open, so the checklist is up; a busy key still counts
        // as saved, so step 2 folds to its done line with the retry under it.
        home(HomeUiState(keyed, overlayGranted = false, browserName = "Brave"))
        sayHi.run(keyed)
        compose.waitForIdle()
        compose.onNodeWithText("Volver a intentar").performScrollTo()
        both("06b-setup-rate-limited")
    }

    @Test
    fun `07 ready`() {
        home(HomeUiState(keyed, overlayGranted = true, browserName = "Brave"))
        both("07-ready")
    }

    @Test
    fun `08 ready with an update`() {
        home(HomeUiState(keyed, overlayGranted = true, browserName = "Brave", update = UPDATE))
        both("08-ready-update")
    }

    @Test
    fun `09 update dialog`() {
        home(HomeUiState(keyed, overlayGranted = true, browserName = "Brave", update = UPDATE))
        compose.onNodeWithContentDescription("Update available", substring = true).performClick()
        both("09-update-dialog")
    }

    @Test
    fun `10 ready after a refused capture, no Brave`() {
        home(HomeUiState(keyed, overlayGranted = true, browserName = null, startRefused = true))
        both("10-ready-refused")
    }

    @Test
    fun `11 running`() {
        home(HomeUiState(keyed, running = true, overlayGranted = true, browserName = "Brave"))
        both("11-running")
    }

    @Test
    fun `12 running, tap to translate`() {
        home(
            HomeUiState(keyed.copy(mode = CaptureMode.MANUAL), running = true, overlayGranted = true, browserName = "Brave")
        )
        both("12-running-tap-mode")
    }

    @Test
    fun `13 paused`() {
        home(HomeUiState(keyed, running = true, paused = true, overlayGranted = true, browserName = "Brave"))
        both("13-paused")
    }

    @Test
    fun `14 tweaks`() {
        home(HomeUiState(keyed.copy(sourceLang = SourceLang.KO), overlayGranted = true, browserName = "Brave"))
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        both("14-tweaks")
    }

    @Test
    fun `14b tweaks after a refused key`() {
        attempt = { throw GeminiHttpException(400, "API key not valid. Please pass a valid API key.") }
        home(HomeUiState(keyed, overlayGranted = true, browserName = "Brave"))
        sayHi.run(keyed)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        // The folded header agrees with setup: the key is not ticked off.
        compose.onNodeWithText("Gemini · API Key rechazada").performScrollTo()
        both("14b-tweaks-key-refused")
    }

    @Test
    fun `14c tweaks, a custom server with no URL yet`() {
        home(
            HomeUiState(
                AppSettings(provider = LlmProvider.CUSTOM), overlayGranted = true, browserName = "Brave",
                versionName = "1.0.0",
            )
        )
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        // The server URL is asked for in Your AI, with nothing to test until it is in.
        compose.onNodeWithText("Servidor personalizado · falta la URL del servidor").performScrollTo()
        compose.onNodeWithText("Probar").assertDoesNotExist()
        // Down to the footer, which the tall renders cannot reach.
        compose.onNodeWithText("MangaLens 1.0.0", substring = true).performScrollTo()
        both("14c-tweaks-custom-no-url")
    }

    @Test
    fun `14d tweaks, solid lettering off, then on`() {
        var turnOn = 0
        actions = HomeActions(onTurnOnSolid = { turnOn++ })
        home(HomeUiState(keyed, overlayGranted = true, solidOffered = true, browserName = "Brave"))
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("¿Está deshabilitado? Permítelo en Información de la aplicación ↗").performScrollTo()
        compose.onNodeWithContentDescription("Activar texto opaco en Accesibilidad").performClick()
        assertEquals(1, turnOn)
        both("14d-tweaks-solid-off")
        // Back from Accessibility with it switched on: a tick, and nothing left to do.
        ui = ui.copy(solidLettering = true)
        compose.waitForIdle()
        compose.onNodeWithText("Texto opaco · activado ✓").performScrollTo()
        compose.onNodeWithContentDescription("Activar texto opaco en Accesibilidad").assertDoesNotExist()
        both("14e-tweaks-solid-on")
    }

    @Test
    fun `14g tweaks, no ghosts where Android caps the overlay`() {
        home(HomeUiState(keyed, overlayGranted = true, ghostsOffered = true, browserName = "Brave"))
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Ocultar texto original").performScrollTo()
        compose.onNodeWithText("El texto original no se transparenta debajo de la traducción.", substring = true).assertExists()
        both("14g-tweaks-no-ghosts")
        // Switched off, it says what that costs.
        ui = ui.copy(settings = keyed.copy(noGhosts = false))
        compose.waitForIdle()
        compose.onNodeWithText("La página mantiene su brillo, pero el texto original puede verse ligeramente debajo de la traducción.").performScrollTo()
        both("14h-tweaks-ghosts-allowed")
    }

    @Test
    fun `14i where Android draws the overlay as painted there is no ghost to hide`() {
        home(HomeUiState(keyed, overlayGranted = true, ghostsOffered = false, browserName = "Brave"))
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Ajusta el tamaño del texto traducido en la página.").performScrollTo()
        compose.onNodeWithText("Ocultar texto original").assertDoesNotExist()
    }

    @Test
    fun `14j with the scroll button hidden, auto-scroll points to the long-press`() {
        home(HomeUiState(keyed.copy(autoScrollButton = false), overlayGranted = true, autoScrollOn = true, browserName = "Brave"))
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        // There is no ▼ to tap: the overlay shows it only while the setting is on.
        compose.onNodeWithText("Mantén pulsada la burbuja $MARK → Desplazamiento automático, en cualquier aplicación.").performScrollTo()
        compose.onNodeWithText("Tap ▼", substring = true).assertDoesNotExist()
        ui = ui.copy(settings = keyed)
        compose.waitForIdle()
        compose.onNodeWithText("Toca ▼ junto a la burbuja $MARK y desplazaré la página por ti en cualquier aplicación.").performScrollTo()
    }

    @Test
    fun `14k the first request after the activity is recreated still opens Tweaks`() {
        // The activity counts the overlay's requests in a plain field, which
        // starts again at 0 when a rotation or a dark-mode switch recreates
        // it; the page on show comes back from saved state.
        var requests by mutableIntStateOf(0)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MangaLensTheme {
                    val scope = rememberCoroutineScope()
                    val hi = remember { SayHi(scope) { LETTERED } }
                    val drafts = rememberAiDrafts(keyed, NoSink)
                    HomeContent(
                        HomeUiState(keyed, overlayGranted = true, browserName = "Brave"),
                        drafts, hi, NoSink, HomeActions(),
                        tweaksRequests = requests,
                    )
                }
            }
        }
        val tweaks = "La configuración predeterminada funciona bien. En general, no hace falta cambiar nada más."
        requests = 1
        compose.onNodeWithText(tweaks).assertExists()
        compose.onNodeWithContentDescription("Cerrar ajustes").performClick()
        compose.onNodeWithText(tweaks).assertDoesNotExist()

        requests = 0
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(tweaks).assertDoesNotExist()
        // The overlay's next Tweaks, or ▼ with auto-scroll switched off: count 1 again.
        requests = 1
        compose.onNodeWithText(tweaks).assertExists()
    }

    @Test
    fun `14f tweaks, solid lettering off at double font size`() {
        // Large text stacks the button under the words instead of squeezing them.
        home(HomeUiState(keyed, overlayGranted = true, solidOffered = true, browserName = "Brave"), fontScale = 2f)
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("¿Está deshabilitado? Permítelo en Información de la aplicación ↗").performScrollTo()
        both("14f-tweaks-solid-font-2x")
    }

    @Test
    fun `15 celebration, light`() = celebration(false)

    @Test
    fun `15 celebration, dark`() = celebration(true)

    /** "All set" with motion on: KA-POW! pops while Fuki and the burst spring in. */
    private fun celebration(isDark: Boolean) {
        dark = isDark
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = true, browserName = "Brave"), reduced = false)
        compose.mainClock.autoAdvance = false
        sayHi.phase = SayHi.Phase.Ok(LETTERED)
        ui = ui.copy(settings = keyed)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(600)
        // The optional third step is open above "All set", which puts it below
        // the fold, and a scroll is an animation this paused clock never runs:
        // the tap goes straight to the button's click. The screen it leaves
        // is replaced by the celebration, so where it was scrolled to is moot.
        compose.onNodeWithText("¡Todo listo, a leer!").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(520)
        capture("15-celebration-" + if (isDark) "dark" else "light")
    }

    @Test
    fun `16 GO, light`() = boop(false)

    @Test
    fun `16 GO, dark`() = boop(true)

    /** The edge into running with motion on: BOOP!, speed lines drawing out, the word swapping. */
    private fun boop(isDark: Boolean) {
        dark = isDark
        home(HomeUiState(keyed, overlayGranted = true, browserName = "Brave"), reduced = false)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(400)
        ui = ui.copy(running = true)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(260)
        capture("16-boop-" + if (isDark) "dark" else "light")
    }

    @Test
    fun `17 setup at double font size`() {
        home(HomeUiState(AppSettings(provider = LlmProvider.GEMINI), overlayGranted = false, browserName = "Brave"), fontScale = 2f)
        both("17-setup-font-2x")
    }

    @Test
    fun `18 ready, animations on`() {
        home(HomeUiState(keyed, overlayGranted = true, browserName = "Brave"), reduced = false)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1200)
        capture("18-ready-motion-light")
    }

    companion object {
        const val LETTERED = "It's okay. I'll protect you."
        val UPDATE = UpdateChecker.Update(version = "0.12.0", url = "https://example.com/MangaLens.apk")
    }
}

/**
 * Tall renders: the whole Tweaks page, More options unfolded, in one picture,
 * on a phone-width window tall enough to hold it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2150dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TweaksScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/ui-preview").apply { mkdirs() }
    private var dark by mutableStateOf(false)

    private fun render(settings: AppSettings, phase: SayHi.Phase, name: String) {
        compose.setContent {
            val base = LocalConfiguration.current
            val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val cfg = Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            CompositionLocalProvider(LocalConfiguration provides cfg, LocalReducedMotion provides true) {
                MangaLensTheme {
                    val scope = rememberCoroutineScope()
                    val hi = remember { SayHi(scope) { UiScreenshotTest.LETTERED }.also { it.phase = phase } }
                    val drafts = rememberAiDrafts(settings, NoSink)
                    HomeContent(
                        HomeUiState(settings, overlayGranted = true, browserName = "Brave", versionName = "1.0.0"),
                        drafts, hi, NoSink, HomeActions(),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Ajustes:", substring = true).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Más opciones").performScrollTo().performClick()
        compose.waitForIdle()
        for (isDark in listOf(false, true)) {
            dark = isDark
            compose.waitForIdle()
            val root = compose.activity.window.decorView.rootView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            val file = File(outDir, "$name-" + (if (isDark) "dark" else "light") + ".png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("wrote ${file.absolutePath}")
        }
    }

    @Test
    fun `19 tweaks with More options open and a lettered test line`() {
        render(
            AppSettings(apiKey = "test-key-" + "x".repeat(24), sourceLang = SourceLang.AUTO),
            SayHi.Phase.Ok(UiScreenshotTest.LETTERED),
            "19-tweaks-ai-full",
        )
    }

    @Test
    fun `20 tweaks for a custom endpoint with no URL yet`() {
        render(
            AppSettings(provider = LlmProvider.CUSTOM, mode = CaptureMode.MANUAL, diagnostics = true),
            SayHi.Phase.Idle,
            "20-tweaks-custom",
        )
    }
}

/** A phone on its side: Fuki on the left, everything else scrolling on the right. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `21 running, landscape`() {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MangaLensTheme {
                    val scope = rememberCoroutineScope()
                    val settings = AppSettings(apiKey = "test-key-" + "x".repeat(24))
                    val hi = remember { SayHi(scope) { UiScreenshotTest.LETTERED } }
                    val drafts = rememberAiDrafts(settings, NoSink)
                    HomeContent(
                        HomeUiState(settings, running = true, overlayGranted = true, browserName = "Brave"),
                        drafts, hi, NoSink, HomeActions(),
                    )
                }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val file = File(File("build/ui-preview").apply { mkdirs() }, "21-running-landscape-light.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${file.absolutePath}")
        assertTrue(bmp.width > bmp.height)
    }
}

/** A settings sink that goes nowhere: the renders are fixed states. */
internal object NoSink : SettingsSink {
    override fun setProvider(v: LlmProvider) = Unit
    override fun setApiKey(provider: LlmProvider, v: String) = Unit
    override fun setModel(provider: LlmProvider, v: String) = Unit
    override fun setCustomUrl(v: String) = Unit
    override fun setSourceLang(v: SourceLang) = Unit
    override fun setMode(v: CaptureMode) = Unit
    override fun setAiVision(v: app.mangalens.settings.AiVisionMode) = Unit
    override fun setAiReasoning(v: app.mangalens.settings.AiReasoning) = Unit
    override fun setDataSaver(v: Boolean) = Unit
    override fun setAiCleanup(v: Boolean) = Unit
    override fun setDiagnostics(v: Boolean) = Unit
    override fun setTextScale(v: Float) = Unit
    override fun setNoGhosts(v: Boolean) = Unit
    override fun setIgnoreTopPct(v: Float) = Unit
    override fun setStabilityMs(v: Int) = Unit
    override fun setAutoScrollButton(v: Boolean) = Unit
    override fun setScrollLevel(v: Int) = Unit
    override fun setSmartScroll(v: Boolean) = Unit
}
