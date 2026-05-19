package com.c2c.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.c2c.CoreViewModel
import com.c2c.ServerCore
import com.c2c.TdAuthState
import com.c2c.data.local.CommandEntity
import com.c2c.ui.theme.*
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    var selectedTab by remember { mutableStateOf(0) }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0x990A0A0F)) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Rounded.Search, null) }, label = { Text("HUB") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Rounded.Chat, null) }, label = { Text("TG CLONE") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Rounded.Dns, null) }, label = { Text("SERVER") })
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> CommandHub(viewModel)
                1 -> TgPager(viewModel)
                2 -> ServerPager(viewModel, eglContext)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandHub(viewModel: CoreViewModel) {
    val commands by viewModel.commands.collectAsState()
    val pendingCommands by viewModel.pendingCommands.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var commandToEdit by remember { mutableStateOf<CommandEntity?>(null) }

    if (showAddDialog || commandToEdit != null) {
        CommandEditorDialog(
            command = commandToEdit,
            onDismiss = { showAddDialog = false; commandToEdit = null },
            onSave = { entity -> 
                viewModel.addCommand(entity)
                showAddDialog = false; commandToEdit = null
            },
            onDelete = {
                commandToEdit?.let { viewModel.deleteCommand(it) }
                showAddDialog = false; commandToEdit = null
            }
        )
    }

    if (showSettingsDialog) {
        CoreSettingsDialog(viewModel = viewModel, onDismiss = { showSettingsDialog = false })
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        
        // 1. The Big Kill Switch & Settings Inline
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.activateKillSwitch() },
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Warning, null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("KILL SWITCH (ABORT QUEUE)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(GlassSurface)
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = ActionBlue)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Quick Actions Panel
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GlassSurface).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val quickCommands = commands.filter { it.category == "Quick" }.take(6) 
            quickCommands.forEach { cmd ->
                val uiKey = "qa_${cmd.id}"
                val isPending = pendingCommands.contains(uiKey)
                val isToggled by remember(cmd.id) { mutableStateOf(viewModel.toggleStates.getOrDefault(uiKey, false)) }

                QuickActionButton(
                    icon = getIconByName(if (isToggled && cmd.isToggle) cmd.toggledIcon() else cmd.icon), 
                    isPending = isPending,
                    onClick = {
                        val actualCmd = if (isToggled && cmd.isToggle) cmd.toggledCmd else cmd.cmd
                        val actualArg = if (isToggled && cmd.isToggle) cmd.toggledArg else cmd.defaultArg
                        viewModel.enqueueCommand(actualCmd, actualArg, uiKey, isLive = false)
                        if (cmd.isToggle) viewModel.toggleStates[uiKey] = !isToggled
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Modern Soft-Key Panel (Live Commands)
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.Black).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val recentKey = "live_btn_recent"
            val homeKey = "live_btn_home"
            val backKey = "live_btn_back"

            SoftKeyButton(Icons.Rounded.Menu, pendingCommands.contains(recentKey)) { viewModel.enqueueCommand("btn_recent", "", recentKey, isLive = true) }
            SoftKeyButton(Icons.Rounded.Circle, pendingCommands.contains(homeKey)) { viewModel.enqueueCommand("btn_home", "", homeKey, isLive = true) }
            SoftKeyButton(Icons.Rounded.ArrowBackIosNew, pendingCommands.contains(backKey)) { viewModel.enqueueCommand("btn_back", "", backKey, isLive = true) }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Mission Directives", color = TextSecondary, fontSize = 12.sp, fontFamily = UbuntuFont)
        Spacer(modifier = Modifier.height(8.dp))

        // 4. Two-Column Dynamic Grid
        val groupedCommands = commands.filter { it.category != "Quick" }.groupBy { it.category }

        LazyColumn(modifier = Modifier.weight(1f)) {
            groupedCommands.forEach { (category, categoryCommands) ->
                stickyHeader {
                    Text(category, style = MaterialTheme.typography.titleSmall, color = PremiumTeal, modifier = Modifier.fillMaxWidth().background(SoulBackground.copy(alpha = 0.9f)).padding(vertical = 4.dp))
                }
                item {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = false, 
                        modifier = Modifier.heightIn(max = (categoryCommands.size * 70).dp) 
                    ) {
                        items(categoryCommands, key = { it.id }) { cmd ->
                            val uiKey = cmd.id.toString()
                            val isPending = pendingCommands.contains(uiKey)
                            val isToggled by remember(cmd.id) { mutableStateOf(viewModel.toggleStates.getOrDefault(uiKey, false)) }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(70.dp) 
                                    .alpha(if (isPending) 0.5f else 1f)
                                    .combinedClickable(
                                        enabled = !isPending,
                                        onClick = {
                                            val actualCmd = if (isToggled && cmd.isToggle) cmd.toggledCmd else cmd.cmd
                                            val actualArg = if (isToggled && cmd.isToggle) cmd.toggledArg else cmd.defaultArg
                                            viewModel.enqueueCommand(actualCmd, actualArg, uiKey, isLive = false)
                                            if (cmd.isToggle) viewModel.toggleStates[uiKey] = !isToggled
                                        },
                                        onLongClick = { commandToEdit = cmd }
                                    ),
                                colors = CardDefaults.cardColors(containerColor = GlassSurface),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isPending) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ActionBlue, strokeWidth = 2.dp)
                                    } else {
                                        Icon(getIconByName(if (isToggled && cmd.isToggle) cmd.toggledIcon() else cmd.icon), null, tint = ActionBlue, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            if (isToggled && cmd.isToggle) cmd.toggledLabel else cmd.label, 
                                            color = TextPrimary, 
                                            fontSize = 11.sp, 
                                            fontWeight = FontWeight.Bold, 
                                            maxLines = 1
                                        )
                                        if (cmd.defaultArg.isNotBlank() || (isToggled && cmd.toggledArg.isNotBlank())) {
                                            Text(
                                                if (isToggled && cmd.isToggle) cmd.toggledArg else cmd.defaultArg, 
                                                color = PremiumTeal, 
                                                fontSize = 9.sp, 
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        FloatingActionButton(onClick = { showAddDialog = true }, modifier = Modifier.padding(24.dp), containerColor = ActionBlue) { Icon(Icons.Rounded.Add, null) }
    }
}

@Composable
fun QuickActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, isPending: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !isPending) {
        if (isPending) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = ActionBlue, strokeWidth = 2.dp)
        } else {
            Icon(icon, null, tint = TextPrimary)
        }
    }
}

