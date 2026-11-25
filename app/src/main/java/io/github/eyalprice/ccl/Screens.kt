package io.github.eyalprice.ccl

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ====================================================================== Now Playing

private fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

@Composable
fun NowPlayingScreen(st: StereoState, vm: StereoViewModel, onInputs: () -> Unit, onQueue: () -> Unit) {
    val pb = st.playback
    val title: String
    val subtitle: String
    val label: String
    when {
        st.isSpotify -> {
            title = pb.title.ifBlank { "Spotify" }
            subtitle = listOf(pb.artist, pb.album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Spotify Connect" }
            label = pb.spotifySource.ifBlank { "SPOTIFY" }
        }
        st.hasMetadata -> {
            title = pb.title.ifBlank { st.sourceName }
            subtitle = pb.format.ifBlank { st.sourceName }
            val preset = st.presets.firstOrNull { it.title.isNotBlank() && it.title == pb.title }
            label = if (preset != null) "PRESET ${preset.number}" else st.sourceName.uppercase()
        }
        else -> {
            title = st.sourceName
            subtitle = "Playing from ${st.sourceName}"
            label = "INPUT"
        }
    }
    val playing = st.transport == "PLAYING" || (st.isSpotify && pb.spotifyPlaying) || pb.state.equals("Playing", true)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onInputs, label = { Text(st.sourceName) },
                leadingIcon = { Icon(Icons.Outlined.Input, null, tint = C.Accent, modifier = Modifier.size(16.dp)) },
                trailingIcon = { Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(16.dp)) })
            if (st.isRadio && pb.format.isNotBlank()) {
                AssistChip(onClick = {}, enabled = false, label = { Text(pb.format, fontSize = 12.sp) })
            }
        }
        Panel(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtBox(if (st.hasMetadata) pb.artUrl else "", if (st.isSpotify) 96.dp else 72.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, color = C.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp, maxLines = 1)
                    Text(title, color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = C.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (st.isSpotify && st.spotifyDur > 0) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                    LinearProgressIndicator(progress = { st.spotifyPos.toFloat() / st.spotifyDur }, color = C.Accent,
                        trackColor = C.Line, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(fmt(st.spotifyPos), color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        if (pb.shuffle) Text("Shuffle  ", color = C.Accent, fontSize = 11.sp)
                        if (pb.repeat) Text("Repeat  ", color = C.Accent, fontSize = 11.sp)
                        Text(fmt(st.spotifyDur), color = C.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.transport("Previous") }) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = C.Text) }
            CircleIconButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play",
                { if (playing) vm.transport("Pause") else vm.play() }, filled = true, size = 60.dp)
            IconButton(onClick = { vm.transport("Next") }) { Icon(Icons.Filled.SkipNext, "Next", tint = C.Text) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleIconButton(Icons.Filled.Remove, "Volume down", { vm.volumeStep(-1) }, size = 52.dp)
            VolumeKnob(st.volume, st.volumeMax, st.muted, onChange = { vm.setVolume(it) })
            CircleIconButton(Icons.Filled.Add, "Volume up", { vm.volumeStep(1) }, size = 52.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(selected = st.muted, onClick = { vm.toggleMute() }, label = { Text(if (st.muted) "Unmute" else "Mute") })
            FilterChip(selected = false, onClick = onQueue, label = { Text("Queue") })
        }
        Text("Turn the knob with your thumb · tap − / + for one step · phone volume keys work too",
            color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
    }
}

