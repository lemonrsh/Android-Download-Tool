package dev.fetchly

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Links arriving via Share or fetchly:// land here and always need the user's confirmation. */
object Inbox { val url = mutableStateOf<String?>(null) }

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadManager.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Prefs.integration(this)) DownloadManager.ensureService()
        handle(intent)
        setContent { MaterialTheme { FetchlyScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val url = when {
            i?.action == Intent.ACTION_SEND ->
                i.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value }
            i?.action == Intent.ACTION_VIEW && i.data?.scheme == "fetchly" -> i.data?.getQueryParameter("url")
            else -> null
        } ?: return
        Inbox.url.value = url
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FetchlyScreen() {
    val all by DownloadManager.items.collectAsState()
    var filter by remember { mutableStateOf("All") }
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val pending = Inbox.url.value
    val shown = if (filter == "All") all else all.filter { it.category == filter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fetchly") },
                actions = { TextButton(onClick = { showSettings = true }) { Text("Settings") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add download") }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(CATEGORIES) { c ->
                    FilterChip(selected = filter == c, onClick = { filter = c }, label = { Text(c) })
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No downloads yet") }
            } else {
                LazyColumn { items(shown, key = { it.id }) { DownloadRow(it) } }
            }
        }
    }

    if (showAdd || pending != null) {
        AddDialog(initial = pending.orEmpty(), onDismiss = { showAdd = false; Inbox.url.value = null })
    }
    if (showSettings) SettingsDialog { showSettings = false }
}

@Composable
fun DownloadRow(d: Item) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(d.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            when {
                d.status == Status.COMPLETED -> {}
                d.total > 0 -> LinearProgressIndicator(
                    progress = { (d.downloaded.toFloat() / d.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                d.status == Status.DOWNLOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val size = if (d.total > 0) "${fmtBytes(d.downloaded)} / ${fmtBytes(d.total)}" else fmtBytes(d.downloaded)
            val state = when (d.status) {
                Status.DOWNLOADING -> fmtSpeed(d.speed)
                Status.FAILED -> "Failed: ${d.error ?: "unknown error"}"
                else -> d.status.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            Text("$size · $state", style = MaterialTheme.typography.bodySmall)
            Row {
                when (d.status) {
                    Status.DOWNLOADING, Status.QUEUED -> TextButton({ DownloadManager.pause(d.id) }) { Text("Pause") }
                    Status.PAUSED, Status.FAILED -> TextButton({ DownloadManager.start(d.id) }) { Text("Resume") }
                    Status.COMPLETED -> TextButton({ openFile(ctx, d) }) { Text("Open") }
                }
                TextButton({ DownloadManager.remove(d.id) }) {
                    Text(if (d.status == Status.COMPLETED) "Remove from list" else "Delete")
                }
            }
        }
    }
}

private fun openFile(ctx: Context, d: Item) {
    val uri = d.uri?.let(Uri::parse) ?: return
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(d.fileName.substringAfterLast('.', "").lowercase()) ?: "*/*"
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app can open this file. It's in Downloads/Fetchly.", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun AddDialog(initial: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember {
        mutableStateOf(initial.ifEmpty {
            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (clip?.startsWith("http") == true) clip else ""
        })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add download") },
        text = { OutlinedTextField(text, { text = it }, label = { Text("URL") }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                if (DownloadManager.add(text) == null)
                    Toast.makeText(ctx, "That doesn't look like an http(s) link", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var seg by remember { mutableFloatStateOf(Prefs.segments(ctx).toFloat()) }
    var integ by remember { mutableStateOf(Prefs.integration(ctx)) }
    val token = remember { Prefs.token(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Connections per download: ${seg.toInt()}")
                Slider(
                    value = seg,
                    onValueChange = { seg = it; Prefs.setSegments(ctx, it.toInt()) },
                    valueRange = 1f..8f, steps = 6,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Browser integration", Modifier.weight(1f))
                    Switch(checked = integ, onCheckedChange = {
                        integ = it
                        Prefs.setIntegration(ctx, it)
                        if (it) DownloadManager.ensureService() else DownloadManager.maybeStopService()
                    })
                }
                Spacer(Modifier.height(12.dp))
                Text("Pairing token (paste into the extension options)", style = MaterialTheme.typography.bodySmall)
                Text(token, fontFamily = FontFamily.Monospace)
                TextButton({
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Fetchly token", token))
                    Toast.makeText(ctx, "Token copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy token") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
