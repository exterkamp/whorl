package codes.exterkamp.exterm

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

// A terminal's palette: phosphor green on black, amber for the second accent, square corners and one monospace
// face everywhere, so the app reads like a TUI rather than a Material app.
internal val Colors = darkColorScheme(
    primary = Color(0xFF00FF41),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF0B3D1A),
    onPrimaryContainer = Color(0xFF00FF41),
    secondary = Color(0xFFFFB000),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF050805),
    onBackground = Color(0xFFCFF5D6),
    surface = Color(0xFF050805),
    onSurface = Color(0xFFCFF5D6),
    secondaryContainer = Color(0xFF0B3D1A),
    onSecondaryContainer = Color(0xFF00FF41),
    surfaceVariant = Color(0xFF0B120C),
    onSurfaceVariant = Color(0xFF6F9A78),
    surfaceContainer = Color(0xFF0B120C),
    surfaceContainerLow = Color(0xFF080D08),
    surfaceContainerHigh = Color(0xFF0E1710),
    surfaceContainerHighest = Color(0xFF132016),
    outline = Color(0xFF1F5A2E),
    outlineVariant = Color(0xFF143D20),
    error = Color(0xFFFF5F56),
)

/** JetBrains Mono (res/font, without ligatures), for the app and the terminal alike. */
internal val Mono = FontFamily(Font(R.font.jetbrains_mono), Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

internal val TuiTypography = Typography().let { t ->
    fun TextStyle.mono() = copy(fontFamily = Mono, letterSpacing = 0.sp)
    Typography(
        displayLarge = t.displayLarge.mono(), displayMedium = t.displayMedium.mono(), displaySmall = t.displaySmall.mono(),
        headlineLarge = t.headlineLarge.mono(), headlineMedium = t.headlineMedium.mono(), headlineSmall = t.headlineSmall.mono(),
        titleLarge = t.titleLarge.mono(), titleMedium = t.titleMedium.mono(), titleSmall = t.titleSmall.mono(),
        bodyLarge = t.bodyLarge.mono(), bodyMedium = t.bodyMedium.mono(), bodySmall = t.bodySmall.mono(),
        labelLarge = t.labelLarge.mono(), labelMedium = t.labelMedium.mono(), labelSmall = t.labelSmall.mono(),
    )
}

internal val TuiShapes = RoundedCornerShape(0.dp).let { square ->
    Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square)
}

private sealed interface Screen {
    data object Hosts : Screen
    data class Edit(val profile: Profile, val isNew: Boolean) : Screen
    data object DeviceKey : Screen
    data object About : Screen
}

class MainActivity : ComponentActivity() {

    /** Bumped on resume so the list re-reads which sessions are live. */
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The bars pad for the system bars, and the editors use imePadding, so drawing edge to edge is safe.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Only for the "connected" notification; sessions work without it.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val store = Store.get(this)
        Sessions.restore(this)
        setContent {
            MaterialTheme(colorScheme = Colors, typography = TuiTypography, shapes = TuiShapes) {
                App(store, resumes) { startActivity(TerminalActivity.intent(this, it)) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }
}

@Composable
private fun App(store: Store, resumes: Int, connect: (Profile) -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Hosts) }
    var profiles by remember { mutableStateOf(store.profiles()) }

    when (val s = screen) {
        Screen.Hosts -> HostList(
            profiles = profiles,
            resumes = resumes,
            onConnect = connect,
            onEdit = { screen = Screen.Edit(it, isNew = false) },
            onAdd = { screen = Screen.Edit(Profile(), isNew = true) },
            onDeviceKey = { screen = Screen.DeviceKey },
            onAbout = { screen = Screen.About },
        )
        is Screen.Edit -> {
            BackHandler { screen = Screen.Hosts }
            EditHost(
                initial = s.profile,
                isNew = s.isNew,
                onSave = {
                    store.save(it)
                    profiles = store.profiles()
                    screen = Screen.Hosts
                },
                onDelete = {
                    store.delete(it.id)
                    profiles = store.profiles()
                    screen = Screen.Hosts
                },
                onBack = { screen = Screen.Hosts },
            )
        }
        Screen.DeviceKey -> {
            BackHandler { screen = Screen.Hosts }
            DeviceKey(store, onBack = { screen = Screen.Hosts })
        }
        Screen.About -> {
            BackHandler { screen = Screen.Hosts }
            About(onBack = { screen = Screen.Hosts })
        }
    }
}

// ---- TUI building blocks ---------------------------------------------------------------------------------------

/** The top bar: "<" back, the title, then text actions, over a rule. */
@Composable
private fun TuiBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) TuiAction("<", onBack, color = MaterialTheme.colorScheme.onSurface)
            else Spacer(Modifier.width(12.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            actions()
        }
        Rule()
    }
}