// ====================================================================== Inputs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InputsScreen(st: StereoState, vm: StereoViewModel, onPicked: () -> Unit) {
    var rename by remember { mutableStateOf<Input?>(null) }
    if (st.inputs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Reading inputs…", color = C.Muted) }
        return
    }
    LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(20.dp, 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(st.inputs) { inp ->
            val on = inp.number == st.sourceNumber
            Column(
                Modifier.height(112.dp).clip(RoundedCornerShape(18.dp)).background(if (on) C.AccentBg else C.Surface)
                    .border(if (on) 1.5.dp else 1.dp, if (on) C.Accent else C.Line, RoundedCornerShape(18.dp))
                    .combinedClickable(onClick = { vm.selectInput(inp.number); onPicked() }, onLongClick = { rename = inp })
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Accent else C.Line),
                        contentAlignment = Alignment.Center) {
                        Icon(inputIcon(inp), null, tint = if (on) C.Bg else C.Text, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (on) Text("ACTIVE", color = C.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                }
                Column {
                    Text(inp.name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("Input ${inp.number}", color = C.Muted, fontSize = 12.sp)
                }
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Text("Long-press a tile to rename it. Names are saved on the stereo.", color = C.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
    rename?.let { inp ->
        var name by remember(inp) { mutableStateOf(inp.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename input ${inp.number}") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameInput(inp.number, name.trim()); rename = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
}

private fun inputIcon(i: Input) = when {
    i.number == 0 || i.name.contains("radio", true) -> Icons.Outlined.Radio
    i.number == 24 || i.name.contains("spotify", true) -> Icons.Outlined.MusicNote
    i.number == 14 || i.name.contains("bluetooth", true) -> Icons.Outlined.Bluetooth
    i.name.contains("tv", true) -> Icons.Outlined.Tv
    i.name.contains("media", true) || i.name.contains("library", true) -> Icons.Outlined.LibraryMusic
    i.name.contains("usb", true) -> Icons.Outlined.Usb
    i.name.contains("ipod", true) -> Icons.Outlined.PhoneIphone
    else -> Icons.Outlined.SettingsInputComponent
}

// ====================================================================== Radio

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioScreen(st: StereoState, vm: StereoViewModel, onPlaying: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Segmented(listOf("Presets", "Search", "Browse"), tab, { tab = it }, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        when (tab) {
            0 -> PresetList(st, vm, onPlaying)
            1 -> StationSearch(st, vm, onPlaying)
            else -> MenuBrowser(vm, rootIcon = "AIRABLE", rootName = "Radio", onPlaying = onPlaying)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetList(st: StereoState, vm: StereoViewModel, onPlaying: () -> Unit) {
    var options by remember { mutableStateOf<Int?>(null) }
    val count = st.presetCount.coerceAtLeast(st.presets.maxOfOrNull { it.number } ?: 0)
    if (count == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Reading presets…", color = C.Muted) }
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        items((1..count).toList()) { n ->
            val p = st.presets.firstOrNull { it.number == n }
            val on = p != null && st.isRadio && p.title == st.playback.title
            Row(
                Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)).background(if (on) C.AccentBg else Color.Transparent)
                    .combinedClickable(onClick = { if (p != null) { vm.playPreset(n); onPlaying() } else options = n },
                        onLongClick = { options = n })
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (on) C.Accent else C.Surface2), contentAlignment = Alignment.Center) {
                    Text("$n", color = if (on) C.Bg else C.Text, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p?.title ?: "Empty", color = if (p == null) C.Muted else C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (p == null) "Hold to save the current station here" else if (on) "Playing now" else p.description.ifBlank { "Tap to play · hold for options" },
                        color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (on) Icon(Icons.Filled.GraphicEq, "Playing", tint = C.Accent)
            }
        }
    }
    options?.let { n ->
        val p = st.presets.firstOrNull { it.number == n }
        var confirmDelete by remember(n) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { options = null }, title = { Text("Preset $n" + (p?.let { " · ${it.title}" } ?: "")) },
            text = {
                Column {
                    if (st.isRadio) TextButton(onClick = { vm.saveCurrentAsPreset(n); options = null }) {
                        Text(if (p == null) "Save “${st.playback.title.ifBlank { "current station" }}” here" else "Replace with “${st.playback.title.ifBlank { "current station" }}”")
                    } else Text("Switch to Internet radio and play a station to save it here.", color = C.Muted)
                    if (p != null) {
                        if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("Delete preset", color = C.Danger) }
                        else TextButton(onClick = { vm.deletePreset(n); options = null }) { Text("Tap again to delete “${p.title}”", color = C.Danger) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { options = null }) { Text("Close") } })
    }
}

@Composable
private fun StationSearch(st: StereoState, vm: StereoViewModel, onPlaying: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<StereoViewModel.SearchResult?>(null) }
    var saveFor by remember { mutableStateOf<Reciva.Station?>(null) }
    fun go() {
        if (q.isBlank() || busy) return
        busy = true
        scope.launch { result = vm.searchStations(q.trim()); busy = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(q, { q = it }, label = { Text("Station name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }),
            trailingIcon = { IconButton(onClick = { go() }) { Icon(Icons.Filled.Search, "Search") } })
        if (busy) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp)
            Text("  Searching… the stereo can take up to 30 seconds.", color = C.Muted, fontSize = 13.sp)
        }
        val r = result
        when {
            r == null -> {}
            r.error != null -> Text(r.error, color = C.Muted, modifier = Modifier.padding(top = 16.dp))
            r.stations.isEmpty() && r.raw.isNotBlank() -> Text("The stereo answered in a format this version doesn't read yet. Run Pro › Stereo check and share the report so it can be added.",
                color = C.Muted, modifier = Modifier.padding(top = 16.dp))
            r.stations.isEmpty() -> Text("No stations found.", color = C.Muted, modifier = Modifier.padding(top = 16.dp))
            else -> LazyColumn(Modifier.padding(top = 8.dp)) {
                items(r.stations) { s ->
                    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircleIconButton(Icons.Filled.PlayArrow, "Play ${s.name}", { vm.playStation(s.id); onPlaying() }, size = 40.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(s.name, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.detail.isNotBlank()) Text(s.detail, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(onClick = { saveFor = s }) { Text("Save", color = C.Accent) }
                    }
                }
            }
        }
    }
    saveFor?.let { s ->
        SlotPicker(st, "Save “${s.name}”", onPick = { n ->
            saveFor = null
            vm.playStation(s.id)
            scope.launch { delay(4000); vm.saveCurrentAsPreset(n) }
        }, onDismiss = { saveFor = null })
    }
}

@Composable
private fun SlotPicker(st: StereoState, title: String, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val count = st.presetCount.coerceAtLeast(1)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                Text("Choose a slot. Amber slots are empty; a used slot is replaced.", color = C.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                (1..count).chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        row.forEach { n ->
                            val empty = st.presets.none { it.number == n }
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2)
                                .border(1.dp, if (empty) C.Accent else C.Line2, RoundedCornerShape(10.dp)).clickable { onPick(n) },
                                contentAlignment = Alignment.Center) { Text("$n", color = C.Text, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ====================================================================== Menu browser (radio & music)

@Composable
fun MenuBrowser(vm: StereoViewModel, rootIcon: String, rootName: String, onPlaying: () -> Unit) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<Reciva.Menu?>(null) }
    var depth by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rootIcon) {
        busy = true
        val top = vm.menuTop()
        val root = top?.items?.firstOrNull { it.icon == rootIcon } ?: top?.items?.firstOrNull { it.name.contains(rootName, true) }
        menu = if (root != null) vm.menuSelect(root) ?: vm.currentMenu() else top
        error = if (menu == null) "The stereo's menu didn't load. Is it switched on?" else null
        busy = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (depth > 0) IconButton(onClick = {
                busy = true
                scope.launch { menu = vm.menuBack(); depth--; busy = false }
            }) { Icon(Icons.Filled.ArrowBack, "Back", tint = C.Text) }
            Text(menu?.name ?: rootName, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = if (depth > 0) 0.dp else 8.dp).weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = C.Accent, strokeWidth = 2.dp)
        }
        error?.let { Text(it, color = C.Muted, modifier = Modifier.padding(20.dp)) }
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(menu?.items ?: emptyList()) { item ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !busy) {
                        busy = true
                        scope.launch {
                            val next = vm.menuSelect(item)
                            if (next == null) { onPlaying() } else { menu = next; depth++ }
                            busy = false
                        }
                    }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (item.type == "menu") Icons.Outlined.Folder else Icons.Outlined.PlayCircle, null, tint = C.Muted)
                    Text(item.name, color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp).weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.type == "menu") Text("›", color = C.Muted)
                }
            }
        }
    }
}

@Composable
fun MusicScreen(vm: StereoViewModel, onPlaying: () -> Unit, onQueue: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Music on your home network (computer or network drive).", color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onQueue) { Text("Queue", color = C.Accent) }
        }
        MenuBrowser(vm, rootIcon = "MEDIALIBRARY", rootName = "Music", onPlaying = onPlaying)
    }
}

