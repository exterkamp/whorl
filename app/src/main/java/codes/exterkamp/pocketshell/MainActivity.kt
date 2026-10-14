package codes.exterkamp.pocketshell

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

private val Colors = darkColorScheme(
    primary = Color(0xFF7EE787),
    onPrimary = Color(0xFF0E1116),
    secondary = Color(0xFF79C0FF),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE6EDF3),
    secondaryContainer = Color(0xFF1F3B26),
    onSecondaryContainer = Color(0xFF7EE787),
    surfaceVariant = Color(0xFF161B22),
    onSurfaceVariant = Color(0xFF8B949E),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C2128),
    surfaceContainerHighest = Color(0xFF21262D),
    outline = Color(0xFF30363D),
    error = Color(0xFFFF7B72),
)

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
            MaterialTheme(colorScheme = Colors) {
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
                title = { Text("Hosts", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onDeviceKey) { Icon(Icons.Default.Lock, "Device key") }
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, "About") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, "Add host")
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No hosts yet.\nTap + to add one.",
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
                item { SectionLabel("Sessions") }
                items(sessions, key = { "session-" + it.profile.id }) { session ->
                    SessionRow(
                        session = session,
                        onResume = { onConnect(session.profile) },
                        onEnd = { Sessions.remove(context, session) },
                    )
                }
                item { SectionLabel("Hosts") }
            }
            items(profiles, key = { it.id }) { profile ->
                val live = resumes >= 0 && sessions.any { it.profile.id == profile.id && !it.finished }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { onConnect(profile) }, onLongClick = { onEdit(profile) }),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape).background(
                                if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "${profile.user}@${profile.host}" + if (profile.port != 22) ":${profile.port}" else "",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            profile.protocol.name.lowercase(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (profile.protocol == Protocol.MOSH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** An open session: tap to go back to it, ✕ to end it. */
@Composable
private fun SessionRow(session: ActiveSession, onResume: () -> Unit, onEnd: () -> Unit) {
    val ended = session.finished
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onResume)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(
                if (ended) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
            )
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(session.profile.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                (if (ended) "ended · tap to reconnect" else "connected · tap to resume") +
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
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var auth by remember { mutableStateOf(initial.auth) }
    var password by remember { mutableStateOf(initial.password) }
    var moshServer by remember { mutableStateOf(initial.moshServer) }
    var startup by remember { mutableStateOf(initial.startup) }
    var confirmDelete by remember { mutableStateOf(false) }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && user.isNotBlank() && portNumber != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New host" else "Edit host") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    TextButton(enabled = valid, onClick = {
                        onSave(
                            initial.copy(
                                name = name.trim(), host = host.trim(), port = portNumber!!, user = user.trim(),
                                protocol = protocol, auth = auth,
                                password = if (auth == AuthMethod.PASSWORD) password else "",
                                moshServer = moshServer.trim().ifEmpty { Profile.DEFAULT_MOSH_SERVER },
                                startup = startup.trim(),
                            )
                        )
                    }) { Text("Save") }
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
            Field("Host", host, { host = it }, keyboard = KeyboardType.Uri, mono = true)
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
                    "If mosh-server isn't on the PATH for non-interactive logins (Homebrew, ~/.local), use its full path.",
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
            confirmButton = { TextButton(onClick = { onDelete(initial) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
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
                title = { Text("Device key") },
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
                "Hosts set to \"Device key\" sign in with this key. Add the public half to ~/.ssh/authorized_keys on each machine.",
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
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("public key", key))
                    }) { Text("Copy") }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key), null
                            )
                        )
                    }) { Text("Share") }
                }
            } else {
                Text("No key yet.", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    val generate = { run { Ssh.generateDeviceKey(context) } }
                    if (key != null) confirmReplace = generate else generate()
                }) { Text(if (key == null) "Generate key" else "New key") }
                OutlinedButton(enabled = !busy, onClick = { importing = !importing }) { Text("Import key") }
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
                }) { Text("Use this key") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    confirmReplace?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmReplace = null },
            title = { Text("Replace the device key?") },
            text = { Text("Hosts that only trust the current key will stop accepting this device until you add the new one.") },
            confirmButton = { TextButton(onClick = { confirmReplace = null; action() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirmReplace = null }) { Text("Cancel") } },
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
                title = { Text(if (showLicenses) "Open source licenses" else "About") },
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
            OutlinedButton(onClick = { open(PRIVACY_URL) }) { Text("Privacy policy") }
            OutlinedButton(onClick = { open(SOURCE_URL) }) { Text("Source code") }
            OutlinedButton(onClick = { showLicenses = true }) { Text("Open source licenses") }
        }
    }
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
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}
