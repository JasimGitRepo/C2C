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
import androidx.compose.material.icons.rounded.*
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class FsItem(val name: String, val path: String, val isDir: Boolean, val size: Long = 0, val lastModified: Long = 0)
data class TransferJob(val id: String, val fileName: String, var progress: Float, val isUpload: Boolean)

class FileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PremiumTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
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

    LaunchedEffect(localPath, showHidden) {
        withContext(Dispatchers.IO) {
            try {
                val dir = File(localPath)
                if (dir.exists() && dir.isDirectory) {
                    val list = dir.listFiles()?.map { FsItem(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified()) } ?: emptyList()
                    localFiles = list.filter { showHidden || !it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                }
            } catch (e: Exception) { ServerCore.log("FS ERROR: ${e.message}", false) }
        }
    }

    LaunchedEffect(remotePath, showHidden) {
        if (ServerCore.liveSessions.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                ServerCore.liveSessions.forEach { it.send(Frame.Text("""{"cmd":"fs_list","arg":"$remotePath"}""")) }
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("System File Explorer", color = TextPrimary) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                IconButton(onClick = { showHidden = !showHidden }) { 
                    Icon(if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null, tint = TextPrimary) 
                }
            }
        )

        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            FileExplorerGlassCard(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().background(Color(0x22000000)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { val parent = File(localPath).parent; if (parent != null) localPath = parent }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.ArrowUpward, null, tint = ActionBlue) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("LOCAL: $localPath", color = ActionBlue, fontSize = 11.sp, maxLines = 1)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(localFiles) { file -> FileRow(file, ActionBlue) { if (file.isDir) localPath = file.path } }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            FileExplorerGlassCard(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().background(Color(0x22000000)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { val segments = remotePath.trimEnd('/').split("/"); if (segments.size > 1) remotePath = segments.dropLast(1).joinToString("/") + "/" }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.ArrowUpward, null, tint = PremiumTeal) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("REMOTE: $remotePath", color = PremiumTeal, fontSize = 11.sp, maxLines = 1)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (ServerCore.liveSessions.isEmpty()) item { Text("Connection Offline", color = TextSecondary, modifier = Modifier.padding(16.dp)) }
                        else items(remoteFiles) { file -> FileRow(file, PremiumTeal) { if (file.isDir) remotePath = file.path } }
                    }
                }
            }
        }
    }
}

@Composable
fun FileRow(file: FsItem, tint: Color, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (file.isDir) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(file.name, color = TextPrimary, fontSize = 14.sp)
            if (!file.isDir) {
                Text("${file.size / 1024} KB", color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FileExplorerGlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.border(1.dp, GlassBorder, PremiumShapes.medium),
        colors = CardDefaults.cardColors(containerColor = GlassSurface),
        shape = PremiumShapes.medium
    ) { content() }
}