@Composable
fun SoftKeyButton(icon: androidx.compose.ui.graphics.vector.ImageVector, isPending: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !isPending, modifier = Modifier.alpha(if (isPending) 0.5f else 1f)) {
        Icon(icon, null, tint = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandEditorDialog(command: CommandEntity?, onDismiss: () -> Unit, onSave: (CommandEntity) -> Unit, onDelete: () -> Unit) {
    var label by remember { mutableStateOf(command?.label ?: "") }
    var cmd by remember { mutableStateOf(command?.cmd ?: "") }
    var arg by remember { mutableStateOf(command?.defaultArg ?: "") }
    var icon by remember { mutableStateOf(command?.icon ?: "code") }
    var category by remember { mutableStateOf(command?.category ?: "System") }
    var isToggle by remember { mutableStateOf(command?.isToggle ?: false) }
    var toggledLabel by remember { mutableStateOf(command?.toggledLabel ?: "") }
    var toggledCmd by remember { mutableStateOf(command?.toggledCmd ?: "") }
    var toggledArg by remember { mutableStateOf(command?.toggledArg ?: "") }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = TgHeader), 
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(if (command == null) "New Command" else "Edit Command", color = ActionBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = cmd, onValueChange = { cmd = it }, label = { Text("Command (e.g. loc)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = arg, onValueChange = { arg = it }, label = { Text("Argument (Optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Icon string") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isToggle, onCheckedChange = { isToggle = it }, colors = CheckboxDefaults.colors(checkedColor = ActionBlue))
                    Text("Toggle Command", color = TextPrimary)
                }

                if (isToggle) {
                    OutlinedTextField(value = toggledLabel, onValueChange = { toggledLabel = it }, label = { Text("Toggled Label") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = toggledCmd, onValueChange = { toggledCmd = it }, label = { Text("Toggled Command") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = toggledArg, onValueChange = { toggledArg = it }, label = { Text("Toggled Argument (Optional)") }, modifier = Modifier.fillMaxWidth())
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (command != null) {
                        TextButton(onClick = onDelete) { Text("DELETE", color = ErrorRed) }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("CANCEL", color = TextSecondary) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { 
                        val updatedCommand = (command ?: CommandEntity(id=0, label="", cmd="", defaultArg="", icon="", category="")).copy(
                            label = label, cmd = cmd, defaultArg = arg, icon = icon, category = category,
                            isToggle = isToggle, toggledLabel = toggledLabel, toggledCmd = toggledCmd, toggledArg = toggledArg
                        )
                        onSave(updatedCommand) 
                    }, colors = ButtonDefaults.buttonColors(containerColor = ActionBlue)) { Text("SAVE") }
                }
            }
        }
    }
}

@Composable
fun CoreSettingsDialog(viewModel: CoreViewModel, onDismiss: () -> Unit) {
    var settings by remember { mutableStateOf(viewModel.getSettings()) }
    var selectedTab by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = TgHeader), 
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Core Engine Settings", color = ActionBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) { Text("Connection", modifier = Modifier.padding(8.dp)) }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) { Text("Telegram", modifier = Modifier.padding(8.dp)) }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (selectedTab == 0) {
                    OutlinedTextField(value = settings.ntfyUrl, onValueChange = { settings = settings.copy(ntfyUrl = it) }, label = { Text("Ntfy URL") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = settings.ntfyTopic, onValueChange = { settings = settings.copy(ntfyTopic = it) }, label = { Text("Ntfy Topic") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = settings.serverIp, onValueChange = { settings = settings.copy(serverIp = it) }, label = { Text("Server IP (for Ktor bind)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = settings.port, onValueChange = { settings = settings.copy(port = it) }, label = { Text("Server Port") }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = settings.apiId, onValueChange = { settings = settings.copy(apiId = it) }, label = { Text("API ID") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = settings.apiHash, onValueChange = { settings = settings.copy(apiHash = it) }, label = { Text("API Hash") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = settings.chatId, onValueChange = { settings = settings.copy(chatId = it) }, label = { Text("Target Chat ID") }, modifier = Modifier.fillMaxWidth())
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("CANCEL", color = TextSecondary) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { viewModel.saveSettings(settings); onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = ActionBlue)) {
                        Text("SAVE")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TgPager(viewModel: CoreViewModel) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    Column {
        TabRow(
            selectedTabIndex = pagerState.currentPage, 
            containerColor = Color.Transparent, 
            contentColor = PremiumTeal, 
            indicator = { tabPositions -> 
                TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]), color = PremiumTeal) 
            }
        ) {
            Tab(selected = pagerState.currentPage == 0, onClick = { }, text = { Text("CHAT", fontFamily = UbuntuFont) })
            Tab(selected = pagerState.currentPage == 1, onClick = { }, text = { Text("TDLIB LOGS", fontFamily = UbuntuFont) })
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when(page) { 
                0 -> TelegramCloneTab(viewModel)
                1 -> TdLibLogScreen(viewModel) 
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerPager(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    Column {
        TabRow(
            selectedTabIndex = pagerState.currentPage, 
            containerColor = Color.Transparent, 
            contentColor = PremiumTeal, 
            indicator = { tabPositions -> 
                TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]), color = PremiumTeal) 
            }
        ) {
            Tab(selected = pagerState.currentPage == 0, onClick = { }, text = { Text("TERMINAL", fontFamily = UbuntuFont) })
            Tab(selected = pagerState.currentPage == 1, onClick = { }, text = { Text("WEBRTC", fontFamily = UbuntuFont) })
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when(page) {
                0 -> TerminalPage(viewModel, LocalContext.current)
                1 -> LiveWebRtcHub(viewModel, eglContext)
            }
        }
    }
}

@Composable
fun TerminalPage(viewModel: CoreViewModel, context: Context) {
    val isRunning by ServerCore.isRunningFlow.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.serverLogs.size) { if(viewModel.serverLogs.isNotEmpty()) listState.animateScrollToItem(viewModel.serverLogs.size - 1) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { viewModel.toggleServer(context) },
            colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) PremiumRose else ActionBlue),
            modifier = Modifier.height(56.dp).fillMaxWidth()
        ) {
            Text(if (isRunning) "TERMINATE NODE" else "INITIALIZE NODE")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("System Event Log", color = TextSecondary, fontSize = 14.sp, fontFamily = UbuntuFont)
            IconButton(onClick = { viewModel.serverLogs.clear() }) { Icon(Icons.Rounded.DeleteOutline, null, tint = TextSecondary) }
        }
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top=8.dp).border(1.dp, GlassBorder, PremiumShapes.medium),
            colors = CardDefaults.cardColors(containerColor = GlassSurface)
        ) {
            LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) { 
                items(viewModel.serverLogs) { log -> 
                    val color = if (log.contains("ERROR") || log.contains("FAIL") || log.contains("SEVERED") || log.contains("DROP") || log.contains("KILL")) PremiumRose 
                                else if (log.contains("SUCCESS") || log.contains("ESTABLISHED")) PremiumTeal 
                                else if (log.contains("QUEUED") || log.contains("SENT")) ActionBlue
                                else TextPrimary
                    Text(log, color = color, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(vertical = 2.dp), fontFamily = CyberFont) 
                } 
            }
        }
    }
}

