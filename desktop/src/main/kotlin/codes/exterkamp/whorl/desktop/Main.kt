package codes.exterkamp.whorl.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import codes.exterkamp.whorl.HerdrAgent
import codes.exterkamp.whorl.HerdrProtocol
import codes.exterkamp.whorl.Profile
import java.awt.Frame
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

val OceanLight = lightColorScheme(
    primary = Color(0xFF006A6A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F0), onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7), onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF8C4F3A), onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    background = Color(0xFFFAFDFC), onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFAFDFC), onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E4), onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7979), outlineVariant = Color(0xFFBEC9C8),
    surfaceContainerLow = Color(0xFFF4F7F6), surfaceContainer = Color(0xFFEEF1F0),
    surfaceContainerHigh = Color(0xFFE8ECEB), surfaceContainerHighest = Color(0xFFE2E6E5),
)

val OceanDark = darkColorScheme(
    primary = Color(0xFF80D5D4), onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F), onPrimaryContainer = Color(0xFF9CF1F0),
    secondary = Color(0xFFB0CCCB), onSecondary = Color(0xFF1B3534),
    secondaryContainer = Color(0xFF324B4B), onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFFFB59E), onTertiary = Color(0xFF55200F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    background = Color(0xFF0E1514), onBackground = Color(0xFFDDE4E3),
    surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E3),
    surfaceVariant = Color(0xFF3F4948), onSurfaceVariant = Color(0xFFBEC9C8),
    outline = Color(0xFF889392), outlineVariant = Color(0xFF3F4948),
    surfaceContainerLow = Color(0xFF161D1D), surfaceContainer = Color(0xFF1A2121),
    surfaceContainerHigh = Color(0xFF252B2B), surfaceContainerHighest = Color(0xFF2F3636),
)

@Composable
fun WhorlTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) OceanDark else OceanLight, content = content)

/** All of the app's state that outlives a single screen. UI thread only. */
class AppState(val platform: DesktopPlatform) {
    val sessions = mutableStateListOf<DesktopSession>()

    /** The tab showing: -1 is the machine list. */
    val selected = mutableStateOf(-1)
    val profiles = mutableStateListOf<Profile>().apply { addAll(platform.desktopStore.profiles()) }
    val fontSize = mutableStateOf(platform.desktopStore.fontSize)

    /** Shows agent alerts; set once the window exists. */
    var notifier: Notifier? = null

    fun connect(profile: Profile) {
        val existing = sessions.indexOfFirst { it.profile.id == profile.id && it.state != DesktopSession.State.FINISHED }
        if (existing >= 0) {
            selected.value = existing
            return
        }
        val session = newSession(profile)
        val finished = sessions.indexOfFirst { it.profile.id == profile.id }
        if (finished >= 0) sessions[finished] = session else sessions.add(session)
        selected.value = sessions.indexOf(session)
    }

    fun reconnect(old: DesktopSession) {
        val i = sessions.indexOf(old)
        if (i < 0) return
        old.close()
        sessions[i] = newSession(old.profile)
    }

    fun closeTab(session: DesktopSession) {
        session.close()
        val i = sessions.indexOf(session)
        sessions.remove(session)
        selected.value = when {
            sessions.isEmpty() -> -1
            selected.value >= sessions.size -> sessions.size - 1
            selected.value > i -> selected.value - 1
            else -> selected.value
        }
    }

    private fun newSession(profile: Profile) = DesktopSession(profile, platform) { s, agent, event -> notifier?.agent(s, agent, event) }

    fun saveProfile(profile: Profile) {
        platform.desktopStore.save(profile)
        val i = profiles.indexOfFirst { it.id == profile.id }
        if (i >= 0) profiles[i] = profile else profiles.add(profile)
    }

    fun deleteProfile(profile: Profile) {
        platform.desktopStore.delete(profile.id)
        profiles.removeAll { it.id == profile.id }
    }

    fun zoom(delta: Int) {
        fontSize.value = (fontSize.value + delta).coerceIn(8, 32)
        platform.desktopStore.fontSize = fontSize.value
    }

