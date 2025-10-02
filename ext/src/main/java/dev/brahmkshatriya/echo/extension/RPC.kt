package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.extension.models.Activity
import dev.brahmkshatriya.echo.extension.models.Identity
import dev.brahmkshatriya.echo.extension.models.ImageLink
import dev.brahmkshatriya.echo.extension.models.Link
import dev.brahmkshatriya.echo.extension.models.Presence
import dev.brahmkshatriya.echo.extension.models.Res
import dev.brahmkshatriya.echo.extension.models.Type
import dev.brahmkshatriya.echo.extension.models.User
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.SocketException
import java.net.UnknownHostException

class RPC(
    private val client: OkHttpClient,
    private val json: Json,
    private val token: String,
    private val applicationId: String,
    private val imageUploader: ImageUploader,
    private val messageFlow: MutableSharedFlow<Message>,
) {

    @OptIn(DelicateCoroutinesApi::class)
    private val scope = CoroutineScope(Dispatchers.IO) + CoroutineExceptionHandler { _, t ->
        GlobalScope.launch(Dispatchers.IO) {
            messageFlow.emit(
                Message("Discord RPC: ${t.message}")
            )
        }
    }

    private val creationTime = System.currentTimeMillis()
    
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null
    private var sequence: Int = 0
    private var connected = false
    private var isIdentified = false
    private val gatewayUrl = "wss://gateway.discord.gg/?encoding=json&v=10"

    private fun getRequest() = Request.Builder()
        .url(resumeGatewayUrl ?: gatewayUrl)
        .build()

    private val listener = Listener()
    private var webSocket = client.newWebSocket(getRequest(), listener)

    var type: Type? = null
    var activityName: String? = null
    var detail: String? = null
    var state: String? = null
    var largeImage: ImageLink? = null
    var smallImage: ImageLink? = null
    var startTimestamp: Long? = null
    var endTimeStamp: Long? = null
    var buttons = listOf<Link>()

    private fun status(invisible: Boolean) = if (invisible) "invisible" else "idle"

    private suspend fun createPresence(invisible: Boolean): String {
        val buttons = buttons.ifEmpty { null }
        return json.encodeToString(Presence.Response(
            3,
            Presence(
                activities = listOf(
                    Activity(
                        name = activityName,
                        state = state,
                        details = detail,
                        type = type?.value,
                        timestamps = if (startTimestamp != null)
                            Activity.Timestamps(startTimestamp, endTimeStamp)
                        else null,
                        assets = Activity.Assets(
                            largeImage = largeImage?.discordUri(),
                            largeText = largeImage?.label,
                            smallImage = smallImage?.discordUri(),
                            smallText = smallImage?.label
                        ),
                        buttons = buttons?.map { it.label },
                        metadata = buttons?.map { it.url }?.let {
                            Activity.Metadata(buttonUrls = it)
                        },
                        applicationId = applicationId,
                    )
                ),
                afk = true,
                since = creationTime,
                status = status(invisible)
            )
        )
        )
    }

    private val assetApi = RPCExternalAsset(applicationId, token, client, json)
    private suspend fun ImageLink.discordUri(): String? {
        val url = imageUploader.getImageUrl(imageHolder) ?: return null
        return assetApi.getDiscordUri(url)
    }

    private fun sendIdentity() {
        println("Sending IDENTIFY")
        isIdentified = false 
        val response = Identity.Response(
            op = 2,
            d = Identity(
                token = token,
                properties = Identity.Properties(
                    os = "windows",
                    browser = "Chrome",
                    device = "disco"
                ),
                compress = false,
                intents = 0
            )
        )
        val success = try {
            webSocket.send(json.encodeToString(response))
        } catch (e: Exception) {
            println("Exception sending IDENTIFY: ${e.message}")
            false
        }
        
        if (!success) {
            println("Failed to send IDENTIFY - socket may be closed")
            reconnectWebSocket()
        } else {
            println("IDENTIFY sent successfully")
        }
    }
    
    private fun sendResume() {
        println("Sending RESUME with sessionId: $sessionId, sequence: $sequence")
        isIdentified = false 
        val response = """{"op":6,"d":{"token":"$token","session_id":"$sessionId","seq":$sequence}}"""
        val success = try {
            webSocket.send(response)
        } catch (e: Exception) {
            println("Exception sending RESUME: ${e.message}")
            false
        }
        
        if (!success) {
            println("Failed to send RESUME - falling back to IDENTIFY")
            sessionId = null
            sequence = 0
            resumeGatewayUrl = null
            sendIdentity()
        } else {
            println("RESUME sent successfully")
        }
    }
    
    private fun reconnectWebSocket() {
        scope.launch {
            delay(1000) 
            println("Reconnecting WebSocket...")
            webSocket = client.newWebSocket(getRequest(), Listener())
        }
    }

    val user = MutableStateFlow<User?>(null)
    suspend fun send(invisible: Boolean, block: suspend RPC.() -> Unit) {
        block.invoke(this@RPC)
        
        println("RPC send() called - waiting for authentication...")
        println("Current state: connected=$connected, isIdentified=$isIdentified, user=${user.value?.username}")
        
        var connectionAttempts = 0
        val maxConnectionAttempts = 250 
        while ((!connected || !isIdentified) && connectionAttempts < maxConnectionAttempts) {
            delay(100)
            connectionAttempts++
            if (connectionAttempts % 10 == 0) {
                println("Waiting for connection... ${connectionAttempts * 100}ms (connected=$connected, isIdentified=$isIdentified)")
            }
        }
        
        if (!connected || !isIdentified) {
            println("✗ Connection failed after ${connectionAttempts * 100}ms - connected=$connected, isIdentified=$isIdentified")
            throw Exception("Discord RPC: Connection not ready after 15 seconds. Please check your connection and try again.")
        }
        
        println("✓ Connection established, now waiting for user data...")
        
        val userValue = withTimeoutOrNull(5000) {
            user.first { it != null }
        }
        
        if (userValue == null) {
            println("User data not available but connection is ready - proceeding anyway")
        } else {
            println("User data available: ${userValue.username}")
        }
        
        println("Preparing presence update...")
        val presence = createPresence(invisible)
        
        var sendAttempts = 0
        val maxSendAttempts = 2
        while (sendAttempts < maxSendAttempts) {
            println("Attempt ${sendAttempts + 1}/$maxSendAttempts: Sending presence update...")
            try {
                if (webSocket.send(presence)) {
                    println("Presence update sent successfully!")
                    return
                } else {
                    println("Send failed (socket may be closing)")
                }
            } catch (e: Exception) {
                println("Send exception: ${e.message}")
            }
            
            sendAttempts++
            if (sendAttempts < maxSendAttempts) {
                val waitTime = 500L * sendAttempts
                println("Waiting ${waitTime}ms before retry...")
                delay(waitTime)
                
                if (!connected) {
                    println("Connection lost, waiting for reconnection...")
                    var reconnectWait = 0
                    while (!connected && reconnectWait < 50) {
                        delay(100)
                        reconnectWait++
                    }
                }
            }
        }
        
        println("All send attempts failed, triggering reconnection...")
        scope.launch {
            messageFlow.emit(Message("Discord RPC: Reconnecting to improve stability..."))
        }
        reconnectWebSocket()
    }

    fun sendDefaultPresence(invisible: Boolean) {
        val presenceJson = json.encodeToString(
            Presence.Response(
                3,
                Presence(status = status(invisible))
            )
        )
        println("Sending Default Presence")
        
        val success = try {
            webSocket.send(presenceJson)
        } catch (e: Exception) {
            println("Exception sending default presence: ${e.message}")
            false
        }
        
        if (success) {
            println("Default presence sent successfully")
        } else {
            println("Failed to send default presence - socket may be closed")
        }
    }

    fun stop() {
        println("Stopping Discord RPC")
        connected = false
        isIdentified = false
        sessionId = null
        resumeGatewayUrl = null
        sequence = 0
        webSocket.close(1000, "Normal closure")
        scope.cancel()
    }

    inner class Listener : WebSocketListener() {
        private var heartbeatInterval: Long? = null
        private var heartbeatJob: Job? = null
        private var lastHeartbeatAck = true

        private fun sendHeartBeat() {
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (true) {
                    delay(heartbeatInterval ?: 41250L)
                    
                    if (!lastHeartbeatAck) {
                        println("Heartbeat not acknowledged - connection may be dead, reconnecting...")
                        connected = false
                        webSocket.close(4000, "Heartbeat timeout")
                        return@launch
                    }
                    
                    lastHeartbeatAck = false
                    val heartbeatMsg = """{"op":1,"d":${sequence.takeIf { it > 0 } ?: "null"}}"""
                    println("Sending heartbeat with seq: $sequence")
                    if (!webSocket.send(heartbeatMsg)) {
                        println("Failed to send heartbeat - socket closed")
                        connected = false
                        reconnectWebSocket()
                        return@launch
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val res = json.decodeFromString<Res>(text)
                println("Received op: ${res.op}, seq: ${res.s}, event: ${res.t}")
                
                res.s?.let { sequence = it }
                
                when (res.op) {
                    10 -> { 
                        res.d as JsonObject
                        heartbeatInterval = res.d["heartbeat_interval"]!!.jsonPrimitive.long
                        println("Received HELLO with heartbeat interval: $heartbeatInterval ms")
                        
                        lastHeartbeatAck = true
                        
                        sendHeartBeat()
                        
                        scope.launch {
                            delay(100) 
                            if (sequence > 0 && !sessionId.isNullOrBlank() && !isIdentified) {
                                println("Attempting to resume session...")
                                sendResume()
                            } else {
                                println("Starting new session with IDENTIFY...")
                                sendIdentity()
                            }
                        }
                    }

                    0 -> { 
                        when (res.t) {
                            "READY" -> {
                                println("✓ Received READY event")
                                println("Raw READY data: ${text.take(500)}") 
                                
                                try {
                                    res.d as JsonObject
                                    val userJson = res.d["user"] as? JsonObject
                                    
                                    if (userJson != null) {
                                        val parsedUser = json.decodeFromJsonElement<User>(userJson)
                                        user.value = parsedUser
                                        println("✓ User parsed successfully: ${parsedUser.username}")
                                    } else {
                                        println("✗ User field not found in READY event")
                                        val userResponse = json.decodeFromString<User.Response>(text)
                                        user.value = userResponse.d.user
                                        println("✓ User parsed via alternative method: ${userResponse.d.user.username}")
                                    }
                                    
                                    sessionId = res.d["session_id"]?.jsonPrimitive?.content
                                    resumeGatewayUrl = res.d["resume_gateway_url"]?.jsonPrimitive?.content
                                        ?.let { "$it?encoding=json&v=10" }
                                    
                                    println("Session ID: $sessionId")
                                    println("Resume Gateway: $resumeGatewayUrl")
                                    println("User: ${user.value?.username}")
                                    
                                    isIdentified = true
                                    connected = true
                                    
                                    println("Discord RPC fully connected and ready!")
                                    
                                    scope.launch {
                                        delay(200) 
                                        sendDefaultPresence(true)
                                    }
                                } catch (e: Exception) {
                                    println("Error parsing READY event: ${e.message}")
                                    e.printStackTrace()
                                    isIdentified = true
                                    connected = true
                                }
                            }
                            "RESUMED" -> {
                                println("Session RESUMED successfully")
                                println("Sequence: $sequence, Session ID: $sessionId")
                                isIdentified = true
                                connected = true
                                println("Discord RPC resumed and ready!")
                            }
                        }
                    }

                    1 -> { 
                        println("Received heartbeat request")
                        val heartbeatMsg = """{"op":1,"d":${sequence.takeIf { it > 0 } ?: "null"}}"""
                        webSocket.send(heartbeatMsg)
                    }

                    11 -> { 
                        println("Heartbeat acknowledged")
                        lastHeartbeatAck = true
                    }
                    
                    7 -> { 
                        println("Received RECONNECT request from Discord")
                        connected = false
                        webSocket.close(4000, "Reconnect requested")
                    }
                    
                    9 -> { 
                        println("Received INVALID_SESSION")
                        val canResume = (res.d as? JsonObject)?.get("d")?.jsonPrimitive?.content?.toBoolean() ?: false
                        
                        if (!canResume) {
                            println("Cannot resume - resetting session")
                            sessionId = null
                            sequence = 0
                            resumeGatewayUrl = null
                        }
                        
                        scope.launch {
                            delay(150) 
                            isIdentified = false
                            sendIdentity()
                        }
                    }
                }
            } catch (e: Exception) {
                println("Error processing message: ${e.message}")
                e.printStackTrace()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            println("WebSocket closed with code: $code, reason: $reason")
            heartbeatJob?.cancel()
            connected = false
            
            when (code) {
                1000 -> { 
                    println("Normal closure - not reconnecting")
                }
                4000 -> { 
                    println("Reconnect code 4000 - attempting reconnect")
                    scope.launch {
                        delay(200)
                        this@RPC.webSocket = client.newWebSocket(getRequest(), Listener())
                    }
                }
                4004 -> { 
                    scope.launch {
                        messageFlow.emit(Message("Discord RPC: Authentication failed. Please re-login."))
                    }
                }
                4014 -> { 
                    scope.launch {
                        messageFlow.emit(Message("Discord RPC: Invalid intents configured."))
                    }
                }
                else -> {
                    println("Unexpected close code: $code - attempting reconnect")
                    scope.launch {
                        delay(1000)
                        this@RPC.webSocket = client.newWebSocket(getRequest(), Listener())
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            println("WebSocket failure: ${t.message}")
            t.printStackTrace()
            heartbeatJob?.cancel()
            connected = false
            
            scope.launch {
                when (t) {
                    is SocketException, is UnknownHostException -> {
                        messageFlow.emit(Message("Discord RPC: Connection lost. Reconnecting in 3 seconds..."))
                        delay(3000)
                        this@RPC.webSocket = client.newWebSocket(getRequest(), Listener())
                    }
                    else -> {
                        messageFlow.emit(Message("Discord RPC: ${t.message}"))
                        delay(5000)
                        this@RPC.webSocket = client.newWebSocket(getRequest(), Listener())
                    }
                }
            }
        }
    }
}