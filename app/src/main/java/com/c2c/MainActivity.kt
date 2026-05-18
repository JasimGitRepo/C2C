--- START OF FILE app/src/main/java/com/c2c/MainActivity.kt ---
package com.c2c

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

data class PushMessage(val id: Int, val label: String, val cmd: String, var defaultArg: String = "", val icon: String = "", val isToggle: Boolean = false, val toggledLabel: String = "", val toggledCmd: String = "", val toggledArg: String = "")
data class TgMessage(val id: Long, val text: String, val isMe: Boolean, val timestamp: Long)
enum class TdAuthState { INIT, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY, CLOSED }

object ServerCore {
    var ktorServer: ApplicationEngine? = null
    val liveSessions = CopyOnWriteArrayList<WebSocketServerSession>()
    val logsFlow = MutableSharedFlow<String>(replay = 50)
    val streamFlow = MutableSharedFlow<ByteArray>(replay = 5)
    
    val isRunningFlow = MutableStateFlow(false)
    var isRunning: Boolean get() = isRunningFlow.value; set(value) { isRunningFlow.value = value }
    var lastStatusSlab = "System Ready. Waiting for link..."

    fun log(msg: String, isSuccess: Boolean? = null) {
        logsFlow.tryEmit(msg)
        if (isSuccess == true) lastStatusSlab = "SUCCESS: $msg"
        else if (isSuccess == false) lastStatusSlab = "ERROR: $msg"
    }
}

