package com.kita.clips

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private val Bg = Color(0xFF0B0D12)
private val CardBg = Color(0xFF151923)
private val Line = Color(0xFF262D3D)
private val Accent = Color(0xFF6C8CFF)
private val Green = Color(0xFF3DDC97)
private val Amber = Color(0xFFFFCC66)
private val Red = Color(0xFFFF6B7A)
private val Muted = Color(0xFF8B94A8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Bg,
                    background = Bg,
                    surface = CardBg,
                    onBackground = Color(0xFFE8ECF5),
                    onSurface = Color(0xFFE8ECF5),
                ),
            ) {
                Box(Modifier.fillMaxSize().background(Bg).navigationBarsPadding()) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Hub.ensureConnected()
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val settings by Hub.settings.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(settings.paired, settings.background) {
        if (settings.paired && settings.background) ClipService.start(ctx) else ClipService.stop(ctx)
    }

    if (settings.paired) {
        HomeScreen(onSettings = { showSettings = true })
    } else {
        PairScreen()
    }
    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
}

// ------------------------------------------------------------------ pairing

/** Parses kitaclips://join?s=<server>&r=<code> */
private fun parseQr(contents: String): Pair<String, String>? {
    val uri = Uri.parse(contents)
    if (uri.scheme != "kitaclips" || uri.host != "join") return null
    val server = uri.getQueryParameter("s") ?: return null
    val room = uri.getQueryParameter("r") ?: return null
    return server to room
}

@Composable
private fun PairScreen() {
    val ctx = LocalContext.current
    val current by Hub.settings.collectAsStateWithLifecycle()
    var server by remember { mutableStateOf(current.server) }
    var room by remember { mutableStateOf(current.room) }
    var error by remember { mutableStateOf<String?>(null) }

    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            val parsed = parseQr(contents)
            if (parsed != null) {
                server = parsed.first
                room = Crypto.format(parsed.second)
                error = null
                Hub.saveSettings(Hub.settings.value.copy(server = server, room = parsed.second))
            } else {
                error = "That QR code isn't from Kita Clips."
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Kita Clips", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Copy on your phone, paste on your Mac. Open your Kita Clips site on the Mac, create a room, then scan its QR code here.",
            color = Muted,
        )
        Button(
            onClick = {
                scan.launch(
                    ScanOptions()
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                        .setPrompt("Scan the QR code on your Mac"),
                )
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Scan QR code") }

        Text("or enter details manually", color = Muted, fontSize = 13.sp)
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Server address") },
            placeholder = { Text("https://clips.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = room,
            onValueChange = { room = it.uppercase() },
            label = { Text("Room code") },
            placeholder = { Text("XXXXX-XXXXX") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = Red) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (server.isBlank()) {
                        error = "Enter the server address first."
                    } else if (Crypto.normalize(room).length != Crypto.CODE_LEN) {
                        error = "A room code has ${Crypto.CODE_LEN} characters."
                    } else {
                        error = null
                        Hub.saveSettings(Hub.settings.value.copy(server = server, room = room))
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Join room") }
            OutlinedButton(
                onClick = {
                    if (server.isBlank()) {
                        error = "Enter the server address first."
                    } else {
                        error = null
                        Hub.saveSettings(Hub.settings.value.copy(server = server, room = Crypto.generateCode()))
                        Toast.makeText(ctx, "Room created. Join it on your Mac with the code in Settings.", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Create room") }
        }
    }
}

// ------------------------------------------------------------------ home

@Composable
private fun HomeScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val clips by Hub.clips.collectAsStateWithLifecycle()
    val status by Hub.status.collectAsStateWithLifecycle()
    val peers by Hub.peers.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Kita Clips", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                StatusLine(status, peers)
            }
            TextButton(onClick = onSettings) { Text("Settings") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Type or paste to send...") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Button(
                onClick = {
                    if (Hub.addLocal(draft)) draft = ""
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.height(56.dp),
            ) { Text("Send") }
        }
        OutlinedButton(
            onClick = {
                val ok = Hub.captureClipboard(ctx)
                Toast.makeText(ctx, if (ok) "Clipboard sent" else "Nothing new on the clipboard", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("Send my clipboard") }

        Spacer(Modifier.height(10.dp))

        if (clips.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No clips yet.\nCopy something, then tap \"Send my clipboard\" or use the Quick Settings tile.",
                    color = Muted,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(clips, key = { it.id }) { clip -> ClipCard(clip) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatusLine(status: Status, peers: Int) {
    val (color, label) = when (status) {
        Status.ONLINE -> Green to run {
            val others = (peers - 1).coerceAtLeast(0)
            if (others == 0) "Connected · just you" else "Connected · $others other device${if (others == 1) "" else "s"}"
        }
        Status.CONNECTING -> Amber to "Connecting…"
        Status.ERROR -> Red to "Check server address"
        Status.OFFLINE -> Muted to "Offline · retrying"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        Text(label, color = Muted, fontSize = 13.sp)
    }
}

private fun timeAgo(ts: Long): String {
    val s = ((System.currentTimeMillis() - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

@Composable
private fun ClipCard(clip: Clip) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                Hub.copyToSystem(clip.text)
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                clip.text,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${clip.device} · ${timeAgo(clip.ts)}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { Hub.delete(clip.id) }) { Text("Delete", color = Red) }
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s by Hub.settings.collectAsStateWithLifecycle()
    var server by remember { mutableStateOf(s.server) }
    var device by remember { mutableStateOf(s.device) }
    var autoCopy by remember { mutableStateOf(s.autoCopy) }
    var background by remember { mutableStateOf(s.background) }
    var confirmLeave by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Room code", color = Muted, fontSize = 12.sp)
                Text(
                    Crypto.format(s.room),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("Room code", Crypto.format(s.room)))
                        Toast.makeText(ctx, "Room code copied", Toast.LENGTH_SHORT).show()
                    },
                )
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text("Server address") }, singleLine = true,
                )
                OutlinedTextField(
                    value = device, onValueChange = { device = it },
                    label = { Text("This device's name") }, singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-copy clips from other devices", Modifier.weight(1f))
                    Switch(checked = autoCopy, onCheckedChange = { autoCopy = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep running in background", Modifier.weight(1f))
                    Switch(checked = background, onCheckedChange = { background = it })
                }
                TextButton(onClick = { confirmLeave = true }) { Text("Leave room", color = Red) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Hub.saveSettings(s.copy(server = server, device = device, autoCopy = autoCopy, background = background))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this room?") },
            text = { Text("This phone stops syncing. Your saved clips stay on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    Hub.saveSettings(s.copy(room = ""))
                    confirmLeave = false
                    onDismiss()
                }) { Text("Leave", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}