    /** Show the agent's session and switch herdr to it (from a notification). */
    fun showAgent(session: DesktopSession, paneId: String) {
        val i = sessions.indexOf(session)
        if (i >= 0) selected.value = i
        session.herdr.focus(paneId)
    }
}

/**
 * herdr agent alerts as Windows notifications, through the system tray (a toast on Windows 10/11). Clicking one
 * brings the window forward on that agent's session.
 */
class Notifier(private val frame: Frame, private val app: AppState) {
    private var last: Pair<DesktopSession, String>? = null
    private val tray: TrayIcon? = runCatching {
        if (!SystemTray.isSupported()) return@runCatching null
        val image = ImageIO.read(Notifier::class.java.getResource("/whorl.png"))
        TrayIcon(image, "Whorl").apply {
            isImageAutoSize = true
            addActionListener { SwingUtilities.invokeLater { open() } }
            SystemTray.getSystemTray().add(this)
        }
    }.getOrNull()

    fun agent(session: DesktopSession, agent: HerdrAgent, event: HerdrProtocol.Event) {
        // Nothing to say if the user is already looking at that session.
        if (frame.isActive && app.sessions.getOrNull(app.selected.value) === session) return
        val title = when (event) {
            HerdrProtocol.Event.NEEDS_INPUT -> "${agent.name} needs you"
            HerdrProtocol.Event.FINISHED -> "${agent.name} finished"
        }
        last = session to agent.paneId
        val body = "${agent.label} · ${session.profile.label}"
        if (tray != null) tray.displayMessage(title, body, TrayIcon.MessageType.INFO)
        else Toolkit.getDefaultToolkit().beep()
    }

    private fun open() {
        frame.isVisible = true
        if (frame.state == Frame.ICONIFIED) frame.state = Frame.NORMAL
        frame.toFront()
        frame.requestFocus()
        last?.let { (session, pane) -> app.showAgent(session, pane) }
    }

    fun dispose() {
        tray?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
    }
}

val appVersion: String = System.getProperty("whorl.version") ?: "dev"
val appCommit: String = System.getProperty("whorl.commit") ?: "dev"

fun main() = application {
    val app = androidx.compose.runtime.remember { AppState(DesktopPlatform()) }
    val state = rememberWindowState(size = DpSize(1100.dp, 720.dp), placement = WindowPlacement.Floating)
    Window(
        onCloseRequest = {
            app.sessions.forEach { it.close() }
            app.notifier?.dispose()
            exitApplication()
        },
        title = "Whorl",
        icon = painterResource("whorl.png"),
        state = state,
        onPreviewKeyEvent = { e ->
            // App-wide shortcuts: Ctrl+Shift+T machines, Ctrl+Shift+W close tab, Ctrl+Tab next tab, Ctrl+= / Ctrl+- zoom.
            if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@Window false
            when {
                e.isShiftPressed && e.key == Key.T -> { app.selected.value = -1; true }
                e.isShiftPressed && e.key == Key.W -> { app.sessions.getOrNull(app.selected.value)?.let(app::closeTab); true }
                e.key == Key.Tab && app.sessions.isNotEmpty() -> {
                    val n = app.sessions.size + 1
                    val step = if (e.isShiftPressed) -1 else 1
                    app.selected.value = ((app.selected.value + 1 + step + n) % n) - 1
                    true
                }
                e.key == Key.Equals || e.key == Key.Plus -> { app.zoom(1); true }
                e.key == Key.Minus -> { app.zoom(-1); true }
                else -> false
            }
        },
    ) {
        if (app.notifier == null) {
            app.notifier = Notifier(window, app)
            // Development and tests: WHORL_CONNECT=<machine name> connects to it on launch.
            System.getenv("WHORL_CONNECT")?.let { name -> app.profiles.firstOrNull { it.label == name }?.let(app::connect) }
        }
        WhorlTheme { WhorlApp(app) }
    }
}