class C2ServerService : Service() {
    companion object { const val ACTION_STOP = "STOP_NODE" }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("c2c_server", "Core Node Service", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopKtor(); stopSelf(); return START_NOT_STICKY }
        val port = intent?.getIntExtra("port", 8765) ?: 8765
        val stopIntent = Intent(this, C2ServerService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "c2c_server").setContentTitle("C2C Node Active").setContentText("Listening on port $port").setSmallIcon(android.R.drawable.ic_dialog_info).addAction(android.R.drawable.ic_menu_close_clear_cancel, "TERMINATE NODE", stopPI).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, notification)
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
                        ServerCore.liveSessions.add(this); ServerCore.log("LINK ESTABLISHED: ${call.request.local.remoteHost}", true)
                        try { for (frame in incoming) { if (frame is Frame.Binary) ServerCore.streamFlow.emit(frame.readBytes()) else if (frame is Frame.Text) ServerCore.log("RECV: ${frame.readText()}")} } 
                        finally { ServerCore.liveSessions.remove(this); ServerCore.log("LINK SEVERED", false) }
                    }
                }
            }.start(wait = false)
            ServerCore.isRunning = true; ServerCore.log("NODE STARTED ON 0.0.0.0:$port", true)
        } catch (e: Exception) { ServerCore.log("NODE CRASH: ${e.message}", false) }
    }
    private fun stopKtor() { ServerCore.ktorServer?.stop(1000, 2000); ServerCore.isRunning = false; ServerCore.liveSessions.clear(); ServerCore.log("NODE TERMINATED.", false) }
    override fun onDestroy() { stopKtor(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

class CoreViewModel(application: Application) : AndroidViewModel(application) {
    private val appCtx = application
    private val prefs = application.getSharedPreferences("CoreConfig", Context.MODE_PRIVATE)
    
    var topPanelCommands = mutableStateListOf<PushMessage>()
    var pushMessages = mutableStateListOf<PushMessage>()
    var serverLogs = mutableStateListOf<String>()
    var latestSlabTxt by mutableStateOf(ServerCore.lastStatusSlab)
    
    // Persistent Local Chat Engine
    var tgMessages = mutableStateListOf<TgMessage>()
    var tgChatName by mutableStateOf("Target Node")
    var tgChatId by mutableStateOf(0L)
    
    var isMicRunning by mutableStateOf(false)
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    var liveScreenFrame by mutableStateOf<ByteArray?>(null)

    val httpClient = HttpClient(CIO) { engine { requestTimeout = 65_000 } }
    
    // Hardware Managers
    private val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // TDLib State
    var tdClient: Client? = null
    var tdAuthState by mutableStateOf(TdAuthState.CLOSED)
    var isTdLibEngineRunning by mutableStateOf(false)

    init {
        loadConfigFromJson()
        loadLocalChatHistory()
        viewModelScope.launch(Dispatchers.Main) { ServerCore.logsFlow.collect { log -> if (serverLogs.size > 150) serverLogs.removeAt(0); serverLogs.add(log); latestSlabTxt = ServerCore.lastStatusSlab } }
        viewModelScope.launch(Dispatchers.IO) { ServerCore.streamFlow.collect { bytes -> if (bytes.firstOrNull()?.toInt() == 0x03) liveScreenFrame = bytes.copyOfRange(1, bytes.size) } }
        viewModelScope.launch(Dispatchers.IO) { for (chunk in audioChannel) { ServerCore.liveSessions.forEach { try { it.send(Frame.Binary(true, chunk)) } catch (e: Exception) {} } } }
    }

    // --- TDLib Engine ---
    fun toggleTdLib() {
        if (isTdLibEngineRunning) {
            tdClient?.send(TdApi.Close()) { tdAuthState = TdAuthState.CLOSED; isTdLibEngineRunning = false }
            tdClient = null
        } else {
            isTdLibEngineRunning = true
            tdAuthState = TdAuthState.INIT
            tgMessages.clear()
            
            // FIXED: Using .apply for robust constructor
            val verbosity = TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = 0 }
            Client.execute(verbosity)
            
            // FIXED: 1-argument Exception Handlers matched
            tdClient = Client.create({ update -> handleTdUpdate(update) }, { _ -> }, { _ -> })
        }
    }

    private fun handleTdUpdate(update: TdApi.Object) {
        viewModelScope.launch(Dispatchers.Main) {
            when (update) {
                is TdApi.UpdateAuthorizationState -> {
                    when (update.authorizationState) {
                        is TdApi.AuthorizationStateWaitTdlibParameters -> {
                            val params = TdApi.SetTdlibParameters().apply {
                                useTestDc = false
                                databaseDirectory = File(appCtx.filesDir, "tdlib").absolutePath
                                useMessageDatabase = true
                                useSecretChats = true
                                apiId = prefs.getInt("tdApiId", 0)
                                apiHash = prefs.getString("tdApiHash", "")
                                systemLanguageCode = "en"
                                deviceModel = Build.MODEL
                                applicationVersion = "1.0"
                            }
                            tdClient?.send(params) { }
                        }
                        is TdApi.AuthorizationStateWaitPhoneNumber -> tdAuthState = TdAuthState.WAIT_PHONE
                        is TdApi.AuthorizationStateWaitCode -> tdAuthState = TdAuthState.WAIT_CODE
                        is TdApi.AuthorizationStateWaitPassword -> tdAuthState = TdAuthState.WAIT_PASSWORD
                        is TdApi.AuthorizationStateReady -> {
                            tdAuthState = TdAuthState.READY
                            tgChatId = prefs.getLong("tdTargetChatId", 0L)
                            if (tgChatId != 0L) fetchChatHistory()
                        }
                        is TdApi.AuthorizationStateClosed -> { tdAuthState = TdAuthState.CLOSED; isTdLibEngineRunning = false }
                    }
                }
                is TdApi.UpdateNewMessage -> {
                    val msg = update.message
                    if (msg.chatId == tgChatId) {
                        val content = msg.content
                        if (content is TdApi.MessageText) {
                            tgMessages.add(TgMessage(msg.id, content.text.text, msg.isOutgoing, msg.date * 1000L))
                            tgMessages.sortBy { it.timestamp }
                            saveLocalChatHistory()
                            if (!msg.isOutgoing) executeHardwareCommand(content.text.text)
                        }
                    }
                }
            }
        }
    }

    // FIXED: Using .apply for all parameters to survive API changes
    fun tdSendPhone(phone: String) = tdClient?.send(TdApi.SetAuthenticationPhoneNumber().apply { phoneNumber = phone }) { }
    fun tdSendCode(code: String) = tdClient?.send(TdApi.CheckAuthenticationCode().apply { this.code = code }) { }
    fun tdSendPassword(pass: String) = tdClient?.send(TdApi.CheckAuthenticationPassword().apply { password = pass }) { }

    private fun fetchChatHistory() {
        val chatReq = TdApi.GetChat().apply { chatId = tgChatId }
        tdClient?.send(chatReq) { chat -> if (chat is TdApi.Chat) viewModelScope.launch(Dispatchers.Main) { tgChatName = chat.title } }
        
        val historyReq = TdApi.GetChatHistory().apply {
            chatId = tgChatId
            limit = 50
        }
        tdClient?.send(historyReq) { res ->
            if (res is TdApi.Messages) {
                viewModelScope.launch(Dispatchers.Main) {
                    val newMsgs = res.messages.filter { it.content is TdApi.MessageText }.map { msg ->
                        TgMessage(msg.id, (msg.content as TdApi.MessageText).text.text, msg.isOutgoing, msg.date * 1000L)
                    }
                    tgMessages.clear()
                    tgMessages.addAll(newMsgs.reversed())
                    saveLocalChatHistory()
                }
            }
        }
    }

    fun sendTelegramMessage(text: String, isMe: Boolean = true) {
        if (tgChatId == 0L || tdClient == null) return
        
        // Add to Local UI instantly
        viewModelScope.launch(Dispatchers.Main) {
            tgMessages.add(TgMessage(System.currentTimeMillis(), text, isMe, System.currentTimeMillis()))
            saveLocalChatHistory()
        }

        // FIXED: Using .apply for nested message contents
        if (isMe) {
            viewModelScope.launch(Dispatchers.IO) {
                val formattedText = TdApi.FormattedText().apply {
                    this.text = text
                    this.entities = emptyArray()
                }
                val inputContent = TdApi.InputMessageText().apply {
                    this.text = formattedText
                }
                val request = TdApi.SendMessage().apply {
                    this.chatId = tgChatId
                    this.inputMessageContent = inputContent
                }
                tdClient?.send(request) { }
            }
        }
    }

    // --- Persistent Chat Methods ---
    private fun loadLocalChatHistory() {
        val historyStr = prefs.getString("tg_history", "[]")
        try {
            val arr = JSONArray(historyStr)
            tgMessages.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                tgMessages.add(TgMessage(obj.getLong("id"), obj.getString("text"), obj.getBoolean("isMe"), obj.getLong("timestamp")))
            }
        } catch (e: Exception) {}
    }

    private fun saveLocalChatHistory() {
        val arr = JSONArray()
        tgMessages.forEach { msg -> arr.put(JSONObject().put("id", msg.id).put("text", msg.text).put("isMe", msg.isMe).put("timestamp", msg.timestamp)) }
        prefs.edit().putString("tg_history", arr.toString()).apply()
    }

    // --- Hardware Command Parser ---
    @SuppressLint("MissingPermission")
    private fun executeHardwareCommand(cmdString: String) {
        val parts = cmdString.split(" ")
        val cmd = parts[0].lowercase().removePrefix("/")
        val arg = if (parts.size > 1) parts[1] else ""
        viewModelScope.launch(Dispatchers.IO) {
            when (cmd) {
                "ping" -> {
                    val batteryStatus = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    sendTelegramMessage("🟢 Node Online.\n🔋 Battery: $level%")
                }
                "loc" -> {
                    sendTelegramMessage("🛰️ Fetching GPS Coordinates...")
                    try {
                        val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (loc != null) sendTelegramMessage("📍 <b>Location Acquired:</b>\nLat: ${loc.latitude}\nLng: ${loc.longitude}\nAccuracy: ${loc.accuracy}m")
                        else sendTelegramMessage("❌ Location unavailable.")
                    } catch (e: Exception) { sendTelegramMessage("❌ GPS Error: ${e.message}") }
                }
                "flash" -> {
                    try {
                        val camId = cameraManager.cameraIdList[0]
                        val state = arg.lowercase() == "on"
                        cameraManager.setTorchMode(camId, state)
                        sendTelegramMessage(if (state) "🔦 Flashlight Enabled" else "🔦 Flashlight Disabled")
                    } catch (e: Exception) { sendTelegramMessage("❌ Camera Hardware Error.") }
                }
                "vol" -> {
                    val targetVol = arg.toIntOrNull() ?: 50
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (targetVol * max) / 100, 0)
                    sendTelegramMessage("🔊 Media volume set to $targetVol%")
                }
                "live_start" -> {
                    sendTelegramMessage("⚡ Initiating Live WebSocket Connection...")
                    toggleServer(appCtx)
                }
                "live_stop" -> {
                    ServerCore.ktorServer?.stop(1000, 2000)
                    ServerCore.isRunning = false
                    sendTelegramMessage("🛑 Core Node Terminated.")
                }
            }
        }
    }

    // --- Standard JSON & Ktor Configs below ---
    fun exportConfigToJson(): String {
        val root = JSONObject()
        root.put("network", JSONObject().put("ntfyUrl", prefs.getString("ntfyUrl", "https://ntfy.sh")).put("ntfyTopic", prefs.getString("ntfyTopic", "default_topic")))
        root.put("server", JSONObject().put("port", prefs.getInt("port", 8765)))
        root.put("tdlib", JSONObject().put("apiId", prefs.getInt("tdApiId", 0)).put("apiHash", prefs.getString("tdApiHash", "")).put("targetChatId", prefs.getLong("tdTargetChatId", 0L)))
        val tp = JSONArray(); topPanelCommands.forEach { tp.put(cmdToJson(it)) }; root.put("top_panel", tp)
        val cmds = JSONArray(); pushMessages.forEach { cmds.put(cmdToJson(it)) }; root.put("commands", cmds)
        return root.toString(4)
    }

    private fun cmdToJson(it: PushMessage) = JSONObject().apply {
        put("id", it.id); put("label", it.label); put("cmd", it.cmd); put("arg", it.defaultArg); put("icon", it.icon)
        put("isToggle", it.isToggle); put("toggledLabel", it.toggledLabel); put("toggledCmd", it.toggledCmd); put("toggledArg", it.toggledArg)
    }

    fun importConfigFromJson(jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            val net = root.getJSONObject("network"); val srv = root.getJSONObject("server"); val td = root.getJSONObject("tdlib")
            prefs.edit().putString("ntfyUrl", net.getString("ntfyUrl")).putString("ntfyTopic", net.getString("ntfyTopic")).putInt("port", srv.getInt("port"))
                .putInt("tdApiId", td.getInt("apiId")).putString("tdApiHash", td.getString("apiHash")).putLong("tdTargetChatId", td.getLong("targetChatId"))
                .putString("raw_top_panel", root.getJSONArray("top_panel").toString()).putString("raw_commands", root.getJSONArray("commands").toString()).apply()
            loadConfigFromJson()
            true
        } catch (e: Exception) { false }
    }

    private fun loadConfigFromJson() {
        topPanelCommands.clear(); pushMessages.clear()
        val rawTp = prefs.getString("raw_top_panel", null); val rawCmds = prefs.getString("raw_commands", null)
        
        if (rawTp != null && rawCmds != null) {
            try {
                val tpArr = JSONArray(rawTp); for (i in 0 until tpArr.length()) { topPanelCommands.add(jsonToCmd(tpArr.getJSONObject(i))) }
                val cmdArr = JSONArray(rawCmds); for (i in 0 until cmdArr.length()) { pushMessages.add(jsonToCmd(cmdArr.getJSONObject(i))) }
            } catch (e: Exception) {}
        } else {
            topPanelCommands.add(PushMessage(1, "Flash", "flash", "on", "flashlight", true, "Flash Off", "flash", "off"))
            topPanelCommands.add(PushMessage(2, "Ping Node", "ping", "", "radar"))
            topPanelCommands.add(PushMessage(3, "Location", "loc", "", "location_on"))
            topPanelCommands.add(PushMessage(4, "Volume", "vol", "100", "volume_up", true, "Mute", "vol", "0"))
            
            pushMessages.add(PushMessage(10, "Wake/Live Node", "live_start", "ws://0.0.0.0:8765/live", "router"))
            pushMessages.add(PushMessage(11, "Kill Node", "live_stop", "", "power_settings_new"))
            pushMessages.add(PushMessage(12, "Cam Front", "cam_front", "", "camera_front"))
            pushMessages.add(PushMessage(13, "Cam Back", "cam_back", "", "camera_rear"))
            pushMessages.add(PushMessage(14, "Record Mic", "mic", "15", "mic"))
            pushMessages.add(PushMessage(15, "Full Info", "info", "", "info"))
            pushMessages.add(PushMessage(16, "Fetch Logs", "get_log", "", "description"))
            pushMessages.add(PushMessage(17, "Clear Logs", "clear_log", "", "delete_sweep"))
            
            exportConfigToJson().let { importConfigFromJson(it) }
        }
    }

    private fun jsonToCmd(obj: JSONObject) = PushMessage(obj.getInt("id"), obj.getString("label"), obj.getString("cmd"), obj.optString("arg", ""), obj.optString("icon", ""), obj.optBoolean("isToggle", false), obj.optString("toggledLabel", ""), obj.optString("toggledCmd", ""), obj.optString("toggledArg", ""))
    
    fun updateCommandArg(id: Int, newArg: String) {
        val idx = pushMessages.indexOfFirst { it.id == id }
        if (idx != -1) { pushMessages[idx] = pushMessages[idx].copy(defaultArg = newArg); prefs.edit().putString("raw_commands", JSONArray(pushMessages.map { cmdToJson(it) }).toString()).apply() }
    }

    fun executePush(cmd: String, arg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = prefs.getString("ntfyUrl", "https://ntfy.sh")!!; val topic = prefs.getString("ntfyTopic", "default_topic")!!
                val targetUrl = if (url.startsWith("http")) "${url.trimEnd('/')}/$topic" else "https://$url/${topic.trimEnd('/')}"
                val payload = if (arg.isNotBlank()) """{"cmd": "${cmd.replace("\"", "\\\"")}", "arg": "${arg.replace("\"", "\\\"")}"}""" else """{"cmd": "${cmd.replace("\"", "\\\"")}"}"""
                httpClient.post(targetUrl) { setBody(payload) }; ServerCore.log("PUSH EXECUTED: $cmd", true)
            } catch (e: Exception) { ServerCore.log("PUSH FAILED: ${e.message}", false) }
        }
    }

    fun sendLive(cmd: String, arg: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            if (ServerCore.liveSessions.isNotEmpty()) {
                val json = if(arg.isNotBlank()) """{"cmd":"$cmd","arg":"$arg"}""" else """{"cmd":"$cmd"}"""
                ServerCore.liveSessions.forEach { try { it.send(Frame.Text(json)) } catch(e: Exception){} }
            }
        }
    }

    fun toggleServer(context: Context) {
        val intent = Intent(context, C2ServerService::class.java).apply { putExtra("port", prefs.getInt("port", 8765)) }
        if (ServerCore.isRunning) { context.stopService(intent) } else { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) }
    }

    @SuppressLint("MissingPermission")
    fun startMic() {
        if (isMicRunning) return; isMicRunning = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) { isMicRunning = false; return@launch }
                audioRecord.startRecording(); val buffer = ByteArray(bufferSize)
                while (isMicRunning && isActive) { val read = audioRecord.read(buffer, 0, buffer.size); if (read > 0) audioChannel.trySend(buffer.copyOfRange(0, read)) }
                audioRecord.stop(); audioRecord.release()
            } catch (e: Exception) { isMicRunning = false }
        }
    }
    fun stopMic() { isMicRunning = false }
}

