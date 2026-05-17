package com.c2c

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.c2c.ui.theme.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.*
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

data class PushMessage(val id: Int, val label: String, val cmd: String, var defaultArg: String = "")

object ServerCore {
    var ktorServer: ApplicationEngine? = null
    val liveSessions = CopyOnWriteArrayList<WebSocketServerSession>()
    val logsFlow = MutableSharedFlow<String>(replay = 50)
    val streamFlow = MutableSharedFlow<ByteArray>(replay = 5)
    var isRunning = false
    var lastStatusSlab = "System Ready. Waiting for link..."

    fun log(msg: String, isSuccess: Boolean? = null) {
        logsFlow.tryEmit(msg)
        if (isSuccess == true) lastStatusSlab = "SUCCESS: $msg"
        else if (isSuccess == false) lastStatusSlab = "ERROR: $msg"
    }
}

class C2ServerService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("c2c_server", "Core Node Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 8765) ?: 8765
        val notification = NotificationCompat.Builder(this, "c2c_server")
            .setContentTitle("Core Node Active")
            .setContentText("Listening on port $port")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(1, notification)
        startKtor(port)
        return START_STICKY
    }
    private fun startKtor(port: Int) {
        if (ServerCore.isRunning) return
        try {
            ServerCore.ktorServer = embeddedServer(io.ktor.server.cio.CIO, port = port, host = "0.0.0.0") {
                install(WebSockets)
                routing {
                    webSocket("/live") {
                        ServerCore.liveSessions.add(this)
                        ServerCore.log("LINK ESTABLISHED: ${call.request.local.remoteHost}", true)
                        try {
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Text -> ServerCore.log("RECV: ${frame.readText()}")
                                    is Frame.Binary -> ServerCore.streamFlow.emit(frame.readBytes())
                                    else -> {}
                                }
                            }
                        } finally {
                            ServerCore.liveSessions.remove(this)
                            ServerCore.log("LINK SEVERED", false)
                        }
                    }
                }
            }.start(wait = false)
            ServerCore.isRunning = true
            ServerCore.log("NODE STARTED ON 0.0.0.0:$port", true)
        } catch (e: Exception) { ServerCore.log("NODE CRASH: ${e.message}", false) }
    }
    override fun onDestroy() {
        ServerCore.ktorServer?.stop(1000, 2000)
        ServerCore.isRunning = false
        ServerCore.liveSessions.clear()
        ServerCore.log("NODE TERMINATED.", false)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

class CoreViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("CoreConfig", Context.MODE_PRIVATE)
    
    // Core State
    var pushMessages = mutableStateListOf<PushMessage>()
    var serverLogs = mutableStateListOf<String>()
    var latestSlabTxt by mutableStateOf(ServerCore.lastStatusSlab)
    
    var isMicRunning by mutableStateOf(false)
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    var liveScreenFrame by mutableStateOf<ByteArray?>(null)
    var showScanResultsModal by mutableStateOf(false)
    var scanResults by mutableStateOf<List<Pair<String, String>>>(emptyList())

    val httpClient = HttpClient(CIO) { engine { requestTimeout = 65_000 } }
    private var tgPollingJob: Job? = null

    init {
        loadConfigFromJson()
        
        viewModelScope.launch(Dispatchers.Main) {
            ServerCore.logsFlow.collect { log ->
                if (serverLogs.size > 150) serverLogs.removeAt(0)
                serverLogs.add(log)
                latestSlabTxt = ServerCore.lastStatusSlab
                
                if (log.contains("\"type\":\"scan_results\"")) {
                    try {
                        val data = JSONObject(log.substringAfter("RECV: "))
                        val resultsArray = data.getJSONArray("data")
                        val parsed = mutableListOf<Pair<String, String>>()
                        for (i in 0 until resultsArray.length()) {
                            val item = resultsArray.getJSONObject(i)
                            parsed.add(Pair(item.getString("name"), item.getString("details")))
                        }
                        scanResults = parsed; showScanResultsModal = true
                    } catch(e: Exception){}
                }
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            ServerCore.streamFlow.collect { bytes -> if (bytes.firstOrNull()?.toInt() == 0x03) liveScreenFrame = bytes.copyOfRange(1, bytes.size) }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            for (chunk in audioChannel) {
                ServerCore.liveSessions.forEach { try { it.send(Frame.Binary(true, chunk)) } catch (e: Exception) {} }
            }
        }
        
        startHeadlessTelegramPolling()
    }

    // --- The Unified JSON Config Engine ---
    
    fun exportConfigToJson(): String {
        val root = JSONObject()
        root.put("network", JSONObject().put("ntfyUrl", prefs.getString("ntfyUrl", "https://ntfy.sh")).put("ntfyTopic", prefs.getString("ntfyTopic", "default_topic")))
        root.put("server", JSONObject().put("port", prefs.getInt("port", 8765)))
        root.put("telegram", JSONObject().put("token", prefs.getString("tgToken", "")).put("chatId", prefs.getString("tgChatId", "")))
        
        val cmds = JSONArray()
        pushMessages.forEach { cmds.put(JSONObject().put("id", it.id).put("label", it.label).put("cmd", it.cmd).put("arg", it.defaultArg)) }
        root.put("commands", cmds)
        
        return root.toString(4)
    }

    fun importConfigFromJson(jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            val net = root.getJSONObject("network")
            val srv = root.getJSONObject("server")
            val tg = root.getJSONObject("telegram")
            
            prefs.edit()
                .putString("ntfyUrl", net.getString("ntfyUrl"))
                .putString("ntfyTopic", net.getString("ntfyTopic"))
                .putInt("port", srv.getInt("port"))
                .putString("tgToken", tg.getString("token"))
                .putString("tgChatId", tg.getString("chatId"))
                .putString("raw_commands", root.getJSONArray("commands").toString())
                .apply()
                
            loadConfigFromJson() // Refresh UI state
            startHeadlessTelegramPolling() // Restart polling with new token
            true
        } catch (e: Exception) { false }
    }

    private fun loadConfigFromJson() {
        pushMessages.clear()
        val rawCmds = prefs.getString("raw_commands", null)
        if (rawCmds != null) {
            try {
                val arr = JSONArray(rawCmds)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    pushMessages.add(PushMessage(obj.getInt("id"), obj.getString("label"), obj.getString("cmd"), obj.optString("arg", "")))
                }
            } catch (e: Exception) {}
        } else {
            // Default loadout
            pushMessages.add(PushMessage(1, "Wake Device", "live_start", ""))
            pushMessages.add(PushMessage(2, "Toggle WiFi", "wifi_toggle", ""))
            pushMessages.add(PushMessage(3, "Scan Environment", "wifi_scan", ""))
            exportConfigToJson().let { importConfigFromJson(it) } // Save defaults
        }
    }

    fun updateCommandArg(id: Int, newArg: String) {
        val idx = pushMessages.indexOfFirst { it.id == id }
        if (idx != -1) {
            pushMessages[idx] = pushMessages[idx].copy(defaultArg = newArg)
            val json = exportConfigToJson()
            prefs.edit().putString("raw_commands", JSONObject(json).getJSONArray("commands").toString()).apply()
        }
    }

    // --- Execution Logic ---

    fun executePush(cmd: String, arg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = prefs.getString("ntfyUrl", "https://ntfy.sh")!!
                val topic = prefs.getString("ntfyTopic", "default_topic")!!
                val targetUrl = if (url.startsWith("http")) "${url.trimEnd('/')}/$topic" else "https://$url/${topic.trimEnd('/')}"
                
                val safeCmd = cmd.replace("\"", "\\\"")
                val payload = if (arg.isNotBlank()) """{"cmd": "$safeCmd", "arg": "${arg.replace("\"", "\\\"")}"}""" else """{"cmd": "$safeCmd"}"""
                
                httpClient.post(targetUrl) { setBody(payload) }
                ServerCore.log("PUSH EXECUTED: $cmd", true)
            } catch (e: Exception) { ServerCore.log("PUSH FAILED: ${e.message}", false) }
        }
    }

    fun sendLive(cmd: String, arg: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            if (ServerCore.liveSessions.isNotEmpty()) {
                val json = if(arg.isNotBlank()) """{"cmd":"$cmd","arg":"$arg"}""" else """{"cmd":"$cmd"}"""
                ServerCore.liveSessions.forEach { try { it.send(Frame.Text(json)) } catch(e: Exception){} }
                ServerCore.log("CMD SENT: $cmd", true)
            } else ServerCore.log("Link Offline", false)
        }
    }

    fun toggleServer(context: Context) {
        val port = prefs.getInt("port", 8765)
        val intent = Intent(context, C2ServerService::class.java).apply { putExtra("port", port) }
        if (ServerCore.isRunning) { context.stopService(intent) } 
        else { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) }
    }

    // --- Hardware Integrations ---

    @SuppressLint("MissingPermission")
    fun startMic() {
        if (isMicRunning) return
        isMicRunning = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) { isMicRunning = false; return@launch }
                audioRecord.startRecording()
                val buffer = ByteArray(bufferSize)
                while (isMicRunning && isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) audioChannel.trySend(buffer.copyOfRange(0, read))
                }
                audioRecord.stop(); audioRecord.release()
            } catch (e: Exception) { isMicRunning = false }
        }
    }
    fun stopMic() { isMicRunning = false }

    // Headless Telegram Listener (Logs incoming TG messages to terminal)
    private fun startHeadlessTelegramPolling() {
        tgPollingJob?.cancel()
        val token = prefs.getString("tgToken", "") ?: ""
        if (token.isBlank()) return
        
        var tgOffset = 0L
        tgPollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val res = JSONObject(httpClient.get("https://api.telegram.org/bot$token/getUpdates?offset=$tgOffset&timeout=15").bodyAsText())
                    if (res.getBoolean("ok")) {
                        val result = res.getJSONArray("result")
                        for (i in 0 until result.length()) {
                            val update = result.getJSONObject(i)
                            tgOffset = update.getLong("update_id") + 1
                            val msg = update.optJSONObject("message")?.optString("text", "")
                            if (!msg.isNullOrBlank()) ServerCore.log("TG_INCOMING: $msg", true)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { delay(5000) }
                delay(3000)
            }
        }
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PremiumTheme {
                AppNavigation(this)
            }
        }
    }
    
    fun promptAuth(onSuccess: () -> Unit) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("System Authentication").setSubtitle("Identity required").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { onSuccess() }
        }).authenticate(promptInfo)
    }

    fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${packageName}") })
        }
    }
}