@Composable
fun LiveWebRtcHub(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    val pendingLiveCommands by viewModel.pendingCommands.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val streamKey = "live_stream_start"
            Button(
                onClick = { viewModel.initiateWebRtcConnection(); viewModel.enqueueCommand("live_screen_cast", "start", streamKey, isLive = true) }, 
                modifier = Modifier.weight(1f),
                enabled = !pendingLiveCommands.contains(streamKey)
            ) { Text("START STREAM") }
            Button(
                onClick = { viewModel.terminateWebRtcConnection() }, 
                modifier = Modifier.weight(1f), 
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
            ) { Text("STOP") }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val camFKey = "live_cam_front"
            val camBKey = "live_cam_back"
            val micKey = "live_audio_mode"

            Button(
                onClick = { viewModel.enqueueCommand("stream_cam_front", "start", camFKey, isLive = true) }, 
                modifier = Modifier.weight(1f),
                enabled = !pendingLiveCommands.contains(camFKey)
            ) { Text("CAM F") }
            Button(
                onClick = { viewModel.enqueueCommand("stream_cam_back", "start", camBKey, isLive = true) }, 
                modifier = Modifier.weight(1f),
                enabled = !pendingLiveCommands.contains(camBKey)
            ) { Text("CAM B") }
            Button(
                onClick = { viewModel.enqueueCommand("live_audio_mode", "mic", micKey, isLive = true) }, 
                modifier = Modifier.weight(1f),
                enabled = !pendingLiveCommands.contains(micKey)
            ) { Text("MIC") }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black).clip(RoundedCornerShape(8.dp))) {
            if (viewModel.remoteVideoTrack != null) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(eglContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                            setEnableHardwareScaler(true)
                        }
                    },
                    update = { view -> viewModel.remoteVideoTrack?.addSink(view) },
                    onRelease = { view -> viewModel.remoteVideoTrack?.removeSink(view); view.release() },
                    modifier = Modifier.fillMaxSize()
                )
            } else Text("Waiting for WebRTC Video Track...", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.activateKillSwitch() }, // Kill switch also applies to live commands
            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
            modifier = Modifier.fillMaxWidth()
        ) { Text("RELEASE LIVE LOCKS") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramCloneTab(viewModel: CoreViewModel) {
    var inputTxt by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.tgMessages.size) { if(viewModel.tgMessages.isNotEmpty()) listState.animateScrollToItem(viewModel.tgMessages.size - 1) }

    Column(modifier = Modifier.fillMaxSize().background(TgBackground)) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(ActionBlue), contentAlignment = Alignment.Center) { Text(viewModel.tgChatName.take(1), color = Color.White, fontSize = 18.sp) }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(viewModel.tgChatName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, fontFamily = UbuntuFont)
                        Text(if (viewModel.isTdLibEngineRunning) "TDLib Engine Syncing..." else "Engine Offline", color = if(viewModel.isTdLibEngineRunning) SuccessGreen else TextSecondary, fontSize = 11.sp, fontFamily = UbuntuFont)
                    }
                }
            },
            actions = {
                IconButton(onClick = { viewModel.toggleTdLib() }) { Icon(Icons.Rounded.PowerSettingsNew, null, tint = if (viewModel.isTdLibEngineRunning) SuccessGreen else PremiumRose) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = TgHeader)
        )
        
        if (viewModel.tdAuthState == TdAuthState.WAIT_PHONE || viewModel.tdAuthState == TdAuthState.WAIT_CODE || viewModel.tdAuthState == TdAuthState.WAIT_PASSWORD) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Card(colors = CardDefaults.cardColors(containerColor = TgHeader), modifier = Modifier.padding(32.dp)) {
                    var authInput by remember { mutableStateOf("") }
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(when(viewModel.tdAuthState) { TdAuthState.WAIT_PHONE -> "Enter Phone (+1234567890)"; TdAuthState.WAIT_CODE -> "Enter Telegram Code"; TdAuthState.WAIT_PASSWORD -> "Enter 2FA Password"; else -> ""}, color = Color.White, fontFamily = UbuntuFont)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = authInput, onValueChange = { authInput = it }, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White))
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            when(viewModel.tdAuthState) {
                                TdAuthState.WAIT_PHONE -> viewModel.tdSendPhone(authInput)
                                TdAuthState.WAIT_CODE -> viewModel.tdSendCode(authInput)
                                TdAuthState.WAIT_PASSWORD -> viewModel.tdSendPassword(authInput)
                                else -> {}
                            }
                            authInput = ""
                        }) { Text("SUBMIT") }
                    }
                }
            }
        } else if (viewModel.tdAuthState == TdAuthState.CLOSED || !viewModel.isTdLibEngineRunning) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CloudOff, null, tint = TextSecondary, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("TDLib Engine Offline", color = TextSecondary, fontFamily = UbuntuFont)
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Spacer(modifier = Modifier.height(16.dp)) }
                items(viewModel.tgMessages) { msg ->
                    val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(msg.timestamp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start) {
                        Card(
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if(msg.isMe) 16.dp else 4.dp, bottomEnd = if(msg.isMe) 4.dp else 16.dp),
                            colors = CardDefaults.cardColors(containerColor = if(msg.isMe) TgBubbleOutgoing else TgBubbleIncoming),
                            modifier = Modifier.widthIn(max = 300.dp)
                        ) {
                            Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp)) {
                                Text(msg.text, color = Color.White, fontSize = 15.sp, fontFamily = UbuntuFont)
                                Row(modifier = Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(timeStr, color = Color(0x99FFFFFF), fontSize = 11.sp)
                                    if(msg.isMe) { Spacer(modifier = Modifier.width(4.dp)); Icon(Icons.Rounded.Check, null, tint = ActionBlue, modifier = Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background(TgInputBg).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = inputTxt, onValueChange = { inputTxt = it },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = UbuntuFont),
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).padding(start = 12.dp),
                decorationBox = { inner -> if (inputTxt.isEmpty()) Text("Message", color = TextSecondary, fontSize = 16.sp, fontFamily = UbuntuFont); inner() }
            )
            if (inputTxt.isNotBlank()) {
                IconButton(onClick = { viewModel.sendTelegramMessage(inputTxt, isMe = false); inputTxt = "" }) { Icon(Icons.Rounded.Send, null, tint = ActionBlue) }
            }
        }
    }
}