fun getIconByName(name: String): ImageVector {
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

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PremiumTheme { AppNavigation(this) } }
    }
    fun promptAuth(onSuccess: () -> Unit) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("System Authentication").setSubtitle("Identity required").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() { override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { onSuccess() } }).authenticate(promptInfo)
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.border(1.dp, GlassBorder, PremiumShapes.medium), colors = CardDefaults.cardColors(containerColor = GlassSurface), shape = PremiumShapes.medium) { content() }
}

@Composable
fun GlassButton(text: String, icon: ImageVector? = null, color: Color = TextPrimary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = GlassSurface, contentColor = color), shape = PremiumShapes.small, border = BorderStroke(1.dp, GlassBorder), modifier = modifier.fillMaxWidth()) {
        if(icon != null) { Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.Medium, fontFamily = UbuntuFont)
    }
}

@Composable
fun AppNavigation(activity: MainActivity) {
    val navController = rememberNavController(); val viewModel: CoreViewModel = viewModel()
    NavHost(navController = navController, startDestination = "login") {
        composable("login") { LoginScreen({ navController.navigate("main") { popUpTo("login") { inclusive = true } } }, activity) }
        composable("main") { MainScreen(viewModel) }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, activity: MainActivity) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Fingerprint, "Auth", tint = ActionBlue, modifier = Modifier.size(80.dp)); Spacer(modifier = Modifier.height(24.dp))
        Text("CORE SYSTEM", fontSize = 28.sp, fontWeight = FontWeight.Light, color = TextPrimary, letterSpacing = 2.sp, fontFamily = UbuntuFont); Spacer(modifier = Modifier.height(48.dp))
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
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Rounded.GridView, null) }, label = { Text("COMMANDS", fontFamily = UbuntuFont) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = SoulBackground, selectedTextColor = ActionBlue, indicatorColor = ActionBlue, unselectedIconColor = TextSecondary))
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Rounded.Dns, null) }, label = { Text("SERVER", fontFamily = UbuntuFont) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = SoulBackground, selectedTextColor = PremiumTeal, indicatorColor = PremiumTeal, unselectedIconColor = TextSecondary))
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Rounded.SettingsEthernet, null) }, label = { Text("CONFIG", fontFamily = UbuntuFont) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = SoulBackground, selectedTextColor = PremiumRose, indicatorColor = PremiumRose, unselectedIconColor = TextSecondary))
            }
        }
    ) { padding -> Box(modifier = Modifier.padding(padding).fillMaxSize()) { when (selectedTab) { 0 -> CommandsPager(viewModel); 1 -> ServerTab(viewModel); 2 -> JsonSetupTab(viewModel) } } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandsPager(viewModel: CoreViewModel) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        if (page == 0) CommandsTab(viewModel) else TelegramCloneTab(viewModel)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandsTab(viewModel: CoreViewModel) {
    val context = LocalContext.current
    var volLevel by remember { mutableFloatStateOf(50f) }
    var argPromptCommand by remember { mutableStateOf<PushMessage?>(null) }; var runtimeArg by remember { mutableStateOf("") }

    if (argPromptCommand != null) {
        AlertDialog(
            onDismissRequest = { argPromptCommand = null }, containerColor = Color(0xFF1E293B),
            title = { Text("Set Argument", color = TextPrimary, fontFamily = UbuntuFont) },
            text = { OutlinedTextField(value = runtimeArg, onValueChange = { runtimeArg = it }, label = { Text("Arg for ${argPromptCommand?.label}") }, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = UbuntuFont)) },
            confirmButton = { TextButton(onClick = { viewModel.updateCommandArg(argPromptCommand!!.id, runtimeArg); argPromptCommand = null; runtimeArg = "" }) { Text("SAVE AS DEFAULT", color = ActionBlue) } },
            dismissButton = { TextButton(onClick = { viewModel.executePush(argPromptCommand!!.cmd, runtimeArg); argPromptCommand = null; runtimeArg = "" }) { Text("EXECUTE ONCE", color = PremiumTeal) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Control Matrix", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary, fontFamily = UbuntuFont)
            Text("Swipe for TDLib Sync ⯈", color = TextSecondary, fontSize = 12.sp, fontFamily = UbuntuFont)
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    viewModel.topPanelCommands.forEach { msg ->
                        var isToggled by remember { mutableStateOf(false) }
                        IconButton(onClick = {
                            val cmd = if(msg.isToggle && isToggled) msg.toggledCmd else msg.cmd
                            val arg = if(msg.isToggle && isToggled) msg.toggledArg else msg.defaultArg
                            viewModel.executePush(cmd, arg)
                            if(msg.isToggle) isToggled = !isToggled
                        }) { Icon(getIconByName(msg.icon), null, tint = if(isToggled) PremiumPurple else PremiumTeal) }
                    }
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
        Text("Directives", fontSize = 16.sp, color = TextSecondary, fontFamily = UbuntuFont); Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(viewModel.pushMessages) { msg ->
                var isToggled by remember { mutableStateOf(false) }
                Card(
                    shape = PremiumShapes.medium, border = BorderStroke(1.dp, GlassBorder), colors = CardDefaults.cardColors(containerColor = GlassSurface),
                    modifier = Modifier.aspectRatio(1f).combinedClickable(
                        onClick = { val cmd = if(msg.isToggle && isToggled) msg.toggledCmd else msg.cmd; val arg = if(msg.isToggle && isToggled) msg.toggledArg else msg.defaultArg; viewModel.executePush(cmd, arg); if(msg.isToggle) isToggled = !isToggled },
                        onDoubleClick = { runtimeArg = if(msg.isToggle && isToggled) msg.toggledArg else msg.defaultArg; argPromptCommand = msg }
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(getIconByName(msg.icon), null, tint = if(isToggled) PremiumTeal else TextPrimary, modifier = Modifier.size(28.dp)); Spacer(modifier = Modifier.height(8.dp))
                            Text(if(msg.isToggle && isToggled) msg.toggledLabel else msg.label, color = TextPrimary, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp), fontFamily = UbuntuFont)
                            val displayArg = if(msg.isToggle && isToggled) msg.toggledArg else msg.defaultArg
                            if (displayArg.isNotBlank()) Text("($displayArg)", color = PremiumTeal, fontSize = 9.sp, maxLines = 1, fontFamily = UbuntuFont)
                        }
                    }
                }
            }
        }
    }
}

