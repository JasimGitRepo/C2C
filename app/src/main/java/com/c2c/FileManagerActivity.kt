package com.c2c

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c2c.ui.theme.*
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FsItem(val name: String, val path: String, val isDir: Boolean, val size: Long = 0, val lastModified: Long = 0)
data class TransferJob(val id: String, val fileName: String, var progress: Float, val isUpload: Boolean)

class FileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = CyberBlack) {
                    DualPaneFileManager()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualPaneFileManager() {
    val scope = rememberCoroutineScope()
    var localPath by remember { mutableStateOf(Environment.getExternalStorageDirectory().absolutePath) }
    var remotePath by remember { mutableStateOf("/storage/emulated/0/") }

    var localFiles by remember { mutableStateOf<List<FsItem>>(emptyList()) }
    var remoteFiles by remember { mutableStateOf<List<FsItem>>(emptyList()) }

    var showHidden by remember { mutableStateOf(false) }
    var showTransfers by remember { mutableStateOf(false) }
    var transfers = remember { mutableStateListOf<TransferJob>() }

    LaunchedEffect(localPath, showHidden) {
        withContext(Dispatchers.IO) {
            val dir = File(localPath)
            if (dir.exists() && dir.isDirectory) {
                val list = dir.listFiles()?.map { FsItem(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified()) } ?: emptyList()
                localFiles = list.filter { showHidden || !it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            }
        }
    }

    LaunchedEffect(remotePath, showHidden) {
        if (ServerCore.liveSession?.isActive == true) {
            scope.launch(Dispatchers.IO) {
                ServerCore.liveSession?.send(Frame.Text("""{"cmd":"fs_list","arg":"$remotePath"}"""))
            }
        }
    }

    LaunchedEffect(Unit) {
        ServerCore.logsFlow.collect { log ->
            if (log.contains("RECV_JSON:") && log.contains("\"type\":\"fs_list\"")) {
                try {
                    val json = JSONObject(log.substringAfter("RECV_JSON: "))
                    val array = json.getJSONArray("data")
                    val list = mutableListOf<FsItem>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(FsItem(obj.getString("name"), obj.getString("path"), obj.getBoolean("isDir"), obj.optLong("size", 0), obj.optLong("modified", 0)))
                    }
                    remoteFiles = list.filter { showHidden || !it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                } catch (e: Exception) {}
            }
        }
    }

    if (showTransfers) {
        AlertDialog(
            onDismissRequest = { showTransfers = false },
            title = { Text("Active Transfers", fontFamily = CyberFont, color = CyberNeonCyan) },
            containerColor = CyberDarkGray,
            text = {
                LazyColumn {
                    items(transfers) { job ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("${if (job.isUpload) "UP" else "DOWN"}: ${job.fileName}", color = CyberWhite, fontFamily = UbuntuInputFont, fontSize = 12.sp)
                            LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().height(4.dp), color = CyberNeonPink)
                        }
                    }
                    if (transfers.isEmpty()) item { Text("No active transfers.", color = Color.Gray, fontFamily = CyberFont) }
                }
            },
            confirmButton = { TextButton(onClick = { showTransfers = false }) { Text("CLOSE", color = CyberNeonCyan, fontFamily = CyberFont) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("C2C FILE PROTOCOL", fontFamily = CyberFont, color = CyberBlack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberNeonCyan),
            actions = {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { showTransfers = true }) { Icon(Icons.Default.SwapVert, "Transfers", tint = CyberBlack) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = CyberBlack) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.background(CyberDarkGray)) {
                        DropdownMenuItem(text = { Text(if (showHidden) "Hide Dotted Files" else "Show Dotted Files", color = CyberWhite, fontFamily = CyberFont) }, onClick = { showHidden = !showHidden; menuExpanded = false })
                        DropdownMenuItem(text = { Text("Download via URL", color = CyberNeonYellow, fontFamily = CyberFont) }, onClick = { menuExpanded = false })
                    }
                }
            }
        )

        Column(modifier = Modifier.weight(1f).padding(8.dp)) {
            Card(modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, CyberNeonYellow, MaterialTheme.shapes.small), colors = CardDefaults.cardColors(containerColor = CyberDarkGray)) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().background(CyberBlack).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { val parent = File(localPath).parent; if (parent != null) localPath = parent }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ArrowUpward, "Up", tint = CyberNeonYellow) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("LOCAL: $localPath", color = CyberNeonYellow, fontFamily = CyberFont, fontSize = 10.sp, maxLines = 1)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(localFiles) { file -> FileRow(file, CyberNeonYellow) { if (file.isDir) localPath = file.path } }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, CyberNeonPink, MaterialTheme.shapes.small), colors = CardDefaults.cardColors(containerColor = CyberDarkGray)) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().background(CyberBlack).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { val segments = remotePath.trimEnd('/').split("/"); if (segments.size > 1) remotePath = segments.dropLast(1).joinToString("/") + "/" }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ArrowUpward, "Up", tint = CyberNeonPink) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("REMOTE: $remotePath", color = CyberNeonPink, fontFamily = CyberFont, fontSize = 10.sp, maxLines = 1)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (ServerCore.liveSession?.isActive != true) item { Text("CLIENT DISCONNECTED", color = LogMediumRed, fontFamily = CyberFont, modifier = Modifier.padding(16.dp)) }
                        else items(remoteFiles) { file -> FileRow(file, CyberNeonPink) { if (file.isDir) remotePath = file.path } }
                    }
                }
            }
        }
    }
}

@Composable
fun FileRow(file: FsItem, tint: Color, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (file.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(file.name, color = CyberWhite, fontFamily = UbuntuInputFont, fontSize = 14.sp)
            if (!file.isDir) {
                Text("${file.size / 1024} KB", color = Color.Gray, fontFamily = CyberFont, fontSize = 10.sp)
            }
        }
    }
}