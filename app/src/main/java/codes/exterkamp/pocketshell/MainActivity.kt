package codes.exterkamp.pocketshell

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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

// A terminal's palette: phosphor green on black, amber for the second accent, square corners and one monospace
// face everywhere, so the app reads like a TUI rather than a Material app.
private val Colors = darkColorScheme(
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

private val Mono = FontFamily.Monospace

private val TuiTypography = Typography().let { t ->
    fun TextStyle.mono() = copy(fontFamily = Mono, letterSpacing = 0.sp)
    Typography(
        displayLarge = t.displayLarge.mono(), displayMedium = t.displayMedium.mono(), displaySmall = t.displaySmall.mono(),
        headlineLarge = t.headlineLarge.mono(), headlineMedium = t.headlineMedium.mono(), headlineSmall = t.headlineSmall.mono(),
        titleLarge = t.titleLarge.mono(), titleMedium = t.titleMedium.mono(), titleSmall = t.titleSmall.mono(),
        bodyLarge = t.bodyLarge.mono(), bodyMedium = t.bodyMedium.mono(), bodySmall = t.bodySmall.mono(),
        labelLarge = t.labelLarge.mono(), labelMedium = t.labelMedium.mono(), labelSmall = t.labelSmall.mono(),
    )
}

private val TuiShapes = RoundedCornerShape(0.dp).let { square ->
    Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square)
}

/** A screen title like a shell prompt's working directory. */
@Composable
private fun TuiTitle(path: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("~/") }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(path) }
        },
        fontWeight = FontWeight.Bold,
    )
}