// ---- Telegram TDLib Clone Tab ----
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(viewModel.tgChatName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, fontFamily = UbuntuFont)
                        }
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
                        Text(
                            when(viewModel.tdAuthState) { TdAuthState.WAIT_PHONE -> "Enter Phone (+1234567890)"; TdAuthState.WAIT_CODE -> "Enter Telegram Code"; TdAuthState.WAIT_PASSWORD -> "Enter 2FA Password"; else -> ""},
                            color = Color.White, fontFamily = UbuntuFont
                        )
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
                    Text("Turn on using the power icon above.", color = TextSecondary, fontSize = 12.sp, fontFamily = UbuntuFont)
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
            Icon(Icons.Rounded.EmojiEmotions, null, tint = TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
            BasicTextField(
                value = inputTxt, onValueChange = { inputTxt = it },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = UbuntuFont),
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                decorationBox = { inner -> if (inputTxt.isEmpty()) Text("Message", color = TextSecondary, fontSize = 16.sp, fontFamily = UbuntuFont); inner() }
            )
            Icon(Icons.Rounded.AttachFile, null, tint = TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
            if (inputTxt.isNotBlank()) {
                IconButton(onClick = { viewModel.sendTelegramMessage(inputTxt); inputTxt = "" }) { Icon(Icons.Rounded.Send, null, tint = ActionBlue) }
            } else {
                Icon(Icons.Rounded.Mic, null, tint = TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerTab(viewModel: CoreViewModel) {
    val context = LocalContext.current
    val isRunning by ServerCore.isRunningFlow.collectAsState()
    var selectedPage by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedPage, containerColor = Color.Transparent, contentColor = PremiumTeal,
            indicator = { tabPositions -> TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(tabPositions[selectedPage]), color = PremiumTeal) }
        ) {
            Tab(selected = selectedPage == 0, onClick = { selectedPage = 0 }, text = { Text("TERMINAL", fontFamily = UbuntuFont, color = if(selectedPage == 0) PremiumTeal else TextSecondary) })
            Tab(selected = selectedPage == 1, onClick = { selectedPage = 1 }, text = { Text("LIVE CONTROLS", fontFamily = UbuntuFont, color = if(selectedPage == 1) PremiumTeal else TextSecondary) })
        }
        Box(modifier = Modifier.weight(1f)) {
            if (selectedPage == 0) TerminalPage(viewModel, context, isRunning) 
            else LiveControlPage(viewModel, context)
        }
    }
}

