package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.providers.MessageFlowProvider
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingMultipleChoice
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.models.ImageLink
import dev.brahmkshatriya.echo.extension.models.Link
import dev.brahmkshatriya.echo.extension.models.Type
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit.SECONDS

open class DiscordRPC : ExtensionClient, LoginClient.WebView, TrackerClient,
    MusicExtensionsProvider, MessageFlowProvider {

    val json = Json {
        encodeDefaults = true
        allowStructuredMapKeys = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(10, SECONDS)
        .readTimeout(10, SECONDS)
        .writeTimeout(10, SECONDS)
        .build()

    open val uploader = ImageUploader(client, json)

    private val applicationId = "1135077904435396718"
    private val appName = "Echo"
    private val appUrl = "https://github.com/brahmkshatriya/echo"

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val activityType
        get() = setting.getStringSet("activityType")?.firstOrNull() ?: Type.Listening.name

    private val activityNameType
        get() = setting.getStringSet("activityName")?.firstOrNull() ?: "a_echo"

    private val showEchoIcon
        get() = setting.getBoolean("showEchoIcon") ?: true

    private val showButtons
        get() = setting.getStringSet("selectedButtons") ?: setOf("a_play", "c_profile")

    private val invisibility
        get() = setting.getBoolean("invisible") ?: false

    private val useMusicUrl
        get() = setting.getBoolean("useMusicUrl") ?: false

    private val disableClients
        get() = setting.getStringSet("disable") ?: emptySet()

    override suspend fun getSettingItems() = listOf(
        SettingCategory(
            "Looks",
            "looks",
            listOf(
                SettingMultipleChoice(
                    "Activity Type",
                    "activityType",
                    "How the RPC activity title will be shown",
                    Type.entries.map { it.title },
                    Type.entries.map { it.name },
                ),
                SettingMultipleChoice(
                    "Activity Name",
                    "activityName",
                    "Name of the Activity in \"Listening to [...]\"",
                    listOf(
                        appName,
                        "[Extension Name]",
                        "[Album/Playlist Name]",
                        "[Song Name]",
                        "[Artist Name]"
                    ),
                    listOf("a_echo", "b_extension", "c_context", "d_track", "e_name"),
                    setOf(0)
                ),
                SettingSwitch(
                    "Show Echo Icon",
                    "showEchoIcon",
                    "Show Small Echo Icon on the RPC.",
                    true
                ),
                SettingMultipleChoice(
                    "RPC Buttons",
                    "selectedButtons",
                    "The buttons to show on the RPC. (Only 2 will be visible)",
                    listOf(
                        "Play/Play on $appName",
                        "Song Artist",
                        "Profile",
                        "Try Echo"
                    ),
                    listOf(
                        "a_play",
                        "b_artist",
                        "c_profile",
                        "d_try_echo"
                    ),
                    setOf(0, 1)
                )
            )
        ),
        SettingCategory(
            "Behavior",
            "behavior",
            listOf(
                SettingSwitch(
                    "Stay Invisible",
                    "invisible",
                    "Stay invisible when you are not actually online. If this is off, you will become \"idle\" on discord when listening to songs on Echo.",
                    false
                ),
                SettingSwitch(
                    "Use Music Url instead of Echo URI",
                    "useMusicUrl",
                    "The Play Button will allow opening music's actual link on extension's site. This will disable people to directly play music on Echo when clicked on play button & instead open the site.",
                    false
                ),
                SettingMultipleChoice(
                    "Disable for Extensions",
                    "disable",
                    "Disable RPC for these extensions.",
                    extensionsMap.values.map { it.name },
                    extensionsMap.keys.toList(),
                )
            )
        )
    )
    override val webViewRequest = object : WebViewRequest.Evaluate<List<User>> {
        override val initialUrl = "https://discord.com/login".toGetRequest(
            mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.363")
        )
        override val javascriptToEvaluateOnPageStart = """
            function() { 
                function initializeLocalStorage() {
                    try {
                        window.LOCAL = window.localStorage || window.webkitStorageLocal;
                        if (window.LOCAL) {
                            window.LOCAL.removeItem = function(key) { 
                                console.log('Prevented removal of item:', key);
                                return true; 
                            };
                            console.log('localStorage initialized successfully');
                        } else {
                            console.error('localStorage is not available');
                        }
                    } catch (e) {
                        console.error('Error initializing localStorage:', e);
                    }
                }   
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', initializeLocalStorage);
                } else {
                    initializeLocalStorage();
                }
            }""".trimIndent()

        override val stopUrlRegex = "https://discord\\.com/app".toRegex()
        override val javascriptToEvaluate = """
            function() { 
                var token = null;
                try {
                    var iframe = document.createElement('iframe');
                    iframe.style.display = 'none';
                    document.body.appendChild(iframe);
                    
                    var iframeLocalStorage = iframe.contentWindow.localStorage;
                    if (iframeLocalStorage) {
                        token = iframeLocalStorage.getItem('token');
                    }
                    
                    document.body.removeChild(iframe);
                } catch (iframeError) {
                    console.error('Error accessing iframe localStorage:', iframeError);
                }
                
                console.log('Token found:', token ? 'Yes' : 'No');
                if (token) {
                    console.log('Token length:', token.length);
                }
                
                return token;
            }""".trimIndent()

        override suspend fun onStop(
            url: NetworkRequest, data: String?,
        ): List<User>? {
            val token = data.orEmpty().trim('"')
            if (token.length <= 69) throw Exception("Token not found, token size: ${token.length}")
            val rpc = getRPC(token)
            val user =
                runCatching { rpc.user.first { it != null } }.getOrNull()
                    ?: throw Exception("Failed to load user data.")
            rpc.stop()
            return listOf(
                User(
                    user.id,
                    user.globalName ?: user.username,
                    user.userAvatar().toImageHolder(),
                    "@${user.username}",
                    mapOf("token" to token)
                )
            )
        }
    }

    private var rpc: RPC? = null
    override fun setLoginUser(user: User?) {
        rpc?.stop()
        if (user == null) {
            rpc = null
            return
        }
        val token = user.extras["token"] ?: throw ClientException.Unauthorized(user.id)
        rpc = getRPC(token)
    }

    override suspend fun getCurrentUser() = rpc?.user?.value?.run {
        User(id, username, userAvatar().toImageHolder())
    }

    private fun getRPC(token: String) =
        RPC(client, json, token, applicationId, uploader, messageFlow)

    private val appIconImage =
        "mp:app-icons/1135077904435396718/7ac162cf125e5e5e314a5e240726da41.png".toImageHolder()

    private suspend fun sendRpc(details: TrackDetails) {
        val (extensionId, track, context) = details
        val rpc = rpc ?: throw ClientException.LoginRequired()
        if (extensionId in disableClients) return

        rpc.send(invisibility) {
            type = Type.valueOf(activityType)

            val artists = track.artists.joinToString(", ") { it.name }

            activityName = when (activityNameType) {
                "a_echo" -> appName
                "b_extension" -> extensionsMap[extensionId]?.name ?: extensionId
                "c_context" -> context?.title ?: track.album?.title ?: track.title
                "d_track" -> track.title
                "e_name" -> artists.ifEmpty { appName }
                else -> appName
            }

            state = if (activityNameType == "e_name") null else artists
            detail = track.title
            startTimestamp = System.currentTimeMillis()
            endTimeStamp = track.duration?.let { startTimestamp!! + it }
            largeImage = track.cover?.let { ImageLink(track.album?.title ?: track.title, it) }
            smallImage = ImageLink(appName, appIconImage).takeIf { showEchoIcon }

            val item = track
            val uri = Link("Play on $appName", getPlayerUrl(extensionId, item))
            val playLink = uri.takeIf { !useMusicUrl }
                ?: getSharableUrl(extensionId, item)?.let { Link("Play", it) }
                ?: uri
            buttons = showButtons.mapNotNull { buttonId ->
                when (buttonId) {
                    "a_play" -> playLink
                    "b_artist" -> track.artists.firstOrNull()?.run {
                        getSharableUrl(extensionId, this)?.let { Link(name, it) }
                    }

                    "c_profile" -> getUserData(extensionId)?.let { Link("Profile", it.second) }
                    "d_try_echo" -> Link("Try $appName", appUrl)
                    else -> null
                }
            }
        }
    }

    private val pauseWaitTime = 10000L // if the track isn't played in 10sec, show pause status
    private var timer = Timer()

    override suspend fun onTrackChanged(details: TrackDetails?) {}
    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        if (details == null) rpc?.sendDefaultPresence(invisibility)
        else if (isPlaying) {
            timer.cancel()
            sendRpc(details)
        } else {
            timer = Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    rpc?.sendDefaultPresence(invisibility)
                }
            }, pauseWaitTime)
        }
    }

    private suspend fun getSharableUrl(clientId: String, item: EchoMediaItem): String? {
        val client = extensionsMap[clientId]?.instance?.value as? ShareClient ?: return null
        return runCatching { client.onShare(item) }.getOrNull()
    }

    private fun getPlayerUrl(clientId: String, mediaItem: EchoMediaItem): String {
        val type = when (mediaItem) {
            is Artist -> "artist"
            is Album -> "album"
            is Playlist -> "playlist"
            is Radio -> "radio"
            is Track -> "track"
        }
        return "echo://music/$clientId/$type/${mediaItem.id}"
    }

    override val requiredMusicExtensions: List<String> = listOf()
    private val extensionsMap = mutableMapOf<String, MusicExtension>()
    override fun setMusicExtensions(extensions: List<MusicExtension>) {
        extensions.forEach { extensionsMap[it.id] = it }
    }

    private suspend fun getUserData(extensionId: String) = runCatching {
        val client = extensionsMap[extensionId]?.instance?.value()?.getOrNull()
        if (client is LoginClient && client is ShareClient) {
            val user = client.getCurrentUser() ?: return@runCatching null
            val link = user.id
            user to link
        } else null
    }.getOrNull()

    private lateinit var messageFlow: MutableSharedFlow<Message>
    override fun setMessageFlow(messageFlow: MutableSharedFlow<Message>) {
        this.messageFlow = messageFlow
    }
}