// Reusable Glass UI Components
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.border(1.dp, GlassBorder, PremiumShapes.medium),
        colors = CardDefaults.cardColors(containerColor = GlassSurface),
        shape = PremiumShapes.medium
    ) { content() }
}

@Composable
fun GlassButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, color: Color = TextPrimary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = GlassSurface, contentColor = color),
        shape = PremiumShapes.small,
        border = BorderStroke(1.dp, GlassBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        if(icon != null) { Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AppNavigation(activity: MainActivity) {
    val navController = rememberNavController()
    val viewModel: CoreViewModel = viewModel()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { activity.requestPermissions() }
    
    NavHost(navController = navController, startDestination = "login") {
        composable("login") { 
            LoginScreen({ 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                else permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                navController.navigate("main") { popUpTo("login") { inclusive = true } } 
            }, activity) 
        }
        composable("main") { MainScreen(viewModel) }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, activity: MainActivity) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Fingerprint, "Auth", tint = ActionBlue, modifier = Modifier.size(80.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("CORE SYSTEM", fontSize = 28.sp, fontWeight = FontWeight.Light, color = TextPrimary, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(48.dp))
        GlassButton("AUTHENTICATE", Icons.Rounded.LockOpen, ActionBlue, Modifier.height(56.dp)) { activity.promptAuth(onLoginSuccess) }
    }
}

@Composable
fun MainScreen(viewModel: CoreViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0x990A0A0F), modifier = Modifier.border(1.dp, GlassBorder)) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Rounded.GridView, "Commands") }, label = { Text("COMMANDS") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = SoulBackground, selectedTextColor = ActionBlue, indicatorColor = ActionBlue, unselectedIconColor = TextSecondary))
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Rounded.Dns, "Server") }, label = { Text("SERVER") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = SoulBackground, selectedTextColor = PremiumTeal, indicatorColor = PremiumTeal, unselectedIconColor = TextSecondary))
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Rounded.SettingsEthernet, "Config") }, label = { Text("CONFIG") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = SoulBackground, selectedTextColor = PremiumRose, indicatorColor = PremiumRose, unselectedIconColor = TextSecondary))
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> CommandsTab(viewModel)
                1 -> ServerTab(viewModel)
                2 -> JsonSetupTab(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandsTab(viewModel: CoreViewModel) {
    val context = LocalContext.current
    var volLevel by remember { mutableFloatStateOf(50f) }
    var argPromptCommand by remember { mutableStateOf<PushMessage?>(null) }
    var runtimeArg by remember { mutableStateOf("") }

    if (argPromptCommand != null) {
        AlertDialog(
            onDismissRequest = { argPromptCommand = null },
            containerColor = Color(0xFF1E293B),
            title = { Text("Set Argument", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = runtimeArg, 
                    onValueChange = { runtimeArg = it },
                    label = { Text("Argument for ${argPromptCommand?.label}") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextSecondary)
                )
            },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.updateCommandArg(argPromptCommand!!.id, runtimeArg)
                    argPromptCommand = null; runtimeArg = "" 
                }) { Text("SAVE AS DEFAULT", color = ActionBlue) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.executePush(argPromptCommand!!.cmd, runtimeArg)
                    argPromptCommand = null; runtimeArg = "" 
                }) { Text("EXECUTE ONCE", color = PremiumTeal) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Control Matrix", fontSize = 24.sp, fontWeight = FontWeight.Light, color = TextPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { viewModel.executePush("flash", "toggle") }) { Icon(Icons.Rounded.FlashlightOn, null, tint = PremiumTeal) }
                    IconButton(onClick = { viewModel.executePush("screen", "on") }) { Icon(Icons.Rounded.LightMode, null, tint = ActionBlue) }
                    IconButton(onClick = { viewModel.executePush("screen", "off") }) { Icon(Icons.Rounded.DarkMode, null, tint = PremiumPurple) }
                    IconButton(onClick = { viewModel.executePush("loc", "") }) { Icon(Icons.Rounded.LocationOn, null, tint = PremiumRose) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VolumeUp, null, tint = TextSecondary)
                    Slider(value = volLevel, onValueChange = { volLevel = it }, valueRange = 0f..100f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = ActionBlue, activeTrackColor = ActionBlue))
                    IconButton(onClick = { viewModel.executePush("vol", volLevel.toInt().toString()) }) { Icon(Icons.Rounded.Send, null, tint = ActionBlue) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                GlassButton("File Explorer", Icons.Rounded.FolderOpen, ActionBlue) { context.startActivity(Intent(context, FileManagerActivity::class.java)) }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Directives", fontSize = 16.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(viewModel.pushMessages) { msg ->
                Card(
                    shape = PremiumShapes.medium,
                    border = BorderStroke(1.dp, GlassBorder),
                    colors = CardDefaults.cardColors(containerColor = GlassSurface),
                    modifier = Modifier.aspectRatio(1f).combinedClickable(
                        onClick = { viewModel.executePush(msg.cmd, msg.defaultArg) },
                        onDoubleClick = { 
                            runtimeArg = msg.defaultArg
                            argPromptCommand = msg 
                        }
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Code, null, tint = TextPrimary, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(msg.label, color = TextPrimary, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                            if (msg.defaultArg.isNotBlank()) {
                                Text("(${msg.defaultArg})", color = PremiumTeal, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerTab(viewModel: CoreViewModel) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 2 })

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        if (page == 0) TerminalPage(viewModel, context) else LiveControlPage(viewModel, context)
    }
}

@Composable
fun TerminalPage(viewModel: CoreViewModel, context: Context) {
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.serverLogs.size) { if(viewModel.serverLogs.isNotEmpty()) listState.animateScrollToItem(viewModel.serverLogs.size - 1) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Node Connection", fontSize = 24.sp, fontWeight = FontWeight.Light, color = TextPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        
        GlassButton(
            text = if (ServerCore.isRunning) "TERMINATE NODE" else "INITIALIZE NODE",
            icon = Icons.Rounded.PowerSettingsNew,
            color = if (ServerCore.isRunning) PremiumRose else ActionBlue,
            modifier = Modifier.height(56.dp)
        ) { viewModel.toggleServer(context) }
        
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("System Event Log", color = TextSecondary, fontSize = 14.sp)
            IconButton(onClick = { viewModel.serverLogs.clear() }) { Icon(Icons.Rounded.DeleteOutline, null, tint = TextSecondary) }
        }
        
        GlassCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) {
                items(viewModel.serverLogs) { log ->
                    val color = if (log.contains("ERROR") || log.contains("FAIL") || log.contains("SEVERED")) PremiumRose 
                                else if (log.contains("SUCCESS") || log.contains("ESTABLISHED")) PremiumTeal 
                                else TextPrimary
                    Text(log, color = color, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
fun LiveControlPage(viewModel: CoreViewModel, context: Context) {
    var showAudioModal by remember { mutableStateOf(false) }
    var showScreenCast by remember { mutableStateOf(false) }
    if (showAudioModal) AudioControlModal(viewModel, context) { showAudioModal = false }
    if (showScreenCast) ScreenCastOverlay(viewModel) { showScreenCast = false }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Live Controls", fontSize = 24.sp, fontWeight = FontWeight.Light, color = TextPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(viewModel.latestSlabTxt, color = PremiumTeal, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
        Spacer(modifier = Modifier.height(24.dp))

        Text("Media Streams", fontSize = 14.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { GlassButton("Audio Hub", Icons.Rounded.Mic, PremiumTeal) { showAudioModal = true } }
            item { GlassButton("Screen Cast", Icons.Rounded.Cast, ActionBlue) { showScreenCast = true; viewModel.sendLive("live_screen_cast", "start") } }
            item { GlassButton("Cam Front", Icons.Rounded.CameraFront) { viewModel.sendLive("stream_cam_front", "start") } }
            item { GlassButton("Cam Back", Icons.Rounded.CameraRear) { viewModel.sendLive("stream_cam_back", "start") } }
        }
        Spacer(modifier = Modifier.height(24.dp))

        Text("Connectivity", fontSize = 14.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("WiFi", null, TextPrimary, Modifier.weight(1f)) { viewModel.sendLive("wifi_toggle") }
            GlassButton("Bluetooth", null, TextPrimary, Modifier.weight(1f)) { viewModel.sendLive("bt_toggle") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Scan WiFi", null, PremiumTeal, Modifier.weight(1f)) { viewModel.sendLive("wifi_scan") }
            GlassButton("Scan BT", null, PremiumTeal, Modifier.weight(1f)) { viewModel.sendLive("bt_scan") }
        }
    }
}

@Composable
fun AudioControlModal(viewModel: CoreViewModel, context: Context, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Audio Control Matrix", fontSize = 18.sp, color = ActionBlue)
                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    GlassButton("FULL CALL", null, PremiumTeal, Modifier.weight(1f)) { viewModel.sendLive("live_audio_mode", "call"); viewModel.startMic() }
                    Spacer(modifier = Modifier.width(8.dp))
                    GlassButton("LISTEN ONLY", null, PremiumTeal, Modifier.weight(1f)) { viewModel.sendLive("live_audio_mode", "mic") }
                }
                Spacer(modifier = Modifier.height(16.dp))
                GlassButton("TERMINATE AUDIO", Icons.Rounded.Close, PremiumRose) { viewModel.sendLive("live_audio_mode", "off"); viewModel.stopMic(); onDismiss() }
            }
        }
    }
}

@Composable
fun ScreenCastOverlay(viewModel: CoreViewModel, onDismiss: () -> Unit) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = { viewModel.sendLive("live_screen_cast", "stop"); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(modifier = Modifier.fillMaxSize()) {
            GlassCard(modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }.size(280.dp, 500.dp).pointerInput(Unit) { detectDragGestures { change, dragAmount -> change.consume(); offsetX += dragAmount.x; offsetY += dragAmount.y } }) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (viewModel.liveScreenFrame == null) CircularProgressIndicator(color = ActionBlue, modifier = Modifier.align(Alignment.Center))
                    else {
                        val bmp = BitmapFactory.decodeByteArray(viewModel.liveScreenFrame, 0, viewModel.liveScreenFrame!!.size)
                        if (bmp != null) Image(bitmap = bmp.asImageBitmap(), contentDescription = "Cast", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    IconButton(onClick = { viewModel.sendLive("live_screen_cast", "stop"); onDismiss() }, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Rounded.Close, null, tint = PremiumRose) }
                }
            }
        }
    }
}

@Composable
fun JsonSetupTab(viewModel: CoreViewModel) {
    val context = LocalContext.current
    var jsonText by remember { mutableStateOf(viewModel.exportConfigToJson()) }
    
    // Automatically refresh if user alters it elsewhere
    LaunchedEffect(viewModel.pushMessages.size) { jsonText = viewModel.exportConfigToJson() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Master Configuration", fontSize = 24.sp, fontWeight = FontWeight.Light, color = TextPrimary)
        Text("Edit RAW JSON. Malformed JSON will be rejected.", color = TextSecondary, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = jsonText,
            onValueChange = { jsonText = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = PremiumTeal, fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GlassBorder, unfocusedBorderColor = GlassBorder,
                focusedContainerColor = Color(0x33000000), unfocusedContainerColor = Color(0x33000000)
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        GlassButton("APPLY CONFIGURATION", Icons.Rounded.Save, SuccessGreen, Modifier.height(56.dp)) {
            if (viewModel.importConfigFromJson(jsonText)) {
                Toast.makeText(context, "Config Applied Successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Invalid JSON Format", Toast.LENGTH_SHORT).show()
            }
        }
    }
}