@Composable
fun QueueScreen(st: StereoState, vm: StereoViewModel) {
    var tracks by remember { mutableStateOf<List<Reciva.Track>?>(null) }
    var undo by remember { mutableStateOf<Reciva.Track?>(null) }
    LaunchedEffect(Unit) { tracks = vm.queue() }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (st.isSpotify) Text("Spotify's queue is managed in the Spotify app.", color = C.Muted, modifier = Modifier.padding(8.dp))
        val t = tracks
        when {
            t == null -> Text("Reading queue…", color = C.Muted, modifier = Modifier.padding(8.dp))
            t.isEmpty() -> Text("The queue is empty. Add music from the Music tab.", color = C.Muted, modifier = Modifier.padding(8.dp))
            else -> LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(t) { i, tr ->
                    Row(Modifier.fillMaxWidth().height(60.dp).clickable { vm.queueJump(tr.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = C.Muted, modifier = Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr.title, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (tr.artist.isNotBlank()) Text(tr.artist, color = C.Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = {
                            vm.queueDelete(tr.id); tracks = t - tr; undo = tr
                        }) { Icon(Icons.Filled.Close, "Remove ${tr.title}", tint = C.Muted) }
                    }
                }
            }
        }
        undo?.let { u ->
            Row(Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(12.dp)).background(C.Text).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Removed “${u.title}”", color = C.Bg, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { undo = null }) { Text("OK", color = Color(0xFF8A5A0C), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ====================================================================== Sound

@Composable
fun SoundScreen(st: StereoState, vm: StereoViewModel) {
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { vm.loadSound() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Stepper("Volume", "${st.volume}", { vm.volumeStep(-1) }, { vm.volumeStep(1) })
                st.bass?.let { b -> Stepper("Bass", signed(b), { vm.setTone("bass", b - 1) }, { vm.setTone("bass", b + 1) }) }
                st.treble?.let { t -> Stepper("Treble", signed(t), { vm.setTone("treble", t - 1) }, { vm.setTone("treble", t + 1) }) }
                st.balance?.let { b -> Stepper("Balance", if (b == 0) "Centre" else if (b < 0) "L ${-b}" else "R $b", { vm.balanceStep(-1) }, { vm.balanceStep(1) }) }
                if (st.balance != null) Text("Balance moves in small steps, up to 16 either side.", color = C.Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Panel(Modifier.fillMaxWidth()) {
            SwitchRow("Mute", null, st.muted) { vm.toggleMute() }
            st.phaseInverted?.let { p -> SwitchRow("Invert phase", "Absolute polarity", p) { vm.setPhase(it) } }
        }
        st.filter?.let { f ->
            SectionLabel("Digital filter")
            Panel(Modifier.fillMaxWidth()) {
                RowItem("Current filter", f.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                Text("This stereo doesn't allow changing the filter from the app.", color = C.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
            }
        }
        GhostButton("Reset bass, treble & balance", { vm.resetTone() }, Modifier.fillMaxWidth().padding(top = 16.dp))
        Spacer(Modifier.height(24.dp))
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

// ====================================================================== Settings

@Composable
fun SettingsScreen(st: StereoState, vm: StereoViewModel, version: String, update: String?) {
    val ctx = LocalContext.current
    var langDialog by remember { mutableStateOf(false) }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    fun issue(kind: String) {
        val body = "**App:** $version\n**Android:** ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n" +
            "**Stereo:** ${st.model} ${st.about["Firmware"] ?: ""}\n\n" +
            (if (kind == "bug") "**What happened:**\n\n**What you expected:**\n\n(Attach the Stereo check report if you can.)" else "**Your idea:**\n")
        open("https://github.com/EyalPrice/CambridgeConnectLegacy/issues/new?labels=$kind&title=&body=" + Uri.encode(body))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Panel(Modifier.fillMaxWidth(), active = st.pro) {
            SwitchRow("Pro mode", "Adds a Pro tab: Stereo check, every stereo command, network devices.", st.pro) { vm.setPro(it) }
        }
        SectionLabel("Stereo")
        Panel(Modifier.fillMaxWidth()) {
            RowItem("Name", st.name)
            Column(Modifier.padding(14.dp)) {
                Text("Display brightness", color = C.Text, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("Low", "Medium", "High"), st.brightness, { vm.setBrightness(it) })
            }
            if (st.languages.isNotEmpty()) RowItem("Display language", st.language) { langDialog = true }
        }
        SectionLabel("About this stereo")
        Panel(Modifier.fillMaxWidth()) {
            st.about.forEach { (k, v) -> RowItem(k, v) }
            Text("Firmware updates are done on the stereo itself, not from the app.", color = C.Muted, fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
        }
        SectionLabel("App")
        Panel(Modifier.fillMaxWidth()) {
            RowItem("Version", version)
            RowItem("Updates", update ?: "Up to date") { open("https://github.com/EyalPrice/CambridgeConnectLegacy/releases/latest") }
            RowItem("Change stereo") { vm.forget() }
        }
        SectionLabel("Feedback")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Report a bug", { issue("bug") }, Modifier.weight(1f))
            GhostButton("Request a feature", { issue("enhancement") }, Modifier.weight(1f))
        }
        Text("Opens a pre-filled form on GitHub (free account needed).", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        SectionLabel("Support the project")
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Cambridge Connect Legacy is a hobby project, made for fun and out of a love of good sound, so that great stereos keep working long after their official apps stop. It's free, with no ads and no tracking. If it's made your listening a bit better and you'd like to say thanks, a small donation would mean a lot. Enjoy the music!",
                    color = C.Text, fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(12.dp))
                val donate = ctx.getString(R.string.donate_url)
                if (donate.isNotBlank()) PrimaryButton("Buy me a coffee", { open(donate) })
                else Text("Donation page coming soon.", color = C.Muted, fontSize = 13.sp)
            }
        }
        SectionLabel("About the app")
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AppMark(48.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Cambridge Connect Legacy", color = C.Text, fontWeight = FontWeight.SemiBold)
                    Text("Made by EyalPrice · open source (MIT)", color = C.Muted, fontSize = 12.sp,
                        modifier = Modifier.clickable { open("https://github.com/EyalPrice/CambridgeConnectLegacy") })
                }
            }
        }
        Text("Unofficial app. Not affiliated with Cambridge Audio.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
    }
    if (langDialog) AlertDialog(onDismissRequest = { langDialog = false }, title = { Text("Display language") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(st.languages) { (name, code) ->
                    Text(name, color = if (name == st.language) C.Accent else C.Text,
                        modifier = Modifier.fillMaxWidth().clickable { vm.setLanguage(code); langDialog = false }.padding(vertical = 12.dp))
                }
            }
        }, confirmButton = { TextButton(onClick = { langDialog = false }) { Text("Close") } })
}

// ====================================================================== Pro

@Composable
fun ProScreen(st: StereoState, vm: StereoViewModel, onCheck: () -> Unit, onExplorer: () -> Unit, onRemote: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Panel(Modifier.fillMaxWidth().clickable(onClick = onCheck), active = true) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FactCheck, null, tint = C.Accent)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Stereo check", color = C.Text, fontWeight = FontWeight.SemiBold)
                    Text("Test every feature and share a report", color = C.Muted, fontSize = 12.sp)
                }
                Text("›", color = C.Muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Panel(Modifier.fillMaxWidth().clickable(onClick = onExplorer)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, tint = C.Accent)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Command explorer", color = C.Text, fontWeight = FontWeight.SemiBold)
                    val n = vm.device?.services?.sumOf { it.actions.size } ?: 0
                    Text("All $n commands your stereo offers", color = C.Muted, fontSize = 12.sp)
                }
                Text("›", color = C.Muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Panel(Modifier.fillMaxWidth().clickable(onClick = onRemote)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SettingsRemote, null, tint = C.Accent)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Virtual remote", color = C.Text, fontWeight = FontWeight.SemiBold)
                    Text("The same keys as the stereo's handset", color = C.Muted, fontSize = 12.sp)
                }
                Text("›", color = C.Muted)
            }
        }
        SectionLabel("Network devices")
        GhostButton("Search the network", { vm.search() }, Modifier.fillMaxWidth())
        if (st.searchInfo.isNotBlank()) Text(st.searchInfo, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        st.found.forEach { f ->
            Text("${f.name.ifBlank { "(no name)" }} · ${f.model}" + if (f.cambridge) "  ★ Cambridge" else "",
                color = if (f.cambridge) C.Accent else C.Text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        SectionLabel("Zones")
        Text("This stereo has a single zone.", color = C.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ExplorerScreen(vm: StereoViewModel) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var run by remember { mutableStateOf<Pair<Upnp.Service, Upnp.Action>?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val dev = vm.device ?: return
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(filter, { filter = it }, label = { Text("Filter commands") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.padding(top = 8.dp)) {
            dev.services.forEach { s ->
                val acts = s.actions.filter { filter.isBlank() || it.name.contains(filter, true) }
                if (acts.isNotEmpty()) item { SectionLabel(s.shortType()) }
                items(acts) { a ->
                    val read = StereoCheck.isRead(a.name)
                    val blocked = a.name == "DoUpgrade"
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = !blocked) { run = s to a },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, color = if (blocked) C.Muted else C.Text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(if (blocked) "disabled" else if (read) "read" else "change", fontSize = 11.sp,
                            color = if (blocked) C.Muted else if (read) C.Green else C.Accent)
                    }
                }
            }
        }
    }
    run?.let { (s, a) ->
        val values = remember(a) {
            mutableStateMapOf<String, String>().apply {
                a.inArgs().forEach { g ->
                    put(g.name, when (g.name) {
                        "InstanceID" -> "0"; "Channel" -> "Master"; "NavigatorId" -> vm.navId ?: ""; "Speed" -> "1"
                        else -> g.allowed.firstOrNull() ?: ""
                    })
                }
            }
        }
        AlertDialog(onDismissRequest = { run = null }, title = { Text(a.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (!StereoCheck.isRead(a.name)) Text("This command changes something on the stereo.", color = C.Accent, fontSize = 13.sp)
                    a.inArgs().forEach { g ->
                        OutlinedTextField(values[g.name] ?: "", { values[g.name] = it }, singleLine = true,
                            label = { Text(g.name) },
                            supportingText = {
                                val hint = (if (g.allowed.isNotEmpty()) "Allowed: " + g.allowed.joinToString(", ") else "") +
                                    (if (g.range.isNotEmpty()) " Range: " + g.range else "")
                                if (hint.isNotBlank()) Text(hint)
                            })
                    }
                    if (a.inArgs().isEmpty()) Text("No inputs needed.", color = C.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val args = LinkedHashMap(values.toMap())
                    run = null
                    scope.launch(Dispatchers.IO) {
                        val text = try {
                            val r = Upnp.call(s, a.name, args, 30000)
                            (if (r.ok()) "OK" else "Error (HTTP ${r.httpCode})") + "\n\n" +
                                r.out.entries.joinToString("\n") { "${it.key} = ${it.value}" } +
                                (if (!r.ok() || r.out.isEmpty()) "\n\n" + r.body.take(3000) else "")
                        } catch (e: Exception) { "Failed: ${e.message}" }
                        result = StereoCheck.sanitize(text, dev.host())
                    }
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { run = null }) { Text("Cancel") } })
    }
    result?.let { r ->
        AlertDialog(onDismissRequest = { result = null }, title = { Text("Reply") },
            text = { Text(r, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("Close") } })
    }
}

@Composable
fun CheckScreen(vm: StereoViewModel, version: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dev = vm.device ?: return
    val check = remember(dev) { StereoCheck(dev, "Cambridge Connect Legacy $version") }
    var progress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    fun job(block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            try { block() } catch (e: Exception) { progress = "Stopped: $e" }
            busy = false
        }
    }
    val p = StereoCheck.Progress { progress = it }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Tests what your stereo supports and builds a report. Network addresses, MAC address and most of the serial number are removed.",
            color = C.Muted, fontSize = 13.sp)
        if (progress.isNotBlank()) Text(progress, color = C.Accent, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
        SectionLabel("1 · Read test")
        PrimaryButton("Run read test", { job { check.runReadTest(p) } }, enabled = !busy)
        SectionLabel("2 · Source tour")
        Text("Switch the stereo to a source, start something playing, name it and tap Capture. Repeat for each source.", color = C.Muted, fontSize = 13.sp)
        OutlinedTextField(label, { label = it }, label = { Text("Source name, e.g. Spotify") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        PrimaryButton("Capture this source", {
            val n = label.ifBlank { "Source ${check.tourCount() + 1}" }
            label = ""
            job { check.captureSource(n, p) }
        }, enabled = !busy)
        SectionLabel("3 · Safe change test (optional)")
        Text("Changes each setting one step, reads it back and restores it. Never: full off, firmware update, deleting presets.", color = C.Muted, fontSize = 13.sp)
        GhostButton("Run safe change test", { confirm = true }, Modifier.fillMaxWidth().padding(top = 8.dp))
        SectionLabel("Report")
        PrimaryButton("Share report", {
            job {
                check.releaseNavigator()
                val f = File(ctx.cacheDir, ReportProvider.FILE_NAME)
                f.writeText(check.buildReport())
                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Stereo check report – ${dev.friendlyName}")
                    .putExtra(Intent.EXTRA_TEXT, "Stereo check report from Cambridge Connect Legacy (attached).")
                    .putExtra(Intent.EXTRA_STREAM, ReportProvider.uri())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(i, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, enabled = !busy)
        Spacer(Modifier.height(24.dp))
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Run the safe change test?") },
        text = { Text("Settings change for a moment and are then restored. You may hear the volume or mute change briefly.") },
        confirmButton = { TextButton(onClick = { confirm = false; job { check.runChangeTest(p) } }) { Text("Run") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

// ====================================================================== First start

@Composable
fun WelcomeScreen(st: StereoState, vm: StereoViewModel) {
    var ip by rememberSaveable { mutableStateOf("") }
    val cambridge = st.found.filter { it.cambridge }
    val others = st.found.filter { !it.cambridge }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        AppMark(96.dp)
        Text("Cambridge Connect Legacy", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
        Text("Wi-Fi remote for Minx Xi, NP30 and Stream Magic 6", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(28.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                when (st.conn) {
                    Conn.SEARCHING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp)
                        Text("   Looking for your stereo…", color = C.Text)
                    }
                    Conn.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp)
                        Text("   Connecting…", color = C.Text)
                    }
                    Conn.UNREACHABLE -> Text("Couldn't reach the stereo. Is it switched on and on this Wi-Fi?", color = C.Text)
                    else -> Text(if (cambridge.isEmpty()) "No Cambridge Audio stereo found yet." else "Tap your stereo to connect.", color = C.Text)
                }
                Text("The phone and the stereo must be on the same Wi-Fi.", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                cambridge.forEach { f ->
                    Spacer(Modifier.height(10.dp))
                    Panel(Modifier.fillMaxWidth().clickable { vm.connect(f.location) }, active = true) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name.ifBlank { "Cambridge Audio stereo" }, color = C.Text, fontWeight = FontWeight.SemiBold)
                                Text(f.model, color = C.Muted, fontSize = 12.sp)
                            }
                            Text("Connect", color = C.Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (st.searchInfo.isNotBlank() && st.conn == Conn.NONE) {
                    Text(st.searchInfo, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (others.isNotEmpty()) {
                    var showOthers by remember { mutableStateOf(false) }
                    TextButton(onClick = { showOthers = !showOthers }) {
                        Text(if (showOthers) "Hide other devices" else "Show ${others.size} other device(s)", color = C.Accent)
                    }
                    if (showOthers) others.forEach { f ->
                        TextButton(onClick = { vm.connect(f.location) }) {
                            Text("${f.name.ifBlank { "(no name)" }} · ${f.model}", color = C.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                GhostButton("Search again", { vm.search() }, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(ip, { ip = it }, label = { Text("Or type the stereo's IP address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        GhostButton("Search this address", { if (ip.isNotBlank()) vm.search(ip.trim()) }, Modifier.fillMaxWidth().padding(top = 8.dp))
        Text("Unofficial app · not affiliated with Cambridge Audio", color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 24.dp))
    }
}


// ====================================================================== Virtual remote

@Composable
fun RemoteScreen(vm: StereoViewModel) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Keys are sent exactly like the stereo's handset.") }
    fun key(k: String) { scope.launch { status = "Sending $k…"; status = vm.pressKey(k) } }
    @Composable
    fun K(label: String, k: String, modifier: Modifier = Modifier) {
        Box(modifier.height(52.dp).clip(RoundedCornerShape(12.dp)).background(C.Line).clickable { key(k) },
            contentAlignment = Alignment.Center) { Text(label, color = C.Text, fontSize = 14.sp) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text(status, color = C.Accent, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    K("Info", "INFO", Modifier.weight(1f)); K("Home", "HOME", Modifier.weight(1f)); K("Menu", "MENU", Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.size(210.dp).clip(CircleShape).background(C.Surface2).border(1.dp, C.Line2, CircleShape)) {
                    IconButton({ key("UP") }, Modifier.align(Alignment.TopCenter).padding(6.dp).size(52.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Up", tint = C.Text) }
                    IconButton({ key("DOWN") }, Modifier.align(Alignment.BottomCenter).padding(6.dp).size(52.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Down", tint = C.Text) }
                    IconButton({ key("LEFT") }, Modifier.align(Alignment.CenterStart).padding(6.dp).size(52.dp)) { Icon(Icons.Filled.KeyboardArrowLeft, "Left", tint = C.Text) }
                    IconButton({ key("RIGHT") }, Modifier.align(Alignment.CenterEnd).padding(6.dp).size(52.dp)) { Icon(Icons.Filled.KeyboardArrowRight, "Right", tint = C.Text) }
                    Box(Modifier.align(Alignment.Center).size(78.dp).clip(CircleShape).background(C.Accent).clickable { key("SELECT") },
                        contentAlignment = Alignment.Center) { Text("OK", color = C.Bg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    K("Back", "BACK", Modifier.weight(1f)); K("Presets", "PRESET", Modifier.weight(1f)); K("Source", "SOURCE", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    K("⏮", "PREV", Modifier.weight(1f)); K("⏯", "PLAY", Modifier.weight(1f)); K("⏹", "STOP", Modifier.weight(1f)); K("⏭", "NEXT", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    K("Vol −", "VOLDOWN", Modifier.weight(1f)); K("Mute", "MUTE", Modifier.weight(1f)); K("Vol +", "VOLUP", Modifier.weight(1f))
                }
            }
        }
        Text("Power is on the top bar. The first press of each key may take a moment while the app learns your stereo's key names.",
            color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
    }
}