/** A word in a bar that does something when tapped. */
@Composable
private fun TuiAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        label,
        color = if (enabled) color else MaterialTheme.colorScheme.outline,
        fontSize = 15.sp,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

/** "[ label ]" in an outlined box; [filled] for the one main action on a screen. */
@Composable
private fun TuiButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val tint = if (enabled) color else MaterialTheme.colorScheme.outline
    Text(
        "[ $label ]",
        color = if (filled && enabled) MaterialTheme.colorScheme.onPrimary else tint,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(if (filled && enabled) tint else Color.Transparent)
            .border(1.dp, tint)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

/** "── text ─────────", a rule with a label, the way a TUI separates sections. */
@Composable
private fun SectionLabel(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("── $text ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** Small explanatory text under a field. */
@Composable
private fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = color)
}

/** A label over a boxed input whose outline lights up while it has focus. */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    hint: String? = null,
    isError: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    lines: Int = 1,
    small: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = if (focused) colors.primary else colors.onSurfaceVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant)
                .border(1.dp, if (isError) colors.error else if (focused) colors.primary else colors.outline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = lines == 1,
                minLines = lines,
                maxLines = if (lines == 1) 1 else lines * 2,
                textStyle = TextStyle(
                    fontFamily = Mono,
                    fontSize = if (small) 12.sp else 15.sp,
                    color = colors.onSurface,
                ),
                cursorBrush = SolidColor(colors.primary),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else keyboard,
                    autoCorrect = false,
                ),
                decorationBox = { input ->
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                fontSize = if (small) 12.sp else 15.sp,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = lines,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        input()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused }
                    .padding(horizontal = 12.dp, vertical = 13.dp),
            )
            trailing?.invoke()
        }
        hint?.let { Hint(it, if (isError) colors.error else colors.onSurfaceVariant) }
    }
}

/** "(•) one   ( ) other", radio buttons drawn in text. */
@Composable
private fun <T> Choice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            val on = value == selected
            Text(
                (if (on) "(•) " else "( ) ") + label,
                fontSize = 15.sp,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onSelect(value) }.padding(vertical = 12.dp, horizontal = 4.dp),
            )
        }
    }
}

