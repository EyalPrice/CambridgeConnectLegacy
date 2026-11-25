package io.github.eyalprice.ccl

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Conn { NONE, SEARCHING, CONNECTING, OK, UNREACHABLE }

data class Input(val number: Int, val name: String)

data class StereoState(
    val conn: Conn = Conn.NONE,
    val name: String = "",
    val model: String = "",
    val power: String = "",
    val sourceNumber: Int = -1,
    val volume: Int = 0,
    val volumeMax: Int = 40,
    val muted: Boolean = false,
    val transport: String = "",
    val playback: Reciva.Playback = Reciva.Playback(),
    val spotifyPos: Int = 0,
    val spotifyDur: Int = 0,
    val bass: Int? = null,
    val treble: Int? = null,
    val balance: Int? = null,
    val phaseInverted: Boolean? = null,
    val filter: String? = null,
    val inputs: List<Input> = emptyList(),
    val presetCount: Int = 0,
    val presets: List<Reciva.Preset> = emptyList(),
    val found: List<Upnp.Found> = emptyList(),
    /** Result line of the last network search, shown on the first-start screen. */
    val searchInfo: String = "",
    val pro: Boolean = false,
    val message: String? = null,
    val about: Map<String, String> = emptyMap(),
    val languages: List<Pair<String, String>> = emptyList(),
    val language: String = "",
    val brightness: Int = 2,
    /** Input numbers shown on the home-screen widget (user's choice; Radio, Spotify, TV by default). */
    val widgetInputs: List<Int> = listOf(0, 24, 6),
    /** True between pressing play/pause and the stereo confirming it (the button shows a spinner). */
    val playPending: Boolean = false,
    /** Slot the app last started (tap, next or back). Used to tell apart slots holding the same station. */
    val lastPreset: Int = 0,
    /** True from tapping a preset (or next/back on radio) until the stereo reports the new station. */
    val tuning: Boolean = false,
) {
    /** The preset now playing: the slot last started if its name still matches, otherwise the first slot with that name. */
    val playingPreset: Reciva.Preset?
        get() {
            if (!isRadio || playback.title.isBlank()) return null
            val same = presets.filter { it.title.isNotBlank() && it.title.equals(playback.title, true) }
            return same.firstOrNull { it.number == lastPreset } ?: same.firstOrNull()
        }
    val sourceName: String
        get() = inputs.firstOrNull { it.number == sourceNumber }?.name ?: Reciva.defaultInputName(sourceNumber)
    val isRadio get() = sourceNumber == 0
    val isSpotify get() = sourceNumber == 24 || sourceName.equals("Spotify", true)
    val isMedia get() = sourceName.contains("media", true) || sourceName.contains("library", true)
    /** Only these sources carry track details; others (TV, Bluetooth…) may report stale Spotify data. */
    val hasMetadata get() = isRadio || isSpotify || isMedia
    val standby get() = power == "IDLE" || power == "OFF"
}

/** Holds the connection to one stereo and keeps its state fresh by polling. */
private const val BALANCE_UNIT = 16
private const val BALANCE_STEPS = 16

class StereoViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ccl", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(StereoState(pro = prefs.getBoolean("pro", false)))
    val state: StateFlow<StereoState> = _state

    var device: Upnp.Device? = null
        private set
    var navId: String? = null
        private set

    private var pollJob: Job? = null
    private var foreground = true
    /** True while the lock-screen service runs; polling then continues slowly in the background. */
    @Volatile var serviceActive = false
    private var lastVolumeTouch = 0L
    /** Until this time, polling doesn't overwrite the play/pause state the user just chose. */
    @Volatile private var playHoldUntil = 0L
    private var pendingVolume: Int? = null
    private var volumeJob: Job? = null

    init {
        val loc = prefs.getString("location", null)
        if (loc != null) viewModelScope.launch(Dispatchers.IO) { if (!connectNow(loc)) search() } else search()
    }

    fun setForeground(fg: Boolean) {
        foreground = fg
        if (fg && device != null) startPolling()
    }

    fun toast(msg: String?) = _state.update { it.copy(message = msg) }

    fun setPro(on: Boolean) {
        prefs.edit().putBoolean("pro", on).apply()
        _state.update { it.copy(pro = on) }
    }

    // ------------------------------------------------------------ discovery & connect

    fun search(ip: String? = null) {
        _state.update { it.copy(conn = Conn.SEARCHING, found = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            var info: String
            val found = try {
                val list = Upnp.discover(getApplication<Application>(), ip, 5000)
                // Read each device's description in parallel so one slow device doesn't hold up the rest.
                list.map { f -> async { Upnp.peek(f) } }.forEach { it.await() }
                val cam = list.count { it.cambridge }
                info = if (list.isEmpty()) "No devices answered on this Wi-Fi."
                else "${list.size} device(s) answered, $cam look like Cambridge Audio."
                list.sortedByDescending { it.cambridge }
            } catch (e: Exception) {
                info = "Search failed: ${e.message}"
                emptyList()
            }
            _state.update { it.copy(conn = if (device == null) Conn.NONE else it.conn, found = found, searchInfo = info) }
        }
    }

    fun forget() {
        prefs.edit().remove("location").apply()
        pollJob?.cancel()
        device = null
        navId = null
        _state.update { StereoState(pro = it.pro) }
        search()
    }

    fun connect(location: String) {
        viewModelScope.launch(Dispatchers.IO) { connectNow(location) }
    }

    private suspend fun connectNow(location: String): Boolean = withContext(Dispatchers.IO) {
        _state.update { it.copy(conn = Conn.CONNECTING) }
        try {
            val d = Upnp.loadDevice(location)
            device = d
            prefs.edit().putString("location", location).apply()
            _state.update { it.copy(conn = Conn.OK, name = d.friendlyName, model = "${d.manufacturer} ${d.modelName}".trim()) }
            registerNavigator()
            loadStatic()
            refreshOnce()
            startPolling()
            true
        } catch (e: Exception) {
            _state.update { it.copy(conn = Conn.UNREACHABLE) }
            false
        }
    }

    /** Used by the widget and lock-screen controls when the app process was started cold. */
    suspend fun connectIfNeeded(): Boolean {
        if (device != null) return true
        val loc = prefs.getString("location", null) ?: return false
        return connectNow(loc)
    }

    fun savedPrefs() = prefs

    private fun service(action: String): Upnp.Service? = device?.services?.firstOrNull { it.action(action) != null }

    fun has(action: String) = service(action) != null

    /** Runs a command, filling InstanceID / Channel / NavigatorId automatically. */
    fun callBlocking(action: String, vararg args: Pair<String, String>, timeoutMs: Int = 8000): Upnp.Result? {
        val s = service(action) ?: return null
        val map = LinkedHashMap<String, String>()
        for (a in s.action(action).inArgs()) {
            val given = args.firstOrNull { it.first == a.name }?.second
            map[a.name] = given ?: when (a.name) {
                "InstanceID" -> "0"
                "Channel" -> "Master"
                "NavigatorId" -> navId ?: ""
                "Speed" -> "1"
                else -> ""
            }
        }
        val t0 = System.currentTimeMillis()
        return try {
            Upnp.call(s, action, map, timeoutMs).also { r ->
                log("$action $map → HTTP ${r.httpCode} (${System.currentTimeMillis() - t0} ms)\n" +
                    (if (r.ok()) r.out.entries.joinToString("\n") { "  ${it.key} = ${it.value.take(1500)}" } else "  " + r.body.take(800)))
            }
        } catch (e: Exception) {
            log("$action $map → ${e.javaClass.simpleName} after ${System.currentTimeMillis() - t0} ms")
            null
        }
    }

    // ------------------------------------------------------------ debug log

    private val debug = ArrayDeque<String>()

    private fun log(line: String) {
        if (line.startsWith("GetPowerState") || line.startsWith("GetVolume") || line.startsWith("GetMute") ||
            line.startsWith("GetTransportInfo") || line.startsWith("GetAudioSourceByNumber") ||
            line.startsWith("GetPlaybackDetails") || line.startsWith("GetSpotifyPlaybackPosition")) return // skip routine polling
        synchronized(debug) {
            debug.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()) + " " + line)
            while (debug.size > 120) debug.removeFirst()
        }
    }

    /** Recent commands and replies (routine status polling left out), with private details removed. */
    fun debugLog(): String {
        val body = synchronized(debug) { debug.joinToString("\n\n") }
        val d = device
        return StereoCheck.sanitize("Cambridge Connect Legacy debug log\nStereo: ${d?.manufacturer} ${d?.modelName}\n\n$body", d?.host() ?: "")
    }

    private fun out(action: String, vararg args: Pair<String, String>): String? =
        callBlocking(action, *args)?.takeIf { it.ok() }?.out?.values?.firstOrNull()

    fun send(action: String, vararg args: Pair<String, String>, after: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = callBlocking(action, *args)
            if (r == null || !r.ok()) toast("The stereo didn't accept “$action”.")
            after?.invoke()
            refreshOnce()
        }
    }

    /** The stereo forgets browsing sessions (for example after standby); register again when needed. */
    private fun ensureNavigator() {
        val id = navId
        if (id != null && callBlocking("IsRegisteredNavigatorId", "NavigatorId" to id)?.out?.get("IsRegistered") == "1") return
        registerNavigator()
    }

    private fun registerNavigator() {
        val name = prefs.getString("navName", null) ?: ("CCL-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString("navName", it).apply()
        }
        val existing = callBlocking("IsRegisteredNavigatorName", "NavigatorName" to name)
        navId = if (existing != null && existing.out["IsRegistered"] == "1") existing.out["RetNavigatorId"]
        else callBlocking("RegisterNamedNavigator", "NewNavigatorName" to name)?.out?.get("RetNavigatorId")
            ?: callBlocking("RegisterNavigator")?.out?.get("RetNavigatorId")
    }

    // ------------------------------------------------------------ static data

    fun loadStatic() {
        val numbers = out("GetAudioSourcesByNumber")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        val inputs = numbers.map { n ->
            val nm = out("GetAudioSourceName", "InAudioSource" to n.toString())
            Input(n, if (nm.isNullOrBlank() || nm == "(none)") Reciva.defaultInputName(n) else nm)
        }
        val max = out("GetVolumeMax")?.toIntOrNull() ?: 40
        val about = linkedMapOf<String, String>()
        out("GetCurrentServicePack")?.let { about["Firmware"] = it }
        out("GetHardwareConfig")?.let { about["Hardware"] = it }
        // Shown in full here (it stays on the phone); shared reports and logs still hide most of it.
        out("GetSerialNumber")?.let { about["Serial number"] = it }
        device?.let { about["Model"] = "${it.modelName} ${it.modelNumber}".trim() }
        val langs = callBlocking("GetDisplayLanguages")?.out
        val names = langs?.get("RetLanguageListValue")?.split(',') ?: emptyList()
        val codes = langs?.get("RetIsoCodeListValue")?.split(',') ?: emptyList()
        val language = out("GetCurrentDisplayLanguage") ?: ""
        _state.update {
            it.copy(inputs = ordered(inputs), volumeMax = max, about = about,
                languages = names.zip(codes), language = language,
                brightness = prefs.getInt("brightness", 2),
                widgetInputs = (prefs.getString("widgetInputs", null) ?: "0,24,6").split(',')
                    .mapNotNull { n -> n.trim().toIntOrNull() }.filter { n -> n >= 0 }.distinct().take(MAX_WIDGET))
        }
        loadPresets()
        loadSound()
    }

    fun loadPresets() {
        val count = out("GetNumberOfPresets")?.toIntOrNull() ?: 0
        val list = Reciva.presets(out("GetPresetList", "Start" to "1", "End" to count.coerceAtLeast(1).toString()))
        _state.update { it.copy(presetCount = count, presets = list) }
    }

    fun loadSound() {
        _state.update {
            it.copy(
                bass = out("GetToneCtrlBass")?.toIntOrNull(),
                treble = out("GetToneCtrlTreble")?.toIntOrNull(),
                balance = out("GetBalanceDB")?.toIntOrNull()?.let { raw ->
                    // Stereo reports 1/256 dB units; show it as steps, preferring the app's own value when they agree.
                    val saved = prefs.getInt("balance", 0)
                    val fromStereo = Math.round(raw.toFloat() / BALANCE_UNIT).coerceIn(-BALANCE_STEPS, BALANCE_STEPS)
                    if (Math.abs(saved * BALANCE_UNIT - raw) < 256) saved else fromStereo
                },
                phaseInverted = out("GetAudioPhaseInverted")?.let { v -> v == "1" || v.equals("true", true) },
                filter = out("GetAudioFilter"),
            )
        }
    }

    // ------------------------------------------------------------ polling

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                if (!foreground && !serviceActive) { delay(1000); continue }
                val ok = refreshOnce()
                failures = if (ok) 0 else failures + 1
                if (failures >= 2) _state.update { it.copy(conn = Conn.UNREACHABLE) }
                delay(if (!ok) 4000 else if (foreground) 1500 else 5000)
            }
        }
    }

    /** Reads the live status. Returns false when the stereo did not answer. */
    private suspend fun refreshOnce(): Boolean = withContext(Dispatchers.IO) {
        val power = out("GetPowerState") ?: return@withContext false
        val src = out("GetAudioSourceByNumber")?.toIntOrNull() ?: -1
        val volTouched = System.currentTimeMillis() - lastVolumeTouch < 2500
        val vol = if (volTouched) null else out("GetVolume")?.toIntOrNull()
        val mute = out("GetMute")
        val transport = out("GetTransportInfo") ?: ""
        val pb = Reciva.playback(out("GetPlaybackDetails"))
        var pos = 0
        var dur = 0
        if (src == 24) {
            callBlocking("GetSpotifyPlaybackPosition")?.out?.let {
                pos = it["Position"]?.toIntOrNull() ?: 0
                dur = it["Duration"]?.toIntOrNull() ?: 0
            }
        }
        val now = System.currentTimeMillis()
        val hold = now < playHoldUntil
        // After next/back on radio, keep showing the chosen station until the stereo reports it (no flicker).
        val stepTitle = pendingStepTitle
        val keepStep = stepTitle != null && now < pendingStepUntil && !pb.title.equals(stepTitle, true)
        if (stepTitle != null && !keepStep) pendingStepTitle = null
        _state.update {
            it.copy(conn = Conn.OK, power = power, sourceNumber = src,
                volume = vol ?: it.volume, muted = mute == "1" || mute.equals("true", true),
                tuning = keepStep,
                transport = if (hold || keepStep) it.transport else transport,
                playback = when {
                    keepStep -> it.playback
                    hold -> pb.copy(state = it.playback.state, spotifyPlaying = it.playback.spotifyPlaying)
                    else -> pb
                },
                spotifyPos = pos, spotifyDur = dur)
        }
        true
    }

    // ------------------------------------------------------------ controls

    fun setVolume(v: Int) {
        val target = v.coerceIn(0, _state.value.volumeMax)
        if (target == _state.value.volume && pendingVolume == null) return
        lastVolumeTouch = System.currentTimeMillis()
        _state.update { it.copy(volume = target) }
        pendingVolume = target
        if (volumeJob?.isActive == true) return
        volumeJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val t = pendingVolume ?: break
                pendingVolume = null
                callBlocking("SetVolume", "DesiredVolume" to t.toString())
                lastVolumeTouch = System.currentTimeMillis()
            }
        }
    }

    fun volumeStep(d: Int) = setVolume(_state.value.volume + d)

    fun toggleMute() {
        val m = !_state.value.muted
        _state.update { it.copy(muted = m) }
        send("SetMute", "DesiredMute" to if (m) "1" else "0")
    }

    fun power(target: String) {
        _state.update { it.copy(power = target) }
        send("SetPowerState", "NewPowerStateValue" to target)
    }

    fun togglePower() = power(if (_state.value.standby) "ON" else "IDLE")

    fun selectInput(n: Int) {
        _state.update { it.copy(sourceNumber = n) }
        if (_state.value.standby) callAsync { callBlocking("SetPowerState", "NewPowerStateValue" to "ON") }
        send("SetAudioSourceByNumber", "NewAudioSourceValue" to n.toString())
    }

    fun renameInput(n: Int, name: String) =
        send("SetAudioSourceName", "InAudioSource" to n.toString(), "InAudioSourceName" to name) {
            _state.update { s -> s.copy(inputs = s.inputs.map { if (it.number == n) it.copy(name = name) else it }) }
        }

    /** Plays a preset and remembers its slot, so next/back continue from the right place even when two slots hold the same station. */
    fun playPreset(n: Int) {
        lastStepPreset = n
        // Show the new station's name at once, with a spinner, until the stereo has tuned in.
        val title = _state.value.presets.firstOrNull { it.number == n }?.title.orEmpty()
        if (title.isNotBlank()) {
            pendingStepTitle = title
            pendingStepUntil = System.currentTimeMillis() + 15000
            _state.update { it.copy(lastPreset = n, tuning = true, transport = "PLAYING",
                playback = it.playback.copy(title = title, state = "Playing", artist = "", artUrl = "", format = "")) }
        } else _state.update { it.copy(lastPreset = n) }
        send("PlayPreset", "NewPresetNumberValue" to n.toString())
    }

    /** Saves the station that is playing now into preset [n] and tells the user whether it worked. */
    fun saveCurrentAsPreset(n: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = Reciva.playback(out("GetPlaybackDetails")).title.ifBlank { _state.value.playback.title }
            toast("Saving “${title.ifBlank { "current station" }}” to preset $n…")
            val ok = savePlayingTo(n, title)
            toast(if (ok) "Saved “${presetTitleCached(n) ?: title}” to preset $n."
                  else "The stereo didn't save to preset $n. Try another slot, or share the debug log (Pro › Troubleshooting).")
            refreshOnce()
        }
    }

    private fun presetTitleCached(n: Int) = _state.value.presets.firstOrNull { it.number == n }?.title

    /**
     * Stores what is playing into slot [n] and checks the slot afterwards. Some firmware refuses
     * the usual save command for certain slots, so if the slot didn't change, the station is
     * stored directly by its station id instead.
     */
    private suspend fun savePlayingTo(n: Int, title0: String): Boolean {
        val title = title0.ifBlank { _state.value.playback.title }
        val before = presetTitle(n)
        log("Save check: slot $n before='${before ?: ""}', playing='$title'")
        // Success = the slot now holds the station that is playing. If the name of what's playing
        // can't be read, any station in the slot counts (it may already have been the same one).
        fun saved(): Boolean {
            val now = presetTitle(n)
            val ok = if (title.isNotBlank()) matches(now, title) else !now.isNullOrBlank()
            log("Save check: slot $n now='${now ?: ""}' → ${if (ok) "saved" else "not saved"}")
            return ok
        }
        if (title.isNotBlank() && matches(before, title)) return true // already there
        ensureNavigator()
        callBlocking("SaveCurrentStationAsPreset", "NavigatorId" to (navId ?: ""), "PresetNumber" to n.toString(), timeoutMs = 20000)
        delay(800)
        if (saved()) return true
        val ids = callBlocking("GetStationId")?.out
        val stationId = ids?.get("RetStationIdValue")
        val menuId = ids?.get("RetMenuIdValue") ?: "0"
        if (!stationId.isNullOrBlank() && stationId != "-1") {
            for (m in listOf(menuId, "0", "-1").distinct()) {
                callBlocking("SetPreset", "NewPresetNumberValue" to n.toString(),
                    "NewPresetStationIdValue" to stationId, "NewPresetMenuIdValue" to m, timeoutMs = 20000)
                delay(600)
                if (saved()) return true
            }
        }
        // Last try: clear the slot, then save again (some units won't overwrite a slot in an odd state).
        callBlocking("DeletePreset", "PresetNumber" to n.toString())
        delay(500)
        callBlocking("SaveCurrentStationAsPreset", "NavigatorId" to (navId ?: ""), "PresetNumber" to n.toString(), timeoutMs = 20000)
        delay(800)
        return saved()
    }

    fun deletePreset(n: Int) = send("DeletePreset", "PresetNumber" to n.toString()) { loadPresets() }

    /** Last preset started via next/back, so repeated presses keep stepping even before the title updates. */
    @Volatile private var lastStepPreset = 0
    @Volatile private var pendingStepTitle: String? = null
    @Volatile private var pendingStepUntil = 0L

    fun transport(action: String) {
        val st = _state.value
        if (st.isRadio && (action == "Next" || action == "Previous")) { stepPreset(if (action == "Next") 1 else -1); return }
        send(action)
    }

    /** Internet radio has no next/back, so step through the saved presets, skipping empty slots and wrapping around. */
    private fun stepPreset(dir: Int) {
        val st = _state.value
        val saved = st.presets.filter { it.title.isNotBlank() }.map { it.number }.sorted()
        if (saved.isEmpty()) return
        // Prefer the slot we last jumped to, so two slots holding the same station don't trap next/back.
        val titleOf = { n: Int -> st.presets.firstOrNull { it.number == n }?.title ?: "" }
        val last = lastStepPreset.takeIf { it in saved && titleOf(it).equals(st.playback.title, true) }
        val byTitle = st.presets.firstOrNull { it.title.isNotBlank() && it.title.equals(st.playback.title, true) }?.number
        val cur = last ?: byTitle ?: lastStepPreset.takeIf { it in saved }
        val next = if (cur == null) { if (dir > 0) saved.first() else saved.last() }
            else { val i = saved.indexOf(cur); saved[(i + dir + saved.size) % saved.size] }
        lastStepPreset = next
        playPreset(next)
    }

    fun play() = send("Play", "Speed" to "1")

    fun isPlaying(st: StereoState = _state.value) =
        st.transport == "PLAYING" || (st.isSpotify && st.playback.spotifyPlaying) ||
            (!st.isSpotify && st.playback.state.equals("Playing", true))

    /**
     * Play/pause used by the app, the widget and the lock screen. Internet radio on some firmware
     * ignores Pause, so if it is still playing a moment later, Stop is sent; and if Play doesn't
     * restart a stopped radio station, its preset is played again.
     */
    fun togglePlay() {
        val st = _state.value
        val wasPlaying = isPlaying(st)
        // Show the new state straight away; the stereo confirms a moment later.
        playHoldUntil = System.currentTimeMillis() + 6000
        _state.update {
            it.copy(playPending = true,
                transport = if (wasPlaying) "PAUSED_PLAYBACK" else "PLAYING",
                playback = it.playback.copy(state = if (wasPlaying) "Paused" else "Playing", spotifyPlaying = !wasPlaying))
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (wasPlaying) {
                    callBlocking("Pause")
                    if (confirmed(false)) return@launch
                    callBlocking("Stop")
                    confirmed(false)
                } else {
                    val preset = st.presets.firstOrNull { it.title.isNotBlank() && it.title == st.playback.title }
                    callBlocking("Play", "Speed" to "1")
                    if (confirmed(true)) return@launch
                    if (st.isRadio && preset != null) callBlocking("PlayPreset", "NewPresetNumberValue" to preset.number.toString())
                    confirmed(true)
                }
            } finally {
                playHoldUntil = 0
                _state.update { it.copy(playPending = false) }
                refreshOnce()
            }
        }
    }

    /** Polls quickly (up to ~2 s) until the stereo reports the wanted play state. */
    private suspend fun confirmed(wantPlaying: Boolean): Boolean {
        repeat(5) {
            delay(400)
            val transport = out("GetTransportInfo") ?: ""
            val pb = Reciva.playback(out("GetPlaybackDetails"))
            val st = _state.value
            val playing = transport == "PLAYING" || (st.isSpotify && pb.spotifyPlaying) ||
                (!st.isSpotify && pb.state.equals("Playing", true))
            if (playing == wantPlaying) return true
        }
        return false
    }

    // ------------------------------------------------------------ input order and widget buttons (saved on the phone)

    /** Applies the user's saved tile order; inputs not in it keep the stereo's order at the end. */
    private fun ordered(inputs: List<Input>): List<Input> {
        val order = (prefs.getString("inputOrder", null) ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
        val known = order.mapNotNull { n -> inputs.firstOrNull { it.number == n } }
        return known + inputs.filter { it !in known }
    }

    /** Moves a tile one place earlier (dir = -1) or later (dir = +1) and saves the order. */
    fun moveInput(number: Int, dir: Int) {
        val list = _state.value.inputs.toMutableList()
        val i = list.indexOfFirst { it.number == number }
        val j = i + dir
        if (i < 0 || j !in list.indices) return
        java.util.Collections.swap(list, i, j)
        prefs.edit().putString("inputOrder", list.joinToString(",") { it.number.toString() }).apply()
        _state.update { it.copy(inputs = list) }
    }

    /** Adds an input to the widget; when it already has [MAX_WIDGET] buttons, [replace] says which one to take off. */
    fun addToWidget(number: Int, replace: Int? = null) {
        val list = _state.value.widgetInputs.filter { it != replace && it != number }.toMutableList()
        if (list.size >= MAX_WIDGET) return
        list.add(number)
        saveWidget(list)
    }

    fun removeFromWidget(number: Int) = saveWidget(_state.value.widgetInputs.filter { it != number })

    private fun saveWidget(list: List<Int>) {
        prefs.edit().putString("widgetInputs", list.joinToString(",")).apply()
        _state.update { it.copy(widgetInputs = list) }
    }

    fun setTone(which: String, value: Int) {
        if (which == "bass") _state.update { it.copy(bass = value) } else _state.update { it.copy(treble = value) }
        val action = if (which == "bass") "SetToneCtrlBass" else "SetToneCtrlTreble"
        val arg = if (which == "bass") "NewToneCtrlBassValue" else "NewToneCtrlTrebleValue"
        send(action, arg to value.toString()) { loadSound() }
    }

    /**
     * Balance is sent in 1/256 dB units. One step is 16 units (1/16 dB) and it stops at 16 steps
     * either side, so it moves gradually. The stereo rounds what it reports back, so the app keeps
     * its own value instead of reading it back after each press.
     */
    fun balanceStep(dir: Int) {
        val cur = _state.value.balance ?: 0
        val next = (cur + dir).coerceIn(-BALANCE_STEPS, BALANCE_STEPS)
        if (next == cur) return
        setBalance(next)
    }

    fun setBalance(steps: Int) {
        _state.update { it.copy(balance = steps) }
        prefs.edit().putInt("balance", steps).apply()
        send("SetBalanceDB", "DesiredBalanceDB" to (steps * BALANCE_UNIT).toString())
    }

    fun resetTone() {
        setTone("bass", 0)
        setTone("treble", 0)
        if (_state.value.balance != null) setBalance(0)
    }

    fun setPhase(on: Boolean) {
        _state.update { it.copy(phaseInverted = on) }
        send("SetAudioPhaseInverted", "NewAudioPhaseInverted" to if (on) "1" else "0") { loadSound() }
    }

    fun setFilter(f: String) = send("SetAudioFilter", "NewAudioFilter" to f) { loadSound() }

    fun setBrightness(level: Int) {
        prefs.edit().putInt("brightness", level).apply()
        _state.update { it.copy(brightness = level) }
        send("SetBrightnessLevel", "NewBrightnessLevel" to level.toString())
    }

    fun setLanguage(code: String) = send("SetCurrentDisplayLanguage", "NewIsoCodeValue" to code) {
        _state.update { s -> s.copy(language = s.languages.firstOrNull { it.second == code }?.first ?: s.language) }
    }

    fun setShuffle(on: Boolean) = send("SetShuffle", "aShuffle" to if (on) "1" else "0")
    fun setRepeat(on: Boolean) = send("SetRepeat", "aRepeat" to if (on) "1" else "0")

    private fun callAsync(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    // ------------------------------------------------------------ menus, search, queue

    suspend fun menuTop(): Reciva.Menu? = withContext(Dispatchers.IO) {
        ensureNavigator()
        callBlocking("GoTopAndGetResponse", timeoutMs = 15000)
        currentMenu() ?: run {
            registerNavigator()
            callBlocking("GoTopAndGetResponse", timeoutMs = 15000)
            currentMenu()
        }
    }

    suspend fun currentMenu(): Reciva.Menu? = withContext(Dispatchers.IO) {
        val r = callBlocking("GetMenuAtOffset", "Count" to "50", "Offset" to "0", timeoutMs = 15000)
        Reciva.menu(r?.out?.get("RetMenuXML")) ?: Reciva.menu(callBlocking("GetMenu", timeoutMs = 15000)?.out?.get("RetMenuXML"))
    }

    private fun same(a: Reciva.Menu?, b: Reciva.Menu?) = a != null && b != null && a.id == b.id && a.items == b.items

    /**
     * Selects an item. Returns the new menu, or null when the item started playback.
     * Internet radio menus load from the internet, so the new menu can take a few seconds to appear.
     */
    suspend fun menuSelect(item: Reciva.MenuItem): Reciva.Menu? = withContext(Dispatchers.IO) {
        ensureNavigator()
        val before = currentMenu()
        callBlocking("SelectItemAndGetResponse", "NewMenuItemId" to item.id.toString(), timeoutMs = 30000)
        var after = currentMenu()
        var tries = 0
        while ((after == null || same(before, after) || (after.items.isEmpty() && after.count > 0)) && tries < 12) {
            delay(700)
            after = currentMenu()
            tries++
        }
        if (after == null || same(before, after)) null else after
    }

    suspend fun menuBack(): Reciva.Menu? = withContext(Dispatchers.IO) {
        ensureNavigator()
        callBlocking("GoBackAndGetResponse", timeoutMs = 15000)
        currentMenu()
    }

    data class SearchResult(val stations: List<Reciva.Station>, val raw: String, val error: String?)
    data class Choice(val id: String, val name: String)

    private var genreCache: List<Choice>? = null
    private var locationCache: List<Choice>? = null

    suspend fun genres(): List<Choice> = withContext(Dispatchers.IO) {
        genreCache ?: Reciva.idNames(callBlocking("GetGenreList", timeoutMs = 45000)?.out?.get("GenreXML"), "genre")
            .map { Choice(it.first, it.second) }.also { if (it.isNotEmpty()) genreCache = it }
    }

    suspend fun locations(): List<Choice> = withContext(Dispatchers.IO) {
        locationCache ?: Reciva.idNames(callBlocking("GetLocationList", timeoutMs = 30000)?.out?.get("LocationXML"), "location")
            .map { Choice(it.first, it.second) }.also { if (it.isNotEmpty()) locationCache = it }
    }

    /**
     * Station search. Uses the stereo's filtered search (name, country, genre) like the original
     * Cambridge Connect app; the firmware's expected "no filter" value isn't documented, so a few
     * spellings are tried and the one that works is remembered. Falls back to search-by-name.
     */
    suspend fun searchStations(query: String, locationId: String?, genreId: String?): SearchResult = withContext(Dispatchers.IO) {
        if (has("Search")) {
            val saved = prefs.getInt("searchVariant", -1)
            val variants = listOf("" to "0", "-1" to "0", "-1" to "-1", "" to "", "0" to "0")
            val order = if (saved in variants.indices) listOf(saved) + variants.indices.filter { it != saved } else variants.indices.toList()
            for (i in order) {
                val (none, rate) = variants[i]
                val r = callBlocking("Search", "NameFilter" to query, "CodecFilter" to "",
                    "LocationIDFilter" to (locationId ?: none), "GenreIDFilter" to (genreId ?: none),
                    "MinBitrateFilter" to rate, timeoutMs = 45000) ?: continue
                if (r.ok()) {
                    prefs.edit().putInt("searchVariant", i).apply()
                    val raw = r.out["ResultXML"] ?: ""
                    return@withContext SearchResult(Reciva.stations(raw), raw, null)
                }
            }
        }
        if (query.isBlank()) return@withContext SearchResult(emptyList(), "", "Type part of a station name, or pick a country or genre.")
        val r = callBlocking("SearchForStationByName", "StationName" to query, timeoutMs = 45000)
            ?: return@withContext SearchResult(emptyList(), "", "The stereo didn't answer in time. Try again.")
        if (!r.ok()) return@withContext SearchResult(emptyList(), r.body, "Search isn't available right now.")
        val raw = r.out["ResultXML"] ?: ""
        SearchResult(Reciva.stations(raw), raw, null)
    }

    fun playStation(id: String) = send("SetStationId", "NewStationIdValue" to id)

    private fun presetTitle(n: Int): String? {
        loadPresets()
        return _state.value.presets.firstOrNull { it.number == n }?.title
    }

    private fun matches(a: String?, b: String): Boolean {
        if (a.isNullOrBlank()) return false
        val x = a.lowercase().filter { it.isLetterOrDigit() }
        val y = b.lowercase().filter { it.isLetterOrDigit() }
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    /**
     * Saves a search result into a preset slot. First tries to store it directly by its station id;
     * if that doesn't stick, plays the station, waits until it is really playing and saves that.
     * The slot is read back afterwards so the user is told whether it worked.
     */
    fun saveStationToSlot(station: Reciva.Station, n: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            toast("Saving “${station.name}” to preset $n…")
            val before = presetTitle(n)
            for (menuId in listOf("0", "")) {
                val r = callBlocking("SetPreset", "NewPresetNumberValue" to n.toString(),
                    "NewPresetStationIdValue" to station.id, "NewPresetMenuIdValue" to menuId, timeoutMs = 20000)
                if (r != null && r.ok()) {
                    val now = presetTitle(n)
                    if (matches(now, station.name) || (now != null && now != before && now.isNotBlank())) {
                        toast("Saved “${now}” to preset $n.")
                        return@launch
                    }
                }
            }
            // Fall back: play it, wait for it to start, then save what is playing.
            callBlocking("SetStationId", "NewStationIdValue" to station.id, timeoutMs = 20000)
            var playing = false
            for (i in 0 until 25) {
                delay(1000)
                val pb = Reciva.playback(out("GetPlaybackDetails"))
                if (pb.state.equals("Playing", true) && matches(pb.title, station.name)) { playing = true; break }
            }
            if (!playing) {
                toast("“${station.name}” didn't start playing, so it wasn't saved. Try Play first, then save it from Presets.")
                return@launch
            }
            val playingTitle = Reciva.playback(out("GetPlaybackDetails")).title.ifBlank { station.name }
            if (savePlayingTo(n, playingTitle)) toast("Saved “${presetTitleCached(n) ?: station.name}” to preset $n.")
            else toast("The stereo didn't save “${station.name}” to preset $n. Try another slot, or share the debug log (Pro › Troubleshooting).")
            refreshOnce()
        }
    }

    suspend fun queue(): List<Reciva.Track> = withContext(Dispatchers.IO) {
        Reciva.tracks(out("GetPlaylistTrackDetails", "StartTrackID" to "0", "TrackCount" to "100"))
    }

    fun queueDelete(id: Int) = send("DeletePlaylistTrack", "PlaylistTrackID" to id.toString())
    fun queueJump(id: Int) = send("SetCurrentPlaylistTrack", "CurrentPlaylistTrackID" to id.toString())

    // ------------------------------------------------------------ virtual remote

    private val keyAliases = mapOf(
        "SELECT" to listOf("SELECT", "OK", "ENTER"), "BACK" to listOf("BACK", "RETURN"),
        "PREV" to listOf("PREVIOUS", "PREV"), "VOLUP" to listOf("VOLUME_UP", "VOLUP", "VOL_UP"),
        "VOLDOWN" to listOf("VOLUME_DOWN", "VOLDOWN", "VOL_DOWN"), "PLAY" to listOf("PLAY", "PLAY_PAUSE", "PLAYPAUSE"),
    )

    /**
     * Sends a handset key. The firmware's key names aren't documented, so a few spellings are tried
     * and the first one the stereo accepts is remembered.
     */
    suspend fun pressKey(key: String): String = withContext(Dispatchers.IO) {
        if (!has("KeyPressed")) return@withContext "This stereo has no remote-key command."
        prefs.getString("key_$key", null)?.split('|')?.takeIf { it.size == 2 }?.let { (name, dur) ->
            val r = callBlocking("KeyPressed", "Key" to name, "Duration" to dur)
            if (r != null && r.ok()) return@withContext "Sent $key"
        }
        val names = (keyAliases[key] ?: listOf(key)).flatMap { listOf(it, "KEY_$it", it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
        for (name in names) for (dur in listOf("SHORT", "0", "100")) {
            val r = callBlocking("KeyPressed", "Key" to name, "Duration" to dur)
            if (r != null && r.ok()) {
                prefs.edit().putString("key_$key", "$name|$dur").apply()
                return@withContext "Sent $key (as “$name”)"
            }
        }
        "The stereo didn't accept the $key key."
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

/** Most input buttons the home-screen widget shows. */
const val MAX_WIDGET = 4
