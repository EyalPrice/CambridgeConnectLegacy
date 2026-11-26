package io.github.eyalprice.ccl

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

enum class Tab(private val labelKey: String, private val titleKey: String) {
    PLAY("tab_playing", "tab_now_playing"), INPUTS("tab_inputs", "tab_inputs"), RADIO("tab_radio", "tab_radio"),
    MUSIC("tab_music", "tab_music");
    val label: String get() = tx(labelKey)
    val title: String get() = tx(titleKey)
}

enum class Page(val titleKey: String) { SOUND("page_sound"), SETTINGS("page_settings"), QUEUE("page_queue") }

class MainActivity : ComponentActivity() {

    private val vm: StereoViewModel get() = stereo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)   // also picks up a phone-language change
        window.statusBarColor = C.Bg.toArgb()
        window.navigationBarColor = C.Bar.toArgb()
        setContent { CclTheme { App(vm, versionName(), versionCode(), starts.intValue) } }
    }

    /** Counts each time the app comes to the front, so the update check runs again then. */
    private val starts = mutableIntStateOf(0)

    override fun onStart() { super.onStart(); vm.setForeground(true); starts.intValue++ }
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

/** Result of asking GitHub: newer = (tag, url) when there is a newer build; failed when GitHub couldn't be reached. */
private data class UpdateCheck(val newer: Pair<String, String>?, val failed: Boolean)

/** Asks GitHub for the newest release and compares its build number with this one. */
private suspend fun checkRelease(current: Long): UpdateCheck = withContext(Dispatchers.IO) {
    try {
        val c = URL("https://api.github.com/repos/EyalPrice/CambridgeConnectLegacy/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return@withContext UpdateCheck(null, true)
        val rel = JSONObject(c.inputStream.bufferedReader().readText())
        val tag = rel.optString("tag_name")
        val n = tag.substringAfterLast('.').toLongOrNull() ?: return@withContext UpdateCheck(null, true)
        val assets = rel.optJSONArray("assets")
        val url = if (assets != null && assets.length() > 0) assets.getJSONObject(0).optString("browser_download_url") else rel.optString("html_url")
        UpdateCheck(if (n > current) tag to url else null, false)
    } catch (e: Exception) { UpdateCheck(null, true) }
}

@Composable
fun App(vm: StereoViewModel, version: String, versionCode: Long, starts: Int) {
    val st by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.PLAY) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var powerOff by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Pair<String, String>?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var checkTick by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(starts, checkTick) {
        updateStatus = tx("checking")
        val r = checkRelease(versionCode)
        update = r.newer
        updateStatus = when {
            r.newer != null -> tx("update_new", r.newer.first)
            r.failed -> tx("update_failed")
            else -> tx("up_to_date")
        }
        if (checkTick > 0 && r.newer == null && !r.failed) vm.toast(tx("newest_version"))
    }

    // Lock-screen controls and locked volume keys need the media service; Android 13+ also asks for notifications.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(st.conn, st.standby) {
        if (st.conn == Conn.OK && !st.standby) {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                val p = vm.savedPrefs()
                if (!p.getBoolean("askedNotify", false)) {
                    p.edit().putBoolean("askedNotify", true).apply()
                    askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            StereoService.start(ctx)
        }
    }
    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.toast(null) } }
    BackHandler(enabled = page != null) { page = null }
    BackHandler(enabled = page == null && tab != Tab.PLAY) { tab = Tab.PLAY }

    if (st.name.isBlank()) {
        Box(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) { WelcomeScreen(st, vm) }
        return
    }

    Scaffold(containerColor = C.Bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TopBar(page?.let { tx(it.titleKey) } ?: tab.title, st,
                onSound = { page = Page.SOUND }, onSettings = { page = Page.SETTINGS },
                onPower = { vm.togglePower() }, onPowerHold = { powerOff = true })
            update?.let { (tag, url) ->
                Banner(tx("new_version", tag), tx("download")) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            when {
                st.conn == Conn.UNREACHABLE -> Banner(tx("banner_unreachable", st.name), tx("retry")) { vm.connect(vm.device?.location ?: "") }
                st.standby -> Banner(tx("banner_standby", st.name), tx("wake_up")) { vm.power("ON") }
            }
            if (page != null) {
                Row(Modifier.fillMaxWidth().clickable { page = null }.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Icon(Icons.Filled.ArrowBack, tx("back"), tint = C.Muted, modifier = Modifier.size(18.dp))
                    Text("  " + tx("back"), color = C.Muted, fontSize = 13.sp)
                }
            }
            Box(Modifier.weight(1f)) {
                when (page) {
                    Page.SOUND -> SoundScreen(st, vm)
                    Page.SETTINGS -> SettingsScreen(st, vm, version, updateStatus) { checkTick++ }
                    Page.QUEUE -> QueueScreen(st, vm)
                    null -> when (tab) {
                        Tab.PLAY -> NowPlayingScreen(st, vm, onInputs = { tab = Tab.INPUTS }, onQueue = { page = Page.QUEUE })
                        Tab.INPUTS -> InputsScreen(st, vm) { }
                        Tab.RADIO -> RadioScreen(st, vm) { }
                        Tab.MUSIC -> MusicScreen(vm, onPlaying = { tab = Tab.PLAY }, onQueue = { page = Page.QUEUE })
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
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = page == null && tab == t,
                        onClick = { page = null; tab = t },
                        icon = {
                            Icon(when (t) {
                                Tab.PLAY -> Icons.Outlined.PlayCircle
                                Tab.INPUTS -> Icons.Outlined.GridView
                                Tab.RADIO -> Icons.Outlined.Radio
                                Tab.MUSIC -> Icons.Outlined.LibraryMusic
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
        title = { Text(tx("poweroff_title")) },
        text = { Text(tx("poweroff_text")) },
        confirmButton = {
            Button(onClick = { powerOff = false; vm.power("OFF") }, colors = ButtonDefaults.buttonColors(containerColor = C.Danger)) { Text(tx("turn_off")) }
        },
        dismissButton = { TextButton(onClick = { powerOff = false }) { Text(tx("cancel")) } },
    )
}