@Composable
fun TdLibLogScreen(viewModel: CoreViewModel) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.tdLogs.size) { if(viewModel.tdLogs.isNotEmpty()) listState.animateScrollToItem(viewModel.tdLogs.size - 1) }

    Column(modifier = Modifier.fillMaxSize().background(TgBackground)) {
        Row(modifier = Modifier.fillMaxWidth().background(TgHeader).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("⯇ TDLib Diagnostic Engine", color = ActionBlue, fontFamily = UbuntuFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            IconButton(onClick = { viewModel.dumpTdLogs(context) }) { Icon(Icons.Rounded.SaveAlt, null, tint = SuccessGreen) }
        }
        
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(12.dp)) {
            items(viewModel.tdLogs) { log ->
                val color = when {
                    log.contains("[ERROR]") -> PremiumRose
                    log.contains("[SUCCESS]") -> SuccessGreen
                    log.contains("[WARN]") -> PremiumTeal
                    else -> TextSecondary
                }
                Text(log, color = color, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(vertical = 2.dp), fontFamily = CyberFont) 
            }
        }
    }
}

fun CommandEntity.toggledIcon(): String {
    return when(this.cmd) {
        "flash" -> "flashlight_off" 
        "vol" -> "volume_off" 
        "icon_hide" -> "visibility" 
        "toggle_wifi" -> "wifi_off" 
        "toggle_hotspot" -> "hotspot_off" 
        "call" -> "phone_disabled" 
        "halt_workflow" -> "play_arrow" 
        else -> this.icon 
    }
}

