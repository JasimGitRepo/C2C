package com.c2c

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.c2c.data.local.CommandEntity
import com.c2c.ui.theme.*
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {
    private val viewModel: CoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val eglContext = viewModel.webRtcManager.getEglBaseContext()

        setContent { 
            PremiumTheme { 
                AppNavigation(this, viewModel, eglContext) 
            } 
        }
    }

    fun promptAuth(onSuccess: () -> Unit) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("System Authentication")
            .setSubtitle("Identity required")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() { 
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { onSuccess() } 
        }).authenticate(promptInfo)
    }
}

@Composable
fun AppNavigation(activity: MainActivity, viewModel: CoreViewModel, eglContext: EglBase.Context) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "login") {
        composable("login") { 
            LoginScreen({ navController.navigate("main") { popUpTo("login") { inclusive = true } } }, activity) 
        }
        composable("main") { 
            MainScreen(viewModel, eglContext) 
        }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, activity: MainActivity) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Fingerprint, "Auth", tint = ActionBlue, modifier = Modifier.size(80.dp)); Spacer(modifier = Modifier.height(24.dp))
        Text("CORE SYSTEM", fontSize = 28.sp, fontWeight = FontWeight.Light, color = TextPrimary, letterSpacing = 2.sp, fontFamily = UbuntuFont); Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = { activity.promptAuth(onLoginSuccess) }, 
            colors = ButtonDefaults.buttonColors(containerColor = GlassSurface, contentColor = ActionBlue), 
            modifier = Modifier.height(56.dp).fillMaxWidth()
        ) {
            Icon(Icons.Rounded.LockOpen, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp))
            Text("AUTHENTICATE", fontWeight = FontWeight.Medium, fontFamily = UbuntuFont)
        }
    }
}

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
                showAddDialog = false; commandToEdit = null
            },
            onDelete = {
                commandToEdit?.let { viewModel.deleteCommand(it) }
                showAddDialog = false; commandToEdit = null
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("Search directives...") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = GlassSurface, unfocusedContainerColor = GlassSurface)
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredCommands, key = { it.id }) { cmd ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(
                        onClick = { viewModel.executeCommand(cmd.cmd, cmd.defaultArg) },
                        onLongClick = { commandToEdit = cmd }
                    ), colors = CardDefaults.cardColors(containerColor = GlassSurface)
                ) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(getIconByName(cmd.icon), null, tint = ActionBlue)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(cmd.label, color = TextPrimary)
                            if (cmd.defaultArg.isNotBlank()) Text("Arg: ${cmd.defaultArg}", color = PremiumTeal, style = MaterialTheme.typography.bodySmall)
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
fun CommandEditorDialog(command: CommandEntity?, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit, onDelete: () -> Unit) {
    var label by remember { mutableStateOf(command?.label ?: "") }
    var cmd by remember { mutableStateOf(command?.cmd ?: "") }
    var arg by remember { mutableStateOf(command?.defaultArg ?: "") }
    var icon by remember { mutableStateOf(command?.icon ?: "code") }
    
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (command == null) "New Command" else "Edit Command") },
        text = {
            Column {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") })
                OutlinedTextField(value = cmd, onValueChange = { cmd = it }, label = { Text("Command (e.g. loc)") })
                OutlinedTextField(value = arg, onValueChange = { arg = it }, label = { Text("Argument (Optional)") })
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Icon string") })
            }
        },
        confirmButton = { Button(onClick = { onSave(label, cmd, arg, icon) }) { Text("SAVE") } },
        dismissButton = {
            Row {
                if (command != null) TextButton(onClick = onDelete) { Text("DELETE", color = ErrorRed) }
                TextButton(onClick = onDismiss) { Text("CANCEL") }
            }
        }
    )
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
                0 -> TerminalPage(viewModel, LocalContext.current, ServerCore.isRunning)
                1 -> LiveWebRtcHub(viewModel, eglContext)
            }
        }
    }
}

@Composable
fun TerminalPage(viewModel: CoreViewModel, context: android.content.Context, isRunning: Boolean) {
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
                    val color = if (log.contains("ERROR") || log.contains("FAIL") || log.contains("SEVERED")) PremiumRose 
                                else if (log.contains("SUCCESS") || log.contains("ESTABLISHED")) PremiumTeal 
                                else TextPrimary
                    Text(log, color = color, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 2.dp), fontFamily = CyberFont) 
                } 
            }
        }
    }
}

@Composable
fun LiveWebRtcHub(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.initiateWebRtcConnection(); viewModel.sendLive("live_screen_cast", "start") }, modifier = Modifier.weight(1f)) { Text("START STREAM") }
            Button(onClick = { viewModel.terminateWebRtcConnection() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)) { Text("STOP") }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.sendLive("stream_cam_front", "start") }, modifier = Modifier.weight(1f)) { Text("CAM F") }
            Button(onClick = { viewModel.sendLive("stream_cam_back", "start") }, modifier = Modifier.weight(1f)) { Text("CAM B") }
            Button(onClick = { viewModel.sendLive("live_audio_mode", "mic") }, modifier = Modifier.weight(1f)) { Text("MIC") }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
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
                Text(log, color = color, fontSize = 11.sp, fontFamily = CyberFont, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

fun getIconByName(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when(name) {
        "flash", "flashlight" -> Icons.Rounded.FlashlightOn
        "screen", "light_mode" -> Icons.Rounded.LightMode
        "loc", "location", "location_on" -> Icons.Rounded.LocationOn
        "wifi" -> Icons.Rounded.Wifi
        "bluetooth" -> Icons.Rounded.Bluetooth
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
        else -> Icons.Rounded.Code
    }
}