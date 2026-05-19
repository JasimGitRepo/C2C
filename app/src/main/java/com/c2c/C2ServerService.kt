package com.c2c

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.ktor.server.application.*
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration

class C2ServerService : Service() {
    companion object { const val ACTION_STOP = "STOP_NODE" }
    
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("c2c_server", "Core Node Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopKtor(); stopSelf(); return START_NOT_STICKY }
        
        val port = intent?.getIntExtra("port", 8765) ?: 8765
        val stopIntent = Intent(this, C2ServerService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        val notification = NotificationCompat.Builder(this, "c2c_server")
            .setContentTitle("C2C Node Active")
            .setContentText("Listening on port $port")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TERMINATE NODE", stopPI)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        
        startKtor(port)
        return START_STICKY
    }
    
    private fun startKtor(port: Int) {
        if (ServerCore.isRunning) return
        try {
            ServerCore.ktorServer = embeddedServer(io.ktor.server.cio.CIO, port = port, host = "0.0.0.0") {
                install(WebSockets) {
                    maxFrameSize = Long.MAX_VALUE // CRITICAL FIX: Allows massive frames without disconnecting
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(15)
                    masking = false
                }
                routing {
                    webSocket("/live") {
                        ServerCore.liveSessions.add(this)
                        ServerCore.log("LINK ESTABLISHED: ${call.request.local.remoteHost}", true)
                        try { 
                            for (frame in incoming) { 
                                if (frame is Frame.Binary) ServerCore.streamFlow.emit(frame.readBytes()) 
                                else if (frame is Frame.Text) ServerCore.log("RECV: ${frame.readText()}")
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
        } catch (e: Exception) { 
            ServerCore.log("NODE CRASH: ${e.message}", false) 
        }
    }
    
    private fun stopKtor() { 
        ServerCore.ktorServer?.stop(1000, 2000)
        ServerCore.isRunning = false
        ServerCore.liveSessions.clear()
        ServerCore.log("NODE TERMINATED.", false) 
    }
    
    override fun onDestroy() { stopKtor(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}