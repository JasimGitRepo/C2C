package com.c2c.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CommandEngine(private val context: Context, private val httpClient: HttpClient) {
    
    private val commandQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val isNetworkAvailable = MutableStateFlow(true)
    private val engineScope = CoroutineScope(Dispatchers.IO)

    init {
        monitorNetwork()
        repeat(3) { 
            engineScope.launch {
                for (command in commandQueue) {
                    isNetworkAvailable.first { it }
                    try {
                        val prefs = context.getSharedPreferences("CoreConfig", Context.MODE_PRIVATE)
                        val url = prefs.getString("ntfyUrl", "https://ntfy.sh")!!
                        val topic = prefs.getString("ntfyTopic", "default_topic")!!
                        val targetUrl = if (url.startsWith("http")) "${url.trimEnd('/')}/$topic" else "https://$url/${topic.trimEnd('/')}"
                        
                        val payload = if (command.second.isNotBlank()) {
                            """{"cmd": "${command.first.replace("\"", "\\\"")}", "arg": "${command.second.replace("\"", "\\\"")}"}"""
                        } else {
                            """{"cmd": "${command.first.replace("\"", "\\\"")}"}"""
                        }

                        httpClient.post(targetUrl) { setBody(payload) }
                    } catch (e: Exception) { }
                }
            }
        }
    }

    private fun monitorNetwork() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { isNetworkAvailable.value = true }
            override fun onLost(network: Network) { isNetworkAvailable.value = false }
        })
    }

    fun enqueue(cmd: String, arg: String = "") {
        commandQueue.trySend(Pair(cmd, arg))
    }
}