@Composable
fun TerminalPage(viewModel: CoreViewModel, context: Context, isRunning: Boolean) {
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.serverLogs.size) { if(viewModel.serverLogs.isNotEmpty()) listState.animateScrollToItem(viewModel.serverLogs.size - 1) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        GlassButton(if (isRunning) "TERMINATE NODE" else "INITIALIZE NODE", Icons.Rounded.PowerSettingsNew, if (isRunning) PremiumRose else ActionBlue, Modifier.height(56.dp)) { viewModel.toggleServer(context) }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("System Event Log", color = TextSecondary, fontSize = 14.sp, fontFamily = UbuntuFont)
            IconButton(onClick = { viewModel.serverLogs.clear() }) { Icon(Icons.Rounded.DeleteOutline, null, tint = TextSecondary) }
        }
        GlassCard(modifier = Modifier.fillMaxWidth().weight(1f).padding(top=8.dp)) {
            LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) { items(viewModel.serverLogs) { log -> val color = if (log.contains("ERROR") || log.contains("FAIL") || log.contains("SEVERED")) PremiumRose else if (log.contains("SUCCESS") || log.contains("ESTABLISHED")) PremiumTeal else TextPrimary; Text(log, color = color, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 2.dp), fontFamily = CyberFont) } }
        }
    }
}

