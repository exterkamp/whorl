package codes.exterkamp.whorl

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

/** JetBrains Mono (res/font), for things that are code: addresses, commands, keys. The terminal uses it too. */
internal val Mono = FontFamily(Font(R.font.jetbrains_mono), Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

/** The logo's ocean teal, behind the shell wherever the app shows it. */
private val ShellBackground = Color(0xFF006A6A)

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
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For the "connected" notification and alerts; sessions work without it.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val store = Store.get(this)
        Sessions.restore(this)
        setContent {
            WhorlTheme {
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

// ---- Shared pieces -----------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { actions() },
    )
}

/** A small heading above a group of settings. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The shell logo in its teal circle. */
@Composable
private fun ShellLogo(size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(ShellBackground),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.requiredSize((size * 1.35).dp))
    }
}

/** The first letter of a machine's name in a tinted circle; lit while connected. */
@Composable
private fun MachineAvatar(label: String, live: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (live) colors.primary else colors.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = if (live) colors.onPrimary else colors.onSecondaryContainer,
        )
    }
}

@Composable
private fun <T> Choice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    @OptIn(ExperimentalMaterial3Api::class)
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

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    secret: Boolean = false,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono, fontSize = 15.sp)
        else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrect = false),
        trailingIcon = trailing,
        modifier = modifier,
    )
}

// ---- Screens -----------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Whorl") },
                actions = {
                    IconButton(onClick = onDeviceKey) { Icon(Icons.Default.Lock, "Device key") }
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, "About") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add machine") },
            )
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ShellLogo(96)
                Spacer(Modifier.height(24.dp))
                Text("No machines yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add a machine to connect to it over SSH or mosh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sessions.isNotEmpty()) {
                item { SectionHeader("Sessions") }
                items(sessions, key = { "session-" + it.profile.id }) { session ->
                    SessionRow(
                        session = session,
                        onResume = { onConnect(session.profile) },
                        onEnd = { Sessions.remove(context, session) },
                    )
                }
                item { SectionHeader("Machines") }
            }
            items(profiles, key = { it.id }) { profile ->
                val live = resumes >= 0 && sessions.any { it.profile.id == profile.id && !it.finished }
                ElevatedCard(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardDefaults.elevatedShape)
                        .combinedClickable(onClick = { onConnect(profile) }, onLongClick = { onEdit(profile) }),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { MachineAvatar(profile.label, live) },
                        headlineContent = { Text(profile.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                profile.addresses.firstOrNull().let { first ->
                                    "${profile.user}@${first?.host.orEmpty()}" +
                                        (first?.portOr(profile.port)?.takeIf { it != 22 }?.let { ":$it" } ?: "") +
                                        (if (profile.addresses.size > 1) " +${profile.addresses.size - 1}" else "") +
                                        " · " + profile.protocol.label
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { IconButton(onClick = { onEdit(profile) }) { Icon(Icons.Default.Edit, "Edit") } },
                    )
                }
            }
        }
    }
}

private val Protocol.label get() = if (this == Protocol.MOSH) "Mosh" else "SSH"

