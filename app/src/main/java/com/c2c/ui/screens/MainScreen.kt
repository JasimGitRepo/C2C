package com.c2c.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.c2c.CoreViewModel
import com.c2c.data.local.CommandEntity
import com.c2c.getIconByName
import com.c2c.ui.theme.*
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun MainScreen(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0x990A0A0F)) {
                NavigationBarItem(
                    selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Rounded.Search, null) },
                    label = { Text("COMMAND HUB") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Rounded.Add, null) }, // Use Live icon in production
                    label = { Text("LIVE WEBRTC") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedTab == 0) CommandHub(viewModel)
            else LiveWebRtcHub(viewModel, eglContext)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandHub(viewModel: CoreViewModel) {
    val commands by viewModel.commands.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var commandToEdit by remember { mutableStateOf<CommandEntity?>(null) }

    val filteredCommands = commands.filter { it.label.contains(searchQuery, ignoreCase = true) }

    if (showAddDialog || commandToEdit != null) {
        CommandEditorDialog(
            command = commandToEdit,
            onDismiss = { showAddDialog = false; commandToEdit = null },
            onSave = { label, cmd, arg, icon -> 
                viewModel.addCommand(label, cmd, arg, icon)
                showAddDialog = false
                commandToEdit = null
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("Search directives...") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = GlassSurface, unfocusedContainerColor = GlassSurface)
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredCommands, key = { it.id }) { cmd ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .combinedClickable(
                            onClick = { viewModel.executeCommand(cmd.cmd, cmd.defaultArg) },
                            onLongClick = { commandToEdit = cmd } // Edit or Delete
                        ),
                    colors = CardDefaults.cardColors(containerColor = GlassSurface)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(getIconByName(cmd.icon), null, tint = ActionBlue)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(cmd.label, color = TextPrimary)
                            if (cmd.defaultArg.isNotBlank()) {
                                Text("Arg: ${cmd.defaultArg}", color = PremiumTeal, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.padding(24.dp),
            containerColor = ActionBlue
        ) {
            Icon(Icons.Rounded.Add, null)
        }
    }
}

@Composable
fun CommandEditorDialog(command: CommandEntity?, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var label by remember { mutableStateOf(command?.label ?: "") }
    var cmd by remember { mutableStateOf(command?.cmd ?: "") }
    var arg by remember { mutableStateOf(command?.defaultArg ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (command == null) "New Command" else "Edit Command") },
        text = {
            Column {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") })
                OutlinedTextField(value = cmd, onValueChange = { cmd = it }, label = { Text("Command (e.g. loc)") })
                OutlinedTextField(value = arg, onValueChange = { arg = it }, label = { Text("Argument (Optional)") })
            }
        },
        confirmButton = { Button(onClick = { onSave(label, cmd, arg, "code") }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
fun LiveWebRtcHub(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.executeCommand("live_screen_cast", "start") }, modifier = Modifier.weight(1f)) { Text("START STREAM") }
            Button(onClick = { viewModel.executeCommand("live_screen_cast", "stop") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)) { Text("STOP") }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Hardware-Accelerated WebRTC Video Renderer
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            if (viewModel.remoteVideoTrack != null) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(eglContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                            setEnableHardwareScaler(true) // Crucial for performance
                        }
                    },
                    update = { view ->
                        viewModel.remoteVideoTrack?.addSink(view)
                    },
                    onRelease = { view ->
                        viewModel.remoteVideoTrack?.removeSink(view)
                        view.release()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("Waiting for WebRTC Video Track...", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}