@Composable
fun LiveControlPage(viewModel: CoreViewModel, context: Context) {
    var showAudioModal by remember { mutableStateOf(false) }
    var showScreenCast by remember { mutableStateOf(false) }
    var runtimeArg by remember { mutableStateOf("") }
    var promptVibrate by remember { mutableStateOf(false) }

    if (showAudioModal) AudioControlModal(viewModel, context) { showAudioModal = false }
    if (showScreenCast) ScreenCastOverlay(viewModel) { showScreenCast = false }
    if (promptVibrate) {
        AlertDialog(
            onDismissRequest = { promptVibrate = false }, containerColor = Color(0xFF1E293B),
            title = { Text("Vibrate Duration (ms)", color = TextPrimary) },
            text = { OutlinedTextField(value = runtimeArg, onValueChange = { runtimeArg = it }) },
            confirmButton = { TextButton(onClick = { viewModel.sendLive("vibrate", runtimeArg); promptVibrate = false; runtimeArg = "" }) { Text("EXECUTE", color = PremiumTeal) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Active Media Streams", fontSize = 14.sp, color = TextSecondary, fontFamily = UbuntuFont); Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { GlassButton("Audio Hub", Icons.Rounded.Mic, PremiumTeal) { showAudioModal = true } }
            item { GlassButton("Screen Cast", Icons.Rounded.Cast, ActionBlue) { showScreenCast = true; viewModel.sendLive("live_screen_cast", "start") } }
            item { GlassButton("Cam Front", Icons.Rounded.CameraFront) { viewModel.sendLive("stream_cam_front", "start") } }
            item { GlassButton("Cam Back", Icons.Rounded.CameraRear) { viewModel.sendLive("stream_cam_back", "start") } }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Environment", fontSize = 14.sp, color = TextSecondary, fontFamily = UbuntuFont); Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Vibrate Device", null, TextPrimary, Modifier.weight(1f)) { promptVibrate = true }
            GlassButton("Sensors On", null, TextPrimary, Modifier.weight(1f)) { viewModel.sendLive("stream_sensors", "start") }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Accessibility Navigation", fontSize = 14.sp, color = TextSecondary, fontFamily = UbuntuFont); Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("BACK", null, PremiumTeal, Modifier.weight(1f)) { viewModel.sendLive("btn_back") }
            GlassButton("HOME", null, ActionBlue, Modifier.weight(1f)) { viewModel.sendLive("btn_home") }
            GlassButton("RECENTS", null, PremiumTeal, Modifier.weight(1f)) { viewModel.sendLive("btn_recents") }
        }
    }
}

@Composable
fun AudioControlModal(viewModel: CoreViewModel, context: Context, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Audio Control Matrix", fontSize = 18.sp, color = ActionBlue, fontFamily = UbuntuFont); Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    GlassButton("CALL", null, PremiumTeal, Modifier.weight(1f)) { viewModel.sendLive("live_audio_mode", "call"); viewModel.startMic() }
                    Spacer(modifier = Modifier.width(8.dp))
                    GlassButton("LISTEN", null, PremiumTeal, Modifier.weight(1f)) { viewModel.sendLive("live_audio_mode", "mic") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                GlassButton("MEDIA (INTERNAL)", null, TextPrimary) { viewModel.sendLive("live_audio_mode", "media") }
                Spacer(modifier = Modifier.height(16.dp))
                GlassButton("TERMINATE AUDIO", Icons.Rounded.Close, PremiumRose) { viewModel.sendLive("live_audio_mode", "off"); viewModel.stopMic(); onDismiss() }
            }
        }
    }
}