/** An open session: tap to go back to it, the close button to end it. */
@Composable
private fun SessionRow(session: ActiveSession, onResume: () -> Unit, onEnd: () -> Unit) {
    val ended = session.finished
    Card(
        onClick = onResume,
        colors = CardDefaults.cardColors(
            containerColor = if (ended) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { MachineAvatar(session.profile.label, live = !ended) },
            headlineContent = { Text(session.profile.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    if (ended) "Ended · tap to reconnect"
                    else "Connected" + (session.route?.let { " via ${it.via}" } ?: "") + " · tap to resume",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { IconButton(onClick = onEnd) { Icon(Icons.Default.Close, "End session") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            BackBar(if (isNew) "New machine" else "Edit machine", onBack) {
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
                }) { Text("Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Machine")
            Field("Name", name, { name = it }, placeholder = "Optional, e.g. devbox")
            addresses.forEachIndexed { i, text ->
                val address = Address.parse(text)
                Field(
                    label = if (addresses.size == 1) "Address" else "Address ${i + 1}",
                    value = text,
                    onChange = { addresses[i] = it },
                    placeholder = "IP or DNS name, :port optional",
                    supporting = when {
                        text.isBlank() -> null
                        address == null -> "Not an address"
                        else -> (address.kind.takeIf { it != address.host } ?: "internet").replaceFirstChar { it.uppercase() }
                    },
                    isError = text.isNotBlank() && address == null,
                    keyboard = KeyboardType.Uri,
                    mono = true,
                    trailing = if (addresses.size > 1) {
                        { IconButton(onClick = { addresses.removeAt(i) }) { Icon(Icons.Default.Close, "Remove address") } }
                    } else null,
                )
            }
            TextButton(onClick = { addresses.add("") }) {
                Icon(Icons.Default.Add, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Add address")
            }
            Hint(
                "List every way to reach this machine, like its LAN address and its Tailscale one. Connecting " +
                    "tries them all and uses the first to answer, preferring the ones higher up. If the network " +
                    "changes and that address stops answering, the session moves to another."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Field("User", user, { user = it }, Modifier.weight(2f), mono = true)
                Field(
                    "Port", port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f),
                    keyboard = KeyboardType.Number, isError = portNumber == null, mono = true,
                )
            }

            SectionHeader("Protocol")
            Choice(listOf("Mosh" to Protocol.MOSH, "SSH" to Protocol.SSH), protocol) { protocol = it }
            Hint(
                if (protocol == Protocol.MOSH) "Survives network changes and sleep. Needs mosh-server on the machine."
                else "Plain SSH. Works anywhere sshd does."
            )

            SectionHeader("Sign in with")
            Choice(listOf("Device key" to AuthMethod.DEVICE_KEY, "Password" to AuthMethod.PASSWORD), auth) { auth = it }
            if (auth == AuthMethod.PASSWORD) {
                Field("Password", password, { password = it }, placeholder = "Leave blank to ask each time", secret = true)
            }

            SectionHeader("On connect")
            Field(
                "Run on connect", startup, { startup = it },
                placeholder = "e.g. herdr or tmux new -A -s main",
                supporting = "Typed into the shell once connected. With a multiplexer here, reconnecting puts you back where you were.",
                mono = true,
            )
            if (protocol == Protocol.MOSH) {
                Field(
                    "mosh-server command", moshServer, { moshServer = it }, mono = true, singleLine = false,
                    supporting = "The default looks in ~/.local/bin and Homebrew before the system PATH.",
                )
            }

            if (!isNew) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Delete machine")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Delete ${initial.label}?") },
            text = { Text("Its saved password and trusted host key are deleted with it.") },
            confirmButton = { TextButton(onClick = { onDelete(initial) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
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

    Scaffold(topBar = { BackBar("Device key", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Machines set to sign in with the device key use this key. Add its public half to " +
                    "~/.ssh/authorized_keys on each machine.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val key = publicKey
            if (key != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(key, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(16.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    }) {
                        Icon(Icons.Default.Share, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Share")
                    }
                }
            } else {
                Text("No key yet.", style = MaterialTheme.typography.titleMedium)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(enabled = !busy, onClick = {
                    val generate = { run { Ssh.generateDeviceKey(context) } }
                    if (key != null) confirmReplace = generate else generate()
                }) { Text(if (key == null) "Generate key" else "New key") }
                OutlinedButton(enabled = !busy, onClick = { importing = !importing }) { Text("Import") }
            }

            if (importing) {
                var privateKey by remember { mutableStateOf("") }
                var passphrase by remember { mutableStateOf("") }
                Field(
                    "Private key", privateKey, { privateKey = it },
                    placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----", mono = true, singleLine = false,
                )
                Field("Passphrase, if it has one", passphrase, { passphrase = it }, secret = true)
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
            text = { Text("Machines that only trust the current key will stop accepting this device until you add the new one.") },
            confirmButton = { TextButton(onClick = { confirmReplace = null; action() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirmReplace = null }) { Text("Cancel") } },
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

    Scaffold(
        topBar = { BackBar(if (showLicenses) "Open source licenses" else "About", { if (showLicenses) showLicenses = false else onBack() }) },
    ) { padding ->
        if (showLicenses) {
            BackHandler { showLicenses = false }
            val text = remember { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }
            Text(
                text,
                fontFamily = Mono,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            ShellLogo(96)
            Spacer(Modifier.height(16.dp))
            Text("Whorl", style = MaterialTheme.typography.headlineMedium)
            if (version.isNotEmpty()) {
                Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "A plain SSH and mosh client. No accounts, no analytics, no ads. Whorl only talks to the machines " +
                    "you add, and their passwords and keys stay encrypted on this device.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
            HorizontalDivider()
            LinkItem("Privacy policy") { open(PRIVACY_URL) }
            LinkItem("Source code") { open(SOURCE_URL) }
            LinkItem("Open source licenses") { showLicenses = true }
            HorizontalDivider()
            ExterwareMark(Modifier.fillMaxWidth().padding(vertical = 32.dp))
        }
    }
}

@Composable
private fun LinkItem(text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** DejaVu Sans Mono, cut down to the mark's characters (res/font). Its own ♣ and ◆ keep Android from
 * drawing them with the color emoji font, which ignores the text color, and they're one column wide. */
private val MarkFont = FontFamily(Font(R.font.exterware_mono))

/** The developer's mark: five clubs between two diamond rules, over the name. */
@Composable
private fun ExterwareMark(modifier: Modifier = Modifier) {
    Text(
        "────◆────\n♣   ♣   ♣\n  ♣   ♣  \n────◆────\nexterware",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = MarkFont,
        fontSize = 14.sp,
        lineHeight = 17.sp,
        softWrap = false,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

private const val SOURCE_URL = "https://github.com/exterkamp/whorl"
private const val PRIVACY_URL = "https://github.com/exterkamp/whorl/blob/main/PRIVACY.md"
