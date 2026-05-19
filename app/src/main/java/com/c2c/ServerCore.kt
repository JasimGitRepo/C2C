package com.c2c

import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.websocket.WebSocketServerSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList

// --- Data Models ---
data class PushMessage(
    val id: Int, 
    val label: String, 
    val cmd: String, 
    var defaultArg: String = "", 
    val icon: String = "", 
    val isToggle: Boolean = false, 
    val toggledLabel: String = "", 
    val toggledCmd: String = "", 
    val toggledArg: String = ""
)

data class TgMessage(
    val id: Long, 
    val text: String, 
    val isMe: Boolean, 
    val timestamp: Long
)

enum class TdAuthState { 
    INIT, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY, CLOSED 
}

// --- Global Core Engine State ---
object ServerCore {
    var ktorServer: ApplicationEngine? = null
    val liveSessions = CopyOnWriteArrayList<WebSocketServerSession>()
    
    val logsFlow = MutableSharedFlow<String>(replay = 50)
    val streamFlow = MutableSharedFlow<ByteArray>(replay = 5) // Kept for legacy byte-stream fallbacks
    
    val isRunningFlow = MutableStateFlow(false)
    var isRunning: Boolean 
        get() = isRunningFlow.value
        set(value) { isRunningFlow.value = value }
        
    var lastStatusSlab = "System Ready. Waiting for link..."

    fun log(msg: String, isSuccess: Boolean? = null) {
        logsFlow.tryEmit(msg)
        if (isSuccess == true) lastStatusSlab = "SUCCESS: $msg"
        else if (isSuccess == false) lastStatusSlab = "ERROR: $msg"
    }
}