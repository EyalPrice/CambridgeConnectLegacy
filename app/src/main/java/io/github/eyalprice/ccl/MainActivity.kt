package io.github.eyalprice.ccl

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class Tab(val label: String, val title: String) {
    PLAY("Playing", "Now Playing"), INPUTS("Inputs", "Inputs"), RADIO("Radio", "Radio"),
    MUSIC("Music", "Music"), PRO("Pro", "Pro")
}

enum class Page(val title: String) { SOUND("Sound"), SETTINGS("Settings"), QUEUE("Queue"), CHECK("Stereo check"), EXPLORER("Commands") }

class MainActivity : ComponentActivity() {

    private val vm: StereoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.Bg.toArgb()
        window.navigationBarColor = C.Bar.toArgb()
        setContent { CclTheme { App(vm, versionName(), versionCode()) } }
    }

    override fun onStart() { super.onStart(); vm.setForeground(true) }
    override fun onStop() { vm.setForeground(false); super.onStop() }

    /** The phone's side volume keys control the stereo while the app is open. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val connected = vm.state.value.conn == Conn.OK
        if (connected && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) vm.volumeStep(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    @Suppress("DEPRECATION")
    private fun versionCode(): Long = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (e: Exception) { 0 }
}

/** Asks GitHub for the newest release; returns (tag, download url) when it is newer than this build. */
private suspend fun newerRelease(current: Long): Pair<String, String>? = withContext(Dispatchers.IO) {
    try {
        val c = URL("https://api.github.com/repos/EyalPrice/CambridgeConnectLegacy/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return@withContext null
        val rel = JSONObject(c.inputStream.bufferedReader().readText())
        val tag = rel.optString("tag_name")
        val n = tag.substringAfterLast('.').toLongOrNull() ?: return@withContext null
        val assets = rel.optJSONArray("assets")
        val url = if (assets != null && assets.length() > 0) assets.getJSONObject(0).optString("browser_download_url") else rel.optString("html_url")
        if (n > current) tag to url else null
    } catch (e: Exception) { null }
}

@Composable
fun App(vm: StereoViewModel, version: String, versionCode: Long) {
    val st by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.PLAY) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var powerOff by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Pair<String, String>?>(null) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { update = newerRelease(versionCode) }
    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.toast(null) } }
    LaunchedEffect(st.pro) { if (!st.pro && tab == Tab.PRO) tab = Tab.PLAY }
    BackHandler(enabled = page != null) { page = null }
    BackHandler(enabled = page == null && tab != Tab.PLAY) { tab = Tab.PLAY }

    if (st.name.isBlank()) {
        Box(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) { WelcomeScreen(st, vm) }
        return
    }

    Scaffold(containerColor = C.Bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TopBar(page?.title ?: tab.title, st,
                onSound = { page = Page.SOUND }, onSettings = { page = Page.SETTINGS },
                onPower = { vm.togglePower() }, onPowerHold = { powerOff = true })
            update?.let { (tag, url) ->
                Banner("New version $tag available", "Download") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            when {
                st.conn == Conn.UNREACHABLE -> Banner("Can't reach ${st.name}. Is it on this Wi-Fi?", "Retry") { vm.connect(vm.device?.location ?: "") }
                st.standby -> Banner("${st.name} is in standby", "Wake up") { vm.power("ON") }
            }
            if (page != null) {
                Row(Modifier.fillMaxWidth().clickable { page = null }.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Icon(Icons.Filled.ArrowBack, "Back", tint = C.Muted, modifier = Modifier.size(18.dp))
                    Text("  Back", color = C.Muted, fontSize = 13.sp)
                }
            }
            Box(Modifier.weight(1f)) {
                when (page) {
                    Page.SOUND -> SoundScreen(st, vm)
                    Page.SETTINGS -> SettingsScreen(st, vm, version, update?.first?.let { "New: $it" })
                    Page.QUEUE -> QueueScreen(st, vm)
                    Page.CHECK -> CheckScreen(vm, version)
                    Page.EXPLORER -> ExplorerScreen(vm)
                    null -> when (tab) {
                        Tab.PLAY -> NowPlayingScreen(st, vm, onInputs = { tab = Tab.INPUTS }, onQueue = { page = Page.QUEUE })
                        Tab.INPUTS -> InputsScreen(st, vm) { }
                        Tab.RADIO -> RadioScreen(st, vm) { }
                        Tab.MUSIC -> MusicScreen(vm, onPlaying = { tab = Tab.PLAY }, onQueue = { page = Page.QUEUE })
                        Tab.PRO -> ProScreen(st, vm, onCheck = { page = Page.CHECK }, onExplorer = { page = Page.EXPLORER })
                    }
                }
            }
            if (tab != Tab.PLAY || page != null) {
                val t = when {
                    st.isSpotify -> st.playback.title.ifBlank { "Spotify" }
                    st.hasMetadata -> st.playback.title.ifBlank { st.sourceName }
                    else -> st.sourceName
                }
                MiniPlayer(st, t, onOpen = { page = null; tab = Tab.PLAY }, vm = vm)
            }
            NavigationBar(containerColor = C.Bar) {
                Tab.entries.filter { it != Tab.PRO || st.pro }.forEach { t ->
                    NavigationBarItem(
                        selected = page == null && tab == t,
                        onClick = { page = null; tab = t },
                        icon = {
                            Icon(when (t) {
                                Tab.PLAY -> Icons.Outlined.PlayCircle
                                Tab.INPUTS -> Icons.Outlined.GridView
                                Tab.RADIO -> Icons.Outlined.Radio
                                Tab.MUSIC -> Icons.Outlined.LibraryMusic
                                Tab.PRO -> Icons.Outlined.Tune
                            }, t.label)
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = C.Accent, selectedTextColor = C.Accent,
                            indicatorColor = C.AccentBg, unselectedIconColor = C.Muted, unselectedTextColor = C.Muted),
                    )
                }
            }
        }
    }

    if (powerOff) AlertDialog(
        onDismissRequest = { powerOff = false },
        title = { Text("Turn the stereo fully off?") },
        text = { Text("It will leave Wi-Fi, so this app can't turn it back on. Use the stereo's power button or its remote to switch it on again.\n\nTip: a short tap on Power puts it in standby, which the app can wake.") },
        confirmButton = {
            Button(onClick = { powerOff = false; vm.power("OFF") }, colors = ButtonDefaults.buttonColors(containerColor = C.Danger)) { Text("Turn off") }
        },
        dismissButton = { TextButton(onClick = { powerOff = false }) { Text("Cancel") } },
    )
}