@Composable
fun ScreenCastOverlay(viewModel: CoreViewModel, onDismiss: () -> Unit) {
    var offsetX by remember { mutableFloatStateOf(0f) }; var offsetY by remember { mutableFloatStateOf(0f) }
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

class JsonVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val coloredString = buildAnnotatedString {
            val str = text.text
            val regex = Regex("""(".*?"\s*:)|(".*?")|(\b\d+\b)|(\b(?:true|false|null)\b)|([\[\]\{\}])""")
            var lastIndex = 0
            for (match in regex.findAll(str)) {
                append(str.substring(lastIndex, match.range.first))
                val color = when {
                    match.groups[1] != null -> ActionBlue 
                    match.groups[2] != null -> SuccessGreen 
                    match.groups[3] != null -> PremiumPurple 
                    match.groups[4] != null -> PremiumRose 
                    match.groups[5] != null -> TextPrimary 
                    else -> TextPrimary
                }
                withStyle(SpanStyle(color = color)) { append(match.value) }
                lastIndex = match.range.last + 1
            }
            append(str.substring(lastIndex))
        }
        return TransformedText(coloredString, OffsetMapping.Identity)
    }
}

@Composable
fun JsonSetupTab(viewModel: CoreViewModel) {
    val context = LocalContext.current
    var jsonText by remember { mutableStateOf(viewModel.exportConfigToJson()) }
    var showAddDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(viewModel.pushMessages.size, viewModel.topPanelCommands.size) { jsonText = viewModel.exportConfigToJson() }

    if (showAddDialog) {
        var includeToggle by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false }, containerColor = Color(0xFF1E293B),
            title = { Text("Generate Command Template", color = TextPrimary, fontFamily = UbuntuFont) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeToggle, onCheckedChange = { includeToggle = it }, colors = CheckboxDefaults.colors(checkedColor = ActionBlue))
                    Text("Include Toggle Parameters", color = TextPrimary, fontFamily = UbuntuFont)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val root = JSONObject(jsonText)
                        val cmds = root.getJSONArray("commands")
                        val newCmd = JSONObject().put("id", (0..999).random()).put("label", "New Cmd").put("cmd", "action").put("arg", "").put("icon", "code")
                        if (includeToggle) { newCmd.put("isToggle", true).put("toggledLabel", "Revert Cmd").put("toggledCmd", "revert").put("toggledArg", "") } 
                        else { newCmd.put("isToggle", false).put("toggledLabel", "").put("toggledCmd", "").put("toggledArg", "") }
                        cmds.put(newCmd)
                        jsonText = root.toString(4)
                        showAddDialog = false
                    } catch (e: Exception) { Toast.makeText(context, "Fix JSON syntax first!", Toast.LENGTH_SHORT).show() }
                }) { Text("ADD TO JSON", color = ActionBlue) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Master Config", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary, fontFamily = UbuntuFont)
            IconButton(onClick = { showAddDialog = true }) { Icon(Icons.Rounded.AddBox, "Add Template", tint = ActionBlue) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = jsonText, onValueChange = { jsonText = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = CyberFont, fontSize = 13.sp),
            visualTransformation = JsonVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GlassBorder, unfocusedBorderColor = GlassBorder, focusedContainerColor = Color(0x33000000), unfocusedContainerColor = Color(0x33000000))
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        GlassButton("APPLY CONFIGURATION", Icons.Rounded.Save, SuccessGreen, Modifier.height(56.dp)) {
            if (viewModel.importConfigFromJson(jsonText)) Toast.makeText(context, "Config Applied", Toast.LENGTH_SHORT).show()
            else Toast.makeText(context, "Invalid JSON Format", Toast.LENGTH_SHORT).show()
        }
    }
}