/** The square at the start of a row: lit while connected. */
@Composable
private fun StatusDot(lit: Boolean) = Box(
    Modifier.size(8.dp).background(if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
)

@Composable
private fun TuiDialog(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline),
        title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
        text = text?.let { { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface) } },
        confirmButton = {
            TuiAction(confirm, onConfirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        },
        dismissButton = { TuiAction("cancel", onDismiss, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

// ---- Screens -----------------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HostList(
    profiles: List<Profile>,
    resumes: Int,
    onConnect: (Profile) -> Unit,
    onEdit: (Profile) -> Unit,
    onAdd: () -> Unit,
    onDeviceKey: () -> Unit,
    onAbout: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TuiBar("exterm") {
                TuiAction("key", onDeviceKey, color = colors.onSurfaceVariant)
                TuiAction("about", onAbout, color = colors.onSurfaceVariant)
            }
        },
        bottomBar = {
            Column(Modifier.background(colors.background).navigationBarsPadding()) {
                Rule()
                TuiButton("+ new machine", onAdd, Modifier.fillMaxWidth().padding(16.dp, 12.dp))
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("no machines yet", fontSize = 16.sp, color = colors.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(
                    "add one with its address,\nuser and how to sign in.",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = colors.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        val context = LocalContext.current
        // Reading version re-runs this whenever a session starts, ends or is closed.
        val sessions = Sessions.version.let { Sessions.all() }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sessions.isNotEmpty()) {
                item { SectionLabel("sessions") }
                items(sessions, key = { "session-" + it.profile.id }) { session ->
                    SessionRow(
                        session = session,
                        onResume = { onConnect(session.profile) },
                        onEnd = { Sessions.remove(context, session) },
                    )
                }
            }
            item { SectionLabel("machines") }
            items(profiles, key = { it.id }) { profile ->
                val live = resumes >= 0 && sessions.any { it.profile.id == profile.id && !it.finished }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (live) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant)
                        .combinedClickable(onClick = { onConnect(profile) }, onLongClick = { onEdit(profile) })
                        .padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(live)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                profile.label,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                profile.protocol.name.lowercase(),
                                fontSize = 12.sp,
                                color = if (profile.protocol == Protocol.MOSH) colors.primary else colors.secondary,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            profile.addresses.firstOrNull().let { first ->
                                "${profile.user}@${first?.host.orEmpty()}" +
                                    (first?.portOr(profile.port)?.takeIf { it != 22 }?.let { ":$it" } ?: "") +
                                    if (profile.addresses.size > 1) " +${profile.addresses.size - 1}" else ""
                            },
                            fontSize = 13.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TuiAction("edit", { onEdit(profile) }, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** An open session: tap to go back to it, x to end it. */
@Composable
private fun SessionRow(session: ActiveSession, onResume: () -> Unit, onEnd: () -> Unit) {
    val ended = session.finished
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant)
            .border(1.dp, if (ended) colors.outlineVariant else colors.primary.copy(alpha = 0.6f))
            .clickable(onClick = onResume)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(!ended)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                session.profile.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (ended) "ended · tap to reconnect"
                else "live" + (session.route?.let { " via ${it.via}" } ?: "") + " · tap to resume",
                fontSize = 13.sp,
                color = if (ended) colors.onSurfaceVariant else colors.primary.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TuiAction("x", onEnd, color = colors.onSurfaceVariant)
    }
}

@Composable
internal fun EditHost(
    initial: Profile,
    isNew: Boolean,
    onSave: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    // What's typed, one per address; a blank one is ignored.
    val addresses = remember { mutableStateListOf(*initial.addresses.map { it.toString() }.ifEmpty { listOf("") }.toTypedArray()) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var auth by remember { mutableStateOf(initial.auth) }
    var password by remember { mutableStateOf(initial.password) }
    var moshServer by remember { mutableStateOf(initial.moshServer) }
    var startup by remember { mutableStateOf(initial.startup) }
    var confirmDelete by remember { mutableStateOf(false) }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val parsed = addresses.filter { it.isNotBlank() }.map { Address.parse(it) }
    val valid = parsed.isNotEmpty() && parsed.all { it != null } && user.isNotBlank() && portNumber != null
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TuiBar(if (isNew) "new machine" else initial.label, onBack) {
                TuiAction("save", enabled = valid, onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(), addresses = parsed.filterNotNull().distinct(), port = portNumber!!, user = user.trim(),
                            protocol = protocol, auth = auth,
                            password = if (auth == AuthMethod.PASSWORD) password else "",
                            moshServer = moshServer.trim().ifEmpty { Profile.DEFAULT_MOSH_SERVER },
                            startup = startup.trim(),
                        )
                    )
                })
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("machine")
            Field("name", name, { name = it }, placeholder = "optional, e.g. devbox")
            addresses.forEachIndexed { i, text ->
                val address = Address.parse(text)
                Field(
                    label = if (addresses.size == 1) "address" else "address ${i + 1}",
                    value = text,
                    onChange = { addresses[i] = it },
                    placeholder = "IP or DNS name, :port optional",
                    hint = when {
                        text.isBlank() -> null
                        address == null -> "not an address"
                        else -> "→ " + (address.kind.takeIf { it != address.host } ?: "internet")
                    },
                    isError = text.isNotBlank() && address == null,
                    keyboard = KeyboardType.Uri,
                    trailing = if (addresses.size > 1) {
                        { TuiAction("x", { addresses.removeAt(i) }, color = colors.onSurfaceVariant) }
                    } else null,
                )
            }
            Box {
                TuiAction("+ add another address", { addresses.add("") })
            }
            Hint(
                "List every way to reach this machine, like its LAN address and its Tailscale one. Connecting " +
                    "tries them all and uses the first to answer, preferring the ones higher up. If the network " +
                    "changes and that address stops answering, the session moves to another."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("user", user, { user = it }, Modifier.weight(2f))
                Field(
                    "port", port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f),
                    keyboard = KeyboardType.Number, isError = portNumber == null,
                )
            }

            SectionLabel("protocol")
            Choice(listOf("mosh" to Protocol.MOSH, "ssh" to Protocol.SSH), protocol) { protocol = it }
            Hint(
                if (protocol == Protocol.MOSH) "Survives network changes and sleep. Needs mosh-server on the machine."
                else "Plain SSH. Works anywhere sshd does."
            )

            SectionLabel("sign in with")
            Choice(listOf("device key" to AuthMethod.DEVICE_KEY, "password" to AuthMethod.PASSWORD), auth) { auth = it }
            if (auth == AuthMethod.PASSWORD) {
                Field("password", password, { password = it }, placeholder = "blank to ask each time", secret = true)
            }

            SectionLabel("on connect")
            Field(
                "run", startup, { startup = it },
                placeholder = "e.g. herdr  or  tmux new -A -s main",
                hint = "Typed into the shell once connected. With a multiplexer here, reconnecting after the app " +
                    "is closed puts you back where you were.",
            )
            if (protocol == Protocol.MOSH) {
                Field(
                    "mosh-server command", moshServer, { moshServer = it }, small = true, lines = 2,
                    hint = "The default looks in ~/.local/bin and Homebrew before the system PATH. Use a full path " +
                        "if mosh-server is anywhere else.",
                )
            }

            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                TuiButton("delete machine", { confirmDelete = true }, Modifier.fillMaxWidth(), color = colors.error)
            }
        }
    }

    if (confirmDelete) {
        TuiDialog(
            title = "delete ${initial.label}?",
            text = "Its saved password and trusted host key go with it.",
            confirm = "delete",
            danger = true,
            onConfirm = { onDelete(initial) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
internal fun DeviceKey(store: Store, onBack: () -> Unit) {
    val context = LocalContext.current
    var publicKey by remember { mutableStateOf(store.devicePublicKey) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun run(work: () -> Unit) {
        busy = true
        error = null
        thread {
            val failure = runCatching(work).exceptionOrNull()
            (context as ComponentActivity).runOnUiThread {
                busy = false
                importing = importing && failure != null
                error = failure?.message
                publicKey = store.devicePublicKey
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = colors.background, topBar = { TuiBar("device key", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("public key")
            Hint(
                "Machines set to sign in with the device key use this one. Add it to ~/.ssh/authorized_keys on each.",
                color = colors.onSurface,
            )
            val key = publicKey
            if (key != null) {
                Text(
                    key,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceVariant)
                        .border(1.dp, colors.outline)
                        .padding(12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TuiButton("copy", {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("public key", key))
                    }, Modifier.weight(1f), filled = true)
                    TuiButton("share", {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key), null
                            )
                        )
                    }, Modifier.weight(1f))
                }
            } else {
                Text("no key yet", fontSize = 15.sp, color = colors.onSurfaceVariant)
            }

            SectionLabel("replace")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TuiButton(if (key == null) "generate" else "new key", enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                    val generate = { run { Ssh.generateDeviceKey(context) } }
                    if (key != null) confirmReplace = generate else generate()
                }, filled = key == null)
                TuiButton("import", { importing = !importing }, Modifier.weight(1f), enabled = !busy)
            }

            if (importing) {
                var privateKey by remember { mutableStateOf("") }
                var passphrase by remember { mutableStateOf("") }
                Field("private key", privateKey, { privateKey = it }, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----", lines = 4, small = true)
                Field("passphrase", passphrase, { passphrase = it }, placeholder = "if it has one", secret = true)
                TuiButton("use this key", enabled = !busy && privateKey.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    val import = { run { Ssh.importDeviceKey(context, privateKey, passphrase) } }
                    if (key != null) confirmReplace = import else import()
                })
            }

            error?.let { Hint(it, colors.error) }
        }
    }

    confirmReplace?.let { action ->
        TuiDialog(
            title = "replace the device key?",
            text = "Machines that only trust the current key will stop accepting this device until you add the new one.",
            confirm = "replace",
            danger = true,
            onConfirm = { confirmReplace = null; action() },
            onDismiss = { confirmReplace = null },
        )
    }
}

@Composable
internal fun About(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))

    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = { TuiBar(if (showLicenses) "licenses" else "about", { if (showLicenses) showLicenses = false else onBack() }) },
    ) { padding ->
        if (showLicenses) {
            BackHandler { showLicenses = false }
            val text = remember { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }
            Text(
                text,
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            )
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("exterm", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.primary)
                Spacer(Modifier.width(10.dp))
                Text("v$version", fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(
                "A plain SSH and mosh client. No accounts, no analytics, no ads. It only talks to the machines " +
                    "you add, and their passwords and keys stay encrypted on this device.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colors.onSurface,
            )
            SectionLabel("links")
            TuiButton("privacy policy", { open(PRIVACY_URL) }, Modifier.fillMaxWidth())
            TuiButton("source code", { open(SOURCE_URL) }, Modifier.fillMaxWidth())
            TuiButton("open source licenses", { showLicenses = true }, Modifier.fillMaxWidth())
            ExterwareMark(Modifier.fillMaxWidth().padding(top = 32.dp))
        }
    }
}

private val MatrixGreen = Color(0xFF00FF41)

/** DejaVu Sans Mono, cut down to the mark's characters (res/font). Its own ♣ and ◆ keep Android from
 * drawing them with the color emoji font, which ignores the text color, and they're one column wide. */
private val MarkFont = FontFamily(Font(R.font.exterware_mono))

/** The exterware mark: five clubs set like the Olympic rings between two diamond rules, over the wordmark. */
@Composable
private fun ExterwareMark(modifier: Modifier = Modifier) {
    Text(
        "────◆────\n♣   ♣   ♣\n  ♣   ♣  \n────◆────\nexterware",
        color = MatrixGreen,
        fontFamily = MarkFont,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        softWrap = false,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

private const val SOURCE_URL = "https://github.com/exterkamp/exterm"
private const val PRIVACY_URL = "https://github.com/exterkamp/exterm/blob/main/PRIVACY.md"