/** "[ label ]", the way a TUI draws a button. */
private fun bracket(label: String) = "[ $label ]"

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
        // Scaffold pads for the system bars, and the editors use imePadding, so drawing edge to edge is safe.
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HostList(
    profiles: List<Profile>,
    resumes: Int,
    onConnect: (Profile) -> Unit,
    onEdit: (Profile) -> Unit,
    onAdd: () -> Unit,
    onDeviceKey: () -> Unit,
    onAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { TuiTitle("machines") },
                actions = {
                    IconButton(onClick = onDeviceKey) { Icon(Icons.Default.Lock, "Device key") }
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, "About") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, "Add machine")
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "no machines yet.\n\npress [+] to add one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }
        val context = LocalContext.current
        // Reading version re-runs this whenever a session starts, ends or is closed.
        val sessions = Sessions.version.let { Sessions.all() }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                item { SectionLabel("machines") }
            }
            items(profiles, key = { it.id }) { profile ->
                val live = resumes >= 0 && sessions.any { it.profile.id == profile.id && !it.finished }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RectangleShape,
                    border = BorderStroke(1.dp, if (live) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onConnect(profile) }, onLongClick = { onEdit(profile) }),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).background(
                                if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                profile.addresses.firstOrNull().let { first ->
                                    "${profile.user}@${first?.host.orEmpty()}" +
                                        (first?.portOr(profile.port)?.takeIf { it != 22 }?.let { ":$it" } ?: "") +
                                        if (profile.addresses.size > 1) "  +${profile.addresses.size - 1}" else ""
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "[" + profile.protocol.name.lowercase() + "]",
                            fontSize = 12.sp,
                            color = if (profile.protocol == Protocol.MOSH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        )
                        IconButton(onClick = { onEdit(profile) }) {
                            Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** "── text ─────────", a rule with a label, the way a TUI separates sections. */
@Composable
private fun SectionLabel(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "── $text ",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** An open session: tap to go back to it, ✕ to end it. */
@Composable
private fun SessionRow(session: ActiveSession, onResume: () -> Unit, onEnd: () -> Unit) {
    val ended = session.finished
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, if (ended) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            .clickable(onClick = onResume)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).background(
                if (ended) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
            )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(session.profile.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                (if (ended) "ended · tap to reconnect" else "connected" + (session.route?.let { " via ${it.via}" } ?: "") + " · tap to resume") +
                    " · " + session.profile.protocol.name.lowercase(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEnd) {
            Icon(Icons.Default.Close, "End session", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditHost(
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TuiTitle(if (isNew) "machines/new" else "machines/" + initial.label) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    TextButton(enabled = valid, onClick = {
                        onSave(
                            initial.copy(
                                name = name.trim(), addresses = parsed.filterNotNull().distinct(), port = portNumber!!, user = user.trim(),
                                protocol = protocol, auth = auth,
                                password = if (auth == AuthMethod.PASSWORD) password else "",
                                moshServer = moshServer.trim().ifEmpty { Profile.DEFAULT_MOSH_SERVER },
                                startup = startup.trim(),
                            )
                        )
                    }) { Text(bracket("save")) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field("Name", name, { name = it }, placeholder = "optional")
            addresses.forEachIndexed { i, text ->
                val address = Address.parse(text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { addresses[i] = it },
                            label = { Text(if (addresses.size == 1) "Address" else "Address ${i + 1}") },
                            placeholder = { Text("IP or DNS name, :port optional") },
                            supportingText = when {
                                text.isBlank() -> null
                                address == null -> { { Text("Not an address") } }
                                else -> { { Text(address.kind.takeIf { it != address.host } ?: "internet") } }
                            },
                            isError = text.isNotBlank() && address == null,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (addresses.size > 1) {
                        IconButton(onClick = { addresses.removeAt(i) }) {
                            Icon(Icons.Default.Close, "Remove address", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            TextButton(onClick = { addresses.add("") }) { Text(bracket("+ address")) }
            Text(
                "Add each way to reach this machine, e.g. its LAN address and its Tailscale one. All are tried on " +
                    "connect and the first to answer is used, preferring the ones listed first. If the network " +
                    "changes and that address stops answering, the session reconnects over another.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(2f)) { Field("User", user, { user = it }, mono = true) }
                Box(Modifier.weight(1f)) { Field("Port", port, { port = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number, mono = true) }
            }

            Label("Protocol")
            Choice(listOf("Mosh" to Protocol.MOSH, "SSH" to Protocol.SSH), protocol) { protocol = it }

            Label("Sign in with")
            Choice(listOf("Device key" to AuthMethod.DEVICE_KEY, "Password" to AuthMethod.PASSWORD), auth) { auth = it }
            if (auth == AuthMethod.PASSWORD) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    placeholder = { Text("leave blank to ask each time") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Field("Run on connect", startup, { startup = it }, placeholder = "e.g. herdr  or  tmux new -A -s main", mono = true)
            Text(
                "Typed into the shell once connected. Exiting it leaves you at the shell. With a multiplexer here, " +
                    "reconnecting after the app is closed puts you back where you were.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (protocol == Protocol.MOSH) {
                Field("mosh-server command", moshServer, { moshServer = it }, mono = true)
                Text(
                    "The default looks in ~/.local/bin and Homebrew before the system PATH. Use a full path if " +
                        "mosh-server is anywhere else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${initial.label}?") },
            confirmButton = { TextButton(onClick = { onDelete(initial) }) { Text(bracket("delete")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(bracket("cancel")) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceKey(store: Store, onBack: () -> Unit) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TuiTitle("device-key") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Machines set to \"Device key\" sign in with this key. Add the public half to ~/.ssh/authorized_keys on each machine.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val key = publicKey
            if (key != null) {
                Text(
                    key,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("public key", key))
                    }) { Text(bracket("copy")) }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key), null
                            )
                        )
                    }) { Text(bracket("share")) }
                }
            } else {
                Text("No key yet.", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    val generate = { run { Ssh.generateDeviceKey(context) } }
                    if (key != null) confirmReplace = generate else generate()
                }) { Text(bracket(if (key == null) "generate" else "new key")) }
                OutlinedButton(enabled = !busy, onClick = { importing = !importing }) { Text(bracket("import")) }
            }

            if (importing) {
                var privateKey by remember { mutableStateOf("") }
                var passphrase by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = { Text("Private key") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase, if it has one") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = !busy && privateKey.isNotBlank(), onClick = {
                    val import = { run { Ssh.importDeviceKey(context, privateKey, passphrase) } }
                    if (key != null) confirmReplace = import else import()
                }) { Text(bracket("use this key")) }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    confirmReplace?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmReplace = null },
            title = { Text("Replace the device key?") },
            text = { Text("Machines that only trust the current key will stop accepting this device until you add the new one.") },
            confirmButton = { TextButton(onClick = { confirmReplace = null; action() }) { Text(bracket("replace")) } },
            dismissButton = { TextButton(onClick = { confirmReplace = null }) { Text(bracket("cancel")) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun About(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TuiTitle(if (showLicenses) "about/licenses" else "about") },
                navigationIcon = {
                    IconButton(onClick = { if (showLicenses) showLicenses = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (showLicenses) {
            BackHandler { showLicenses = false }
            val text = remember { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("PocketShell $version", style = MaterialTheme.typography.titleLarge)
            Text(
                "A plain SSH and mosh client. No accounts, no analytics, no ads. PocketShell only talks to the " +
                    "hosts you add, and your hosts, passwords and keys stay encrypted on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { open(PRIVACY_URL) }) { Text(bracket("privacy policy")) }
            OutlinedButton(onClick = { open(SOURCE_URL) }) { Text(bracket("source code")) }
            OutlinedButton(onClick = { showLicenses = true }) { Text(bracket("open source licenses")) }
            ExterwareMark(Modifier.fillMaxWidth().padding(top = 24.dp))
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

private const val SOURCE_URL = "https://github.com/exterkamp/pocketshell"
private const val PRIVACY_URL = "https://github.com/exterkamp/pocketshell/blob/main/PRIVACY.md"

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrect = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (label, value) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = RectangleShape,
            ) { Text(label) }
        }
    }
}
