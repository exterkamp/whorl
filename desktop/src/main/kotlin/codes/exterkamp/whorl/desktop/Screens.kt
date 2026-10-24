package codes.exterkamp.whorl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codes.exterkamp.whorl.Address
import codes.exterkamp.whorl.AuthMethod
import codes.exterkamp.whorl.ConnectException
import codes.exterkamp.whorl.HerdrAgent
import codes.exterkamp.whorl.HerdrProtocol
import codes.exterkamp.whorl.Profile
import codes.exterkamp.whorl.Protocol
import codes.exterkamp.whorl.Ssh
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun WhorlApp(app: AppState) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TabBar(app)
            HorizontalDivider()
            val session = app.sessions.getOrNull(app.selected.value)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (session == null) MachinesScreen(app) else SessionScreen(app, session)
            }
        }
    }
}

@Composable
private fun TabBar(app: AppState) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab(selected = app.selected.value == -1, onClick = { app.selected.value = -1 }) {
            Icon(Icons.Default.Computer, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Machines", style = MaterialTheme.typography.labelLarge)
        }
        app.sessions.forEachIndexed { i, s ->
            Tab(selected = app.selected.value == i, onClick = { app.selected.value = i }) {
                StatusDot(s)
                Spacer(Modifier.width(6.dp))
                Text(s.profile.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.widthIn(max = 180.dp), overflow = TextOverflow.Ellipsis)
                @Suppress("UNUSED_EXPRESSION") s.herdrRevision
                if (s.herdr.agents.any { it.status == HerdrAgent.Status.BLOCKED }) {
                    Spacer(Modifier.width(4.dp))
                    Text("⚠", color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp)
                }
                IconButton(onClick = { app.closeTab(s) }, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Default.Close, "Close", Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun Tab(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.padding(end = 4.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 10.dp, end = 6.dp).height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun StatusDot(s: DesktopSession) {
    val color = when {
        s.state == DesktopSession.State.CONNECTED -> Color(0xFF4CAF50)
        s.state == DesktopSession.State.CONNECTING -> Color(0xFFFFB300)
        else -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

// --- Machines --------------------------------------------------------------------------------------------------

@Composable
private fun MachinesScreen(app: AppState) {
    var editing by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var showKey by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Machines", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { showKey = true }) {
                Icon(Icons.Default.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Device key")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { editing = Profile() }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add machine")
            }
        }
        Spacer(Modifier.height(16.dp))
        if (app.profiles.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No machines yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add one, then make a device key and put its public key in ~/.ssh/authorized_keys there.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(app.profiles, key = { it.id }) { p ->
                    MachineCard(p, live = app.sessions.any { it.profile.id == p.id && it.state != DesktopSession.State.FINISHED },
                        onConnect = { app.connect(p) }, onEdit = { editing = p }, onDelete = { deleting = p })
                }
            }
        }
        Text(
            "Whorl $appVersion · $appCommit   ·   Ctrl+Shift+T machines · Ctrl+Tab next tab · Ctrl+Shift+C/V copy/paste · Ctrl+= / Ctrl+- text size",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    editing?.let { p -> MachineEditor(p, onDismiss = { editing = null }, onSave = { app.saveProfile(it); editing = null }) }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.label}?") },
            text = { Text("Its settings are removed from this computer. Its host key stays trusted.") },
            confirmButton = { TextButton(onClick = { app.deleteProfile(p); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (showKey) DeviceKeyDialog(app) { showKey = false }
}

@Composable
private fun MachineCard(p: Profile, live: Boolean, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onConnect)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Badge(if (p.protocol == Protocol.MOSH) "mosh" else "ssh")
                    if (live) { Spacer(Modifier.width(6.dp)); Badge("connected") }
                }
                Text(
                    "${p.user}@" + p.addresses.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.startup.isNotBlank()) {
                    Text("runs: ${p.startup}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            Button(onClick = onConnect) { Text(if (live) "Open" else "Connect") }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun MachineEditor(original: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var name by remember { mutableStateOf(original.name) }
    var addresses by remember { mutableStateOf(original.addresses.joinToString("\n")) }
    var port by remember { mutableStateOf(original.port.toString()) }
    var user by remember { mutableStateOf(original.user) }
    var protocol by remember { mutableStateOf(original.protocol) }
    var auth by remember { mutableStateOf(original.auth) }
    var password by remember { mutableStateOf(original.password) }
    var moshServer by remember { mutableStateOf(original.moshServer) }
    var startup by remember { mutableStateOf(original.startup) }

    val parsed = addresses.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it to Address.parse(it) }
    val badAddress = parsed.firstOrNull { it.second == null }?.first
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = parsed.isNotEmpty() && badAddress == null && portNumber != null && user.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original.addresses.isEmpty()) "Add machine" else "Edit ${original.label}") },
        text = {
            Column(Modifier.width(460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    addresses, { addresses = it }, label = { Text("Addresses") }, minLines = 2,
                    supportingText = { Text(badAddress?.let { "Not an address: $it" } ?: "One per line, preferred first (e.g. the LAN IP, then the Tailscale one). host or host:port.") },
                    isError = badAddress != null, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(2f))
                    OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, isError = portNumber == null, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connect with", modifier = Modifier.width(110.dp))
                    FilterChip(protocol == Protocol.MOSH, { protocol = Protocol.MOSH }, label = { Text("mosh") })
                    FilterChip(protocol == Protocol.SSH, { protocol = Protocol.SSH }, label = { Text("SSH") })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sign in with", modifier = Modifier.width(110.dp))
                    FilterChip(auth == AuthMethod.DEVICE_KEY, { auth = AuthMethod.DEVICE_KEY }, label = { Text("Device key") })
                    FilterChip(auth == AuthMethod.PASSWORD, { auth = AuthMethod.PASSWORD }, label = { Text("Password") })
                }
                if (auth == AuthMethod.PASSWORD) {
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("Leave empty to be asked each time. Saved encrypted for this Windows user.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    startup, { startup = it }, label = { Text("Run on connect") }, singleLine = true,
                    supportingText = { Text("Typed into the shell once connected, e.g. herdr or tmux new -A -s main") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth(),
                )
                if (protocol == Protocol.MOSH) {
                    OutlinedTextField(
                        moshServer, { moshServer = it }, label = { Text("mosh-server command") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(original.copy(
                    name = name.trim(), addresses = parsed.mapNotNull { it.second }, port = portNumber ?: 22, user = user.trim(),
                    protocol = protocol, auth = auth, password = if (auth == AuthMethod.PASSWORD) password else "",
                    moshServer = moshServer.trim().ifEmpty { Profile.DEFAULT_MOSH_SERVER }, startup = startup.trim(),
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeviceKeyDialog(app: AppState, onDismiss: () -> Unit) {
    val store = app.platform.desktopStore
    var publicKey by remember { mutableStateOf(store.devicePublicKey) }
    var importing by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun attempt(action: () -> Unit) {
        error = try { action(); null } catch (e: ConnectException) { e.message } catch (e: Exception) { e.message ?: e.toString() }
        publicKey = store.devicePublicKey
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device key") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Machines set to sign in with the device key accept this computer once its public key is in their " +
                        "~/.ssh/authorized_keys.", style = MaterialTheme.typography.bodyMedium,
                )
                val key = publicKey
                if (key != null) {
                    SelectionContainer {
                        Text(key, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp))
                    }
                } else {
                    Text("No device key yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (importing) {
                    OutlinedTextField(privateKey, { privateKey = it }, label = { Text("Private key (OpenSSH or PEM)") }, minLines = 4,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase, if it has one") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (confirmReplace) {
                    Text("Replacing the key locks this computer out of machines that only know the old one.",
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    importing -> {
                        TextButton(onClick = { importing = false }) { Text("Back") }
                        TextButton(onClick = {
                            attempt { Ssh.importDeviceKey(app.platform, privateKey, passphrase.ifEmpty { null }) }
                            if (error == null) { importing = false; privateKey = ""; passphrase = "" }
                        }) { Text("Use this key") }
                    }
                    publicKey != null && !confirmReplace -> {
                        TextButton(onClick = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(publicKey), null) }) {
                            Text("Copy public key")
                        }
                        TextButton(onClick = { confirmReplace = true }) { Text("Replace…") }
                        TextButton(onClick = onDismiss) { Text("Done") }
                    }
                    else -> {
                        TextButton(onClick = { importing = true; confirmReplace = false }) { Text("Import a key") }
                        TextButton(onClick = { attempt { Ssh.generateDeviceKey(app.platform) }; confirmReplace = false }) { Text("Generate new key") }
                        if (confirmReplace) TextButton(onClick = { confirmReplace = false }) { Text("Cancel") }
                    }
                }
            }
        },
    )
}

// --- A session ---------------------------------------------------------------------------------------------------

@Composable
private fun SessionScreen(app: AppState, session: DesktopSession) {
    // A dropped connection reconnects by itself after a moment (the run-on-connect command puts the user back).
    var reconnectIn by remember(session) { mutableStateOf(0) }
    LaunchedEffect(session, session.state) {
        if (!session.dropped) return@LaunchedEffect
        for (s in 3 downTo 1) { reconnectIn = s; delay(1000) }
        reconnectIn = 0
        if (session.dropped && session in app.sessions) app.reconnect(session)
    }
    Column(Modifier.fillMaxSize()) {
        SessionHeader(app, session, reconnectIn)
        // A margin in the terminal's own background color, so text isn't flush against the window edge.
        val background = session.emulator?.mColors?.mCurrentColors?.get(com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(background ?: 0xFF000000.toInt()) .copy(alpha = 1f)).padding(start = 8.dp, top = 4.dp, end = 4.dp)) {
            TerminalView(session, app.fontSize.value)
        }
    }
    session.prompt?.let { PromptDialog(it) }
}

@Composable
private fun SessionHeader(app: AppState, s: DesktopSession, reconnectIn: Int) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.title?.takeIf { it.isNotBlank() } ?: s.profile.label, style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(12.dp))
        val status = when (s.state) {
            DesktopSession.State.CONNECTING -> s.progress
            DesktopSession.State.CONNECTED -> s.route?.let { "${s.profile.protocol.name.lowercase()} via ${it.via} · ${it.address}" } ?: "connected"
            DesktopSession.State.FINISHED -> if (reconnectIn > 0) "connection lost · reconnecting in $reconnectIn" else "disconnected"
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.weight(1f))
        HerdrMenu(s)
        if (s.state == DesktopSession.State.FINISHED) {
            TextButton(onClick = { app.reconnect(s) }) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Reconnect")
            }
        }
    }
}

/** herdr's agents on this machine, if it runs herdr: a summary that opens a switcher. */
@Composable
private fun HerdrMenu(s: DesktopSession) {
    @Suppress("UNUSED_EXPRESSION") s.herdrRevision
    val agents = s.herdr.agents
    val summary = HerdrProtocol.summary(agents) ?: return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Default.SmartToy, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(summary, color = if (agents.any { it.status == HerdrAgent.Status.BLOCKED }) MaterialTheme.colorScheme.tertiary else Color.Unspecified)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            agents.forEach { a ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${HerdrProtocol.glyph(a.status)}  ${a.label}", fontWeight = if (a.focused) FontWeight.Bold else FontWeight.Normal)
                            Text("${a.name} · ${HerdrProtocol.describe(a.status)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { s.herdr.focus(a.paneId); open = false },
                )
            }
        }
    }
}

@Composable
private fun PromptDialog(prompt: PendingPrompt) {
    when (prompt) {
        is PendingPrompt.YesNo -> AlertDialog(
            onDismissRequest = { prompt.answer.complete(false) },
            title = { Text(prompt.title) },
            text = { Text(prompt.message, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { prompt.answer.complete(true) }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { prompt.answer.complete(false) }) { Text("No") } },
        )
        is PendingPrompt.Secret -> {
            var value by remember(prompt) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { prompt.answer.complete(null) },
                title = { Text(prompt.title) },
                text = {
                    Column {
                        Text(prompt.message)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value, { value = it }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    }
                },
                confirmButton = { TextButton(onClick = { prompt.answer.complete(value) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { prompt.answer.complete(null) }) { Text("Cancel") } },
            )
        }
    }
}