fun getIconByName(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when(name) {
        "flash", "flashlight", "flashlight_on" -> Icons.Rounded.FlashlightOn
        "flashlight_off" -> Icons.Rounded.FlashlightOff 
        "screen", "light_mode" -> Icons.Rounded.LightMode
        "loc", "location", "location_on" -> Icons.Rounded.LocationOn
        "wifi" -> Icons.Rounded.Wifi
        "wifi_off" -> Icons.Rounded.WifiOff 
        "bluetooth" -> Icons.Rounded.Bluetooth
        "hotspot_off" -> Icons.Rounded.WifiOff 
        "folder" -> Icons.Rounded.FolderOpen
        "radar" -> Icons.Rounded.Radar
        "camera_front" -> Icons.Rounded.CameraFront
        "camera_rear" -> Icons.Rounded.CameraRear
        "mic" -> Icons.Rounded.Mic
        "info" -> Icons.Rounded.Info
        "description", "log" -> Icons.Rounded.Description
        "delete_sweep", "clear" -> Icons.Rounded.DeleteSweep
        "power_settings_new", "power" -> Icons.Rounded.PowerSettingsNew
        "router", "server" -> Icons.Rounded.Router
        "volume_up", "vol" -> Icons.Rounded.VolumeUp
        "volume_off" -> Icons.Rounded.VolumeOff 
        "screenshot_monitor" -> Icons.Rounded.ScreenshotMonitor
        "track_changes" -> Icons.Rounded.TrackChanges
        "play_arrow" -> Icons.Rounded.PlayArrow
        "account_tree" -> Icons.Rounded.AccountTree
        "upload_file" -> Icons.Rounded.UploadFile
        "system_update" -> Icons.Rounded.SystemUpdate
        "visibility_off" -> Icons.Rounded.VisibilityOff
        "visibility" -> Icons.Rounded.Visibility 
        "phone" -> Icons.Rounded.Phone
        "phone_disabled" -> Icons.Rounded.PhoneDisabled
        "cloud_download" -> Icons.Rounded.CloudDownload
        "network_wifi" -> Icons.Rounded.NetworkWifi 
        "stop" -> Icons.Rounded.Stop 
        "menu" -> Icons.Rounded.Menu 
        "circle" -> Icons.Rounded.Circle 
        "arrow_back_ios_new" -> Icons.Rounded.ArrowBackIosNew 
        else -> Icons.Rounded.Code
    }
}