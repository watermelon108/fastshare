package com.fastshare.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ theme

@Composable
fun FastShareTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF8AB4FF), onPrimary = Color(0xFF002F6C))
    } else {
        lightColorScheme(primary = Color(0xFF1F6FEB))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ------------------------------------------------------------------ root

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastShareApp() {
    val devices by Hub.devices.collectAsState()
    val selectedId by Hub.selectedId.collectAsState()
    val fileReq by Hub.fileRequest.collectAsState()
    val pairCode by Hub.pairingCode.collectAsState()
    val unread by Hub.unread.collectAsState()
    val tick by Hub.chatTick.collectAsState()
    val progress by Hub.progress.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var pairTarget by remember { mutableStateOf<Device?>(null) }
    var removeTarget by remember { mutableStateOf<Device?>(null) }
    var codeOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val selected = devices.firstOrNull { it.id == selectedId }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val d = Hub.devices.value.firstOrNull { it.id == Hub.selectedId.value }
        if (d != null && uris.isNotEmpty()) Hub.sendFiles(d, uris)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        Hub.toasts.collectLatest { snackbar.showSnackbar(it) }
    }

    BackHandler(enabled = showSettings || selected != null) {
        if (showSettings) showSettings = false else Hub.selectedId.value = null
    }

    when {
        showSettings -> SettingsScreen(onBack = { showSettings = false })
        selected != null -> ChatScreen(
            d = selected,
            tick = tick,
            progress = progress,
            snackbar = snackbar,
            onBack = { Hub.selectedId.value = null },
            onFile = { fileLauncher.launch(arrayOf("*/*")) },
            onPair = { pairTarget = selected },
            onCode = {
                Hub.newPairingCode()
                codeOpen = true
            },
            onRemove = { removeTarget = selected }
        )
        else -> HomeScreen(
            devices = devices,
            unread = unread,
            snackbar = snackbar,
            onOpen = { Hub.select(it.id) },
            onCode = {
                Hub.newPairingCode()
                codeOpen = true
            },
            onSettings = { showSettings = true }
        )
    }

    pairTarget?.let { d ->
        PairDialog(
            d = d,
            onDismiss = { pairTarget = null },
            onConfirm = { code ->
                Hub.pair(d, code)
                pairTarget = null
            }
        )
    }

    removeTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("${d.alias} remove karein?") },
            text = { Text("Pairing aur is device ki chat history delete ho jaayegi.") },
            confirmButton = {
                TextButton(onClick = {
                    Hub.removeDevice(d.id)
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } }
        )
    }

    if (codeOpen) {
        AlertDialog(
            onDismissRequest = { codeOpen = false },
            title = { Text("Pairing code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        pairCode ?: "------",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Dusre device par yehi code likho. 2 minute tak valid hai.",
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = { TextButton(onClick = { codeOpen = false }) { Text("Band karo") } }
        )
    }

    fileReq?.let { r ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${r.fromAlias} file bhej raha hai") },
            text = { Text("${r.name}\n${human(r.size)}\n\nAccept karein?") },
            confirmButton = { TextButton(onClick = { Hub.answerFileRequest(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { Hub.answerFileRequest(false) }) { Text("Decline") } }
        )
    }
}

// ------------------------------------------------------------------ home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    devices: List<Device>,
    unread: Set<String>,
    snackbar: SnackbarHostState,
    onOpen: (Device) -> Unit,
    onCode: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FastShare", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onCode) { Text("Code") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        if (devices.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Koi device nahi mila", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Same Wi-Fi ya hotspot par doosra phone ya Linux FastShare chalao.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(devices, key = { it.id }) { d ->
                    DeviceRow(d, hasNew = d.id in unread) { onOpen(d) }
                }
            }
        }
    }
}

@Composable
fun DeviceRow(d: Device, hasNew: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                d.alias.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.alias, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                buildString {
                    append(if (d.online) "Online" else "Offline")
                    append("  ·  ")
                    append(if (d.paired) "Paired" else "Pair nahi hai")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (hasNew) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

// ------------------------------------------------------------------ chat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    d: Device,
    tick: Int,
    progress: Float,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onFile: () -> Unit,
    onPair: () -> Unit,
    onCode: () -> Unit,
    onRemove: () -> Unit
) {
    val msgs by produceState(initialValue = emptyList<Msg>(), d.id, tick) {
        value = withContext(Dispatchers.IO) { Hub.db.listMessages(d.id) }
    }
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    val ready = d.paired && d.online
    val ctx = LocalContext.current

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(d.alias, maxLines = 1, fontWeight = FontWeight.Bold)
                        Text(
                            if (d.online) "online" else "offline",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    if (!d.paired && d.online) TextButton(onClick = onPair) { Text("Pair") }
                    TextButton(onClick = onCode) { Text("Code") }
                    if (d.paired) TextButton(onClick = onRemove) { Text("Remove") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (progress >= 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onFile, enabled = ready) { Text("📎") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (ready) "Message" else "Offline ya pair nahi hai") },
                        enabled = ready,
                        maxLines = 4
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            Hub.sendText(d, text.trim())
                            text = ""
                        },
                        enabled = ready && text.isNotBlank()
                    ) { Text("Bhejo") }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(msgs, key = { it.mid }) { m ->
                Bubble(m) { openFile(ctx, m) }
            }
        }
    }
}

@Composable
fun Bubble(m: Msg, onOpen: () -> Unit) {
    val out = m.direction == "out"
    val fg = if (out) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val bg = if (out) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val time = remember(m.ts) {
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(m.ts * 1000))
    }
    val state = when (m.status) {
        "pending" -> "  ·  sending…"
        "failed" -> "  ·  failed"
        else -> ""
    }
    val clickable = m.kind == "file" && m.fileUri != null

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (out) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .then(if (clickable) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(if (m.kind == "file") "📎 ${m.body}" else m.body, color = fg)
            Text(time + state, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.75f))
        }
    }
}

fun openFile(ctx: Context, m: Msg) {
    if (m.kind != "file") return
    val uri = m.fileUri ?: return
    val u = Uri.parse(uri)
    val mime = ctx.contentResolver.getType(u) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(u, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(intent, "Kholo"))
    } catch (_: Exception) {
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
fun PairDialog(d: Device, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${d.alias} se pair karo") },
        text = {
            Column {
                Text("Dusre device par 'Code' dabao, aur wahi 6-digit code yahan likho.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) code = v },
                    singleLine = true,
                    label = { Text("6-digit code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(code) }, enabled = code.length == 6) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------ settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val alias0 by Hub.alias.collectAsState()
    val auto0 by Hub.autoAccept.collectAsState()
    val notif0 by Hub.notifications.collectAsState()
    var alias by remember(alias0) { mutableStateOf(alias0) }
    var auto by remember(auto0) { mutableStateOf(auto0) }
    var notif by remember(notif0) { mutableStateOf(notif0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it.take(40) },
                label = { Text("Device naam") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SwitchRow("Paired device se bina pooche accept karo", auto) { auto = it }
            SwitchRow("Notifications", notif) { notif = it }
            Button(
                onClick = {
                    Hub.saveSettings(alias, auto, notif)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

fun human(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024L * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / 1048576.0)
}
