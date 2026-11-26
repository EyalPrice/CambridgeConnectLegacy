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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.withLink
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
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

private fun fmt(sec: Int) = nx(String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60))

@Composable
fun NowPlayingScreen(st: StereoState, vm: StereoViewModel, onInputs: () -> Unit, onQueue: () -> Unit) {
    val pb = st.playback
    val ctx = LocalContext.current
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
            val preset = st.playingPreset
            label = if (preset != null) tx("preset_label", preset.number) else st.sourceName.uppercase()
        }
        else -> {
            title = st.sourceName
            subtitle = tx("playing_from", st.sourceName)
            label = tx("input_caps")
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
        // Swipe the card: right-to-left = next, left-to-right = previous (same as the buttons).
        var dragX by remember { mutableFloatStateOf(0f) }
        val view = androidx.compose.ui.platform.LocalView.current
        Panel(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()
            .graphicsLayer { translationX = dragX * 0.35f }
            .pointerInput(Unit) {
                val limit = 60.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX <= -limit) { vm.transport("Next"); view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
                        else if (dragX >= limit) { vm.transport("Previous"); view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                ) { change, amount -> change.consume(); dragX += amount }
            }) {
            // Fixed height: long station names never push the controls down.
            Row(Modifier.padding(12.dp).height(108.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtBox(if (st.hasMetadata) pb.artUrl else "", if (st.isSpotify) 96.dp else 72.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = C.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp, maxLines = 1)
                        if (st.tuning) {
                            CircularProgressIndicator(Modifier.padding(start = 8.dp).size(12.dp), color = C.Accent, strokeWidth = 1.5.dp)
                            Text("  " + tx("tuning"), color = C.Muted, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    Text(title, color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (st.isSpotify && st.spotifyDur > 0) Ltr {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                    LinearProgressIndicator(progress = { st.spotifyPos.toFloat() / st.spotifyDur }, color = C.Accent,
                        trackColor = C.Line, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(fmt(st.spotifyPos), color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        if (pb.shuffle) Text(tx("shuffle") + "  ", color = C.Accent, fontSize = 11.sp)
                        if (pb.repeat) Text(tx("repeat") + "  ", color = C.Accent, fontSize = 11.sp)
                        Text(fmt(st.spotifyDur), color = C.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        Ltr {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.transport("Previous") }) { Icon(Icons.Filled.SkipPrevious, tx("previous"), tint = C.Text) }
            PlayButton(playing, st.playPending, 60.dp) { playPressed(ctx, st, vm, playing) }
            IconButton(onClick = { vm.transport("Next") }) { Icon(Icons.Filled.SkipNext, tx("next"), tint = C.Text) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleIconButton(Icons.Filled.Remove, tx("volume_down"), { vm.volumeStep(-1) }, size = 52.dp)
            VolumeKnob(st.volume, st.volumeMax, st.muted, onChange = { vm.setVolume(it) })
            CircleIconButton(Icons.Filled.Add, tx("volume_up"), { vm.volumeStep(1) }, size = 52.dp)
        }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(selected = st.muted, onClick = { vm.toggleMute() }, label = { Text(if (st.muted) tx("unmute") else tx("mute")) })
            FilterChip(selected = false, onClick = onQueue, label = { Text(tx("page_queue")) })
        }
        Text(tx("knob_hint"),
            color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
    }
}

// ====================================================================== Inputs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InputsScreen(st: StereoState, vm: StereoViewModel, onPicked: () -> Unit) {
    var rename by remember { mutableStateOf<Input?>(null) }
    var menuFor by remember { mutableStateOf<Input?>(null) }
    var replaceFor by remember { mutableStateOf<Input?>(null) }
    if (st.inputs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tx("reading_inputs"), color = C.Muted) }
        return
    }
    LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(20.dp, 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Text(tx("inputs_hint"), color = C.Muted, fontSize = 13.sp)
        }
        items(st.inputs, key = { it.number }) { inp ->
            val on = inp.number == st.sourceNumber
            Column(
                Modifier.height(112.dp).clip(RoundedCornerShape(18.dp)).background(if (on) C.AccentBg else C.Surface)
                    .border(if (on) 1.5.dp else 1.dp, if (on) C.Accent else C.Line, RoundedCornerShape(18.dp))
                    .combinedClickable(onClick = { vm.selectInput(inp.number); onPicked() }, onLongClick = { menuFor = inp })
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Accent else C.Line),
                        contentAlignment = Alignment.Center) {
                        Icon(inputIcon(inp), null, tint = if (on) C.Bg else C.Text, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (inp.number in st.widgetInputs)
                        Icon(Icons.Outlined.Widgets, tx("on_widget"), tint = C.Accent, modifier = Modifier.size(16.dp).padding(end = 2.dp))
                    if (on && "input" in st.busy) BusySpinner(Modifier.padding(start = 4.dp))
                    else if (on) Text(" " + tx("active_caps"), color = C.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                }
                Column {
                    Text(inp.name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(tx("input_n", inp.number), color = C.Muted, fontSize = 12.sp)
                }
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Widgets, null, tint = C.Accent, modifier = Modifier.size(14.dp))
                Text("  " + tx("widget_key", MAX_WIDGET), color = C.Muted, fontSize = 12.sp)
            }
        }
    }
    menuFor?.let { inp ->
        val idx = st.inputs.indexOfFirst { it.number == inp.number }
        val onWidget = inp.number in st.widgetInputs
        AlertDialog(onDismissRequest = { menuFor = null }, title = { Text(inp.name) },
            text = {
                Column {
                    MenuLine(Icons.Outlined.Edit, tx("rename")) { menuFor = null; rename = inp }
                    MenuLine(Icons.Filled.ArrowUpward, tx("move_up"), enabled = idx > 0) { vm.moveInput(inp.number, -1) }
                    MenuLine(Icons.Filled.ArrowDownward, tx("move_down"), enabled = idx in 0 until st.inputs.size - 1) { vm.moveInput(inp.number, 1) }
                    if (onWidget) MenuLine(Icons.Outlined.Widgets, tx("remove_from_widget")) { vm.removeFromWidget(inp.number); menuFor = null }
                    else MenuLine(Icons.Outlined.Widgets, tx("add_to_widget")) {
                        menuFor = null
                        if (st.widgetInputs.size >= MAX_WIDGET) replaceFor = inp else vm.addToWidget(inp.number)
                    }
                }
            }, confirmButton = { TextButton(onClick = { menuFor = null }) { Text(tx("close")) } })
    }
    replaceFor?.let { inp ->
        AlertDialog(onDismissRequest = { replaceFor = null }, title = { Text(tx("widget_full")) },
            text = {
                Column {
                    Text(tx("widget_full_q", MAX_WIDGET, inp.name), color = C.Muted,
                        modifier = Modifier.padding(bottom = 8.dp))
                    st.inputs.filter { it.number in st.widgetInputs }.forEach { w ->
                        MenuLine(Icons.Outlined.SwapHoriz, w.name) { vm.addToWidget(inp.number, replace = w.number); replaceFor = null }
                    }
                }
            }, confirmButton = { TextButton(onClick = { replaceFor = null }) { Text(tx("cancel")) } })
    }
    rename?.let { inp ->
        var name by remember(inp) { mutableStateOf(inp.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text(tx("rename_input", inp.number)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameInput(inp.number, name.trim()); rename = null }) { Text(tx("save")) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(tx("cancel")) } })
    }
}

@Composable
private fun MenuLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val col = if (enabled) C.Text else C.Muted.copy(alpha = 0.5f)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) C.Accent else col, modifier = Modifier.size(20.dp))
        Text(text, color = col, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp))
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
        Segmented(listOf(tx("presets"), tx("search")), tab.coerceAtMost(1), { tab = it }, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        when (tab) {
            0 -> PresetList(st, vm, onPlaying)
            else -> StationSearch(st, vm, onPlaying)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetList(st: StereoState, vm: StereoViewModel, onPlaying: () -> Unit) {
    var options by remember { mutableStateOf<Int?>(null) }
    val count = st.presetCount.coerceAtLeast(st.presets.maxOfOrNull { it.number } ?: 0)
    if (count == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tx("reading_presets"), color = C.Muted) }
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        items((1..count).toList()) { n ->
            val p = st.presets.firstOrNull { it.number == n }
            val on = p != null && st.playingPreset?.number == n
            Row(
                Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)).background(if (on) C.AccentBg else Color.Transparent)
                    .combinedClickable(onClick = { if (p != null) { vm.playPreset(n); onPlaying() } else options = n },
                        onLongClick = { options = n })
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (on) C.Accent else C.Surface2), contentAlignment = Alignment.Center) {
                    Text(nx(n), color = if (on) C.Bg else C.Text, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p?.title ?: tx("empty"), color = if (p == null) C.Muted else C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (p == null) tx("hold_to_save") else if (on) tx("playing_now") else p.description.ifBlank { tx("tap_to_play") },
                        color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (on) Icon(Icons.Filled.GraphicEq, tx("playing"), tint = C.Accent)
            }
        }
    }
    options?.let { n ->
        val p = st.presets.firstOrNull { it.number == n }
        var confirmDelete by remember(n) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { options = null }, title = { Text(tx("preset_n", n) + (p?.let { " · ${it.title}" } ?: "")) },
            text = {
                Column {
                    if (st.isRadio) TextButton(onClick = { vm.saveCurrentAsPreset(n); options = null }) {
                        Text(if (p == null) tx("save_here", st.playback.title.ifBlank { tx("current_station") }) else tx("replace_with", st.playback.title.ifBlank { tx("current_station") }))
                    } else Text(tx("switch_to_radio"), color = C.Muted)
                    if (p != null) {
                        if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text(tx("delete_preset"), color = C.Danger) }
                        else TextButton(onClick = { vm.deletePreset(n); options = null }) { Text(tx("tap_again_delete", p.title), color = C.Danger) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { options = null }) { Text(tx("close")) } })
    }
}

@Composable
private fun StationSearch(st: StereoState, vm: StereoViewModel, onPlaying: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<StereoViewModel.SearchResult?>(null) }
    var saveFor by remember { mutableStateOf<Reciva.Station?>(null) }
    var country by remember { mutableStateOf<StereoViewModel.Choice?>(null) }
    var genre by remember { mutableStateOf<StereoViewModel.Choice?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    fun go() {
        if (busy || (q.isBlank() && country == null && genre == null)) return
        busy = true
        scope.launch { result = vm.searchStations(q.trim(), country?.id, genre?.id); busy = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(q, { q = it }, label = { Text(tx("station_name_hint")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }),
            trailingIcon = { IconButton(onClick = { go() }) { Icon(Icons.Filled.Search, tx("search")) } })
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = country != null, onClick = { picker = "country" },
                label = { Text(country?.name ?: tx("any_country"), maxLines = 1) },
                trailingIcon = { Icon(Icons.Filled.ExpandMore, null, Modifier.size(16.dp)) })
            FilterChip(selected = genre != null, onClick = { picker = "genre" },
                label = { Text(genre?.name ?: tx("any_genre"), maxLines = 1) },
                trailingIcon = { Icon(Icons.Filled.ExpandMore, null, Modifier.size(16.dp)) })
        }
        PrimaryButton(tx("search"), { go() }, Modifier.padding(top = 8.dp), enabled = !busy)
        if (busy) Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp)
            Text("  " + tx("searching"), color = C.Muted, fontSize = 13.sp)
        }
        val r = result
        val problem = when {
            r == null || busy -> null
            r.error != null -> r.error
            r.stations.isEmpty() && r.raw.isNotBlank() -> tx("unknown_format")
            r.stations.isEmpty() -> tx("no_stations")
            else -> null
        }
        if (problem != null) {
            Text(problem, color = C.Muted, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { shareText(ctx, "Cambridge Connect Legacy debug log", vm.debugLog()) }) {
                Text(tx("share_debug_long"), color = C.Accent)
            }
        }
        var resultFilter by remember(r) { mutableStateOf("") }
        if (r != null && !busy && r.stations.size > 8)
            SearchField(resultFilter, { resultFilter = it }, tx("search_these", r.stations.size), Modifier.padding(top = 8.dp))
        if (r != null && !busy && r.stations.isNotEmpty()) LazyColumn(Modifier.padding(top = 8.dp)) {
            items(r.stations.filter { resultFilter.isBlank() || it.name.contains(resultFilter, true) || it.detail.contains(resultFilter, true) }) { s ->
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(Icons.Filled.PlayArrow, tx("play_x", s.name), { vm.playStation(s.id); onPlaying() }, size = 40.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(s.name, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.detail.isNotBlank()) Text(s.detail, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { saveFor = s }) { Text(tx("save"), color = C.Accent) }
                }
            }
        }
    }
    picker?.let { kind ->
        var list by remember(kind) { mutableStateOf<List<StereoViewModel.Choice>?>(null) }
        var filter by remember(kind) { mutableStateOf("") }
        LaunchedEffect(kind) { list = if (kind == "country") vm.locations() else vm.genres() }
        AlertDialog(onDismissRequest = { picker = null }, title = { Text(if (kind == "country") tx("country") else tx("genre")) },
            text = {
                Column {
                    SearchField(filter, { filter = it }, if (kind == "country") tx("type_country") else tx("type_genre"))
                    val l = list
                    if (l == null) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = C.Accent, strokeWidth = 2.dp)
                        Text("  " + tx("loading_stereo"), color = C.Muted)
                    } else if (l.isEmpty()) Text(tx("list_not_sent"), color = C.Muted, modifier = Modifier.padding(top = 12.dp))
                    else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        item {
                            Text(if (kind == "country") "Any country" else "Any genre", color = C.Accent,
                                modifier = Modifier.fillMaxWidth().clickable { if (kind == "country") country = null else genre = null; picker = null }.padding(vertical = 12.dp))
                        }
                        items(l.filter { filter.isBlank() || it.name.contains(filter, true) }) { c ->
                            Text(c.name, color = C.Text, modifier = Modifier.fillMaxWidth()
                                .clickable { if (kind == "country") country = c else genre = c; picker = null }.padding(vertical = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text(tx("close")) } })
    }
    saveFor?.let { s ->
        SlotPicker(st, tx("save_x", s.name), onPick = { n ->
            saveFor = null
            vm.saveStationToSlot(s, n)
        }, onDismiss = { saveFor = null })
    }
}

fun shareText(ctx: android.content.Context, subject: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, subject))
}

@Composable
private fun SlotPicker(st: StereoState, title: String, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val count = st.presetCount.coerceAtLeast(1)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                Text(tx("choose_slot"), color = C.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                (1..count).chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        row.forEach { n ->
                            val empty = st.presets.none { it.number == n }
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2)
                                .border(1.dp, if (empty) C.Accent else C.Line2, RoundedCornerShape(10.dp)).clickable { onPick(n) },
                                contentAlignment = Alignment.Center) { Text(nx(n), color = C.Text, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tx("cancel")) } })
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
        menu = openRoot(vm, rootIcon, rootName)
        error = if (menu == null) "music_menu_failed" else null
        busy = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (depth > 0) IconButton(onClick = {
                busy = true
                scope.launch { menu = vm.menuBack(); depth--; busy = false }
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx("back"), tint = C.Text) }
            Text(menu?.name ?: rootName, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = if (depth > 0) 0.dp else 8.dp).weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = C.Accent, strokeWidth = 2.dp)
        }
        error?.let {
            Text(tx(it), color = C.Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
            val ctx = LocalContext.current
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = {
                    busy = true; error = null
                    scope.launch {
                        menu = openRoot(vm, rootIcon, rootName)
                        depth = 0
                        error = if (menu == null) "menu_still_failed" else null
                        busy = false
                    }
                }) { Text(tx("try_again"), color = C.Accent) }
                TextButton(onClick = { shareText(ctx, "Cambridge Connect Legacy debug log", vm.debugLog()) }) { Text(tx("share_debug"), color = C.Accent) }
            }
        }
        var menuFilter by remember(menu) { mutableStateOf("") }
        val shown = (menu?.items ?: emptyList()).filterNot { isDangerous(it) }
        if (shown.size > 8) SearchField(menuFilter, { menuFilter = it }, tx("search_list"), Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(shown.filter { menuFilter.isBlank() || it.name.contains(menuFilter, true) }) { item ->
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
                    if (item.type == "menu") Text(chevron(), color = C.Muted)
                }
            }
        }
    }
}

/** Never offer stereo-settings items such as factory reset or firmware update from a browse list. */
private fun isDangerous(i: Reciva.MenuItem): Boolean {
    val n = i.name.lowercase()
    return i.icon == "CONFIGURE" || listOf("reset", "factory", "upgrade", "firmware", "update", "wipe", "erase", "setting", "network config")
        .any { n.contains(it) }
}

/**
 * Opens one branch of the stereo's menu (e.g. Music Library). Returns null unless the stereo really
 * shows that branch, so the user never lands in an unrelated menu such as Settings.
 */
private suspend fun openRoot(vm: StereoViewModel, rootIcon: String, rootName: String): Reciva.Menu? {
    val top = vm.menuTop() ?: return null
    val root = top.items.firstOrNull { it.icon == rootIcon } ?: top.items.firstOrNull { it.name.contains(rootName, true) } ?: return null
    val m = vm.menuSelect(root) ?: return null
    if (m.atTop || m.id == top.id) return null
    return m
}

@Composable
fun MusicScreen(vm: StereoViewModel, onPlaying: () -> Unit, onQueue: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(tx("music_hint"), color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onQueue) { Text(tx("page_queue"), color = C.Accent) }
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
        if (st.isSpotify) Text(tx("spotify_queue"), color = C.Muted, modifier = Modifier.padding(8.dp))
        val t = tracks
        when {
            t == null -> Text(tx("reading_queue"), color = C.Muted, modifier = Modifier.padding(8.dp))
            t.isEmpty() -> Text(tx("queue_empty"), color = C.Muted, modifier = Modifier.padding(8.dp))
            else -> LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(t) { i, tr ->
                    Row(Modifier.fillMaxWidth().height(60.dp).clickable { vm.queueJump(tr.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(nx(i + 1), color = C.Muted, modifier = Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr.title, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (tr.artist.isNotBlank()) Text(tr.artist, color = C.Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = {
                            vm.queueDelete(tr.id); tracks = t - tr; undo = tr
                        }) { Icon(Icons.Filled.Close, tx("remove_x", tr.title), tint = C.Muted) }
                    }
                }
            }
        }
        undo?.let { u ->
            Row(Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(12.dp)).background(C.Text).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(tx("removed_x", u.title), color = C.Bg, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { undo = null }) { Text("OK", color = Color(0xFF8A5A0C), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ====================================================================== Sound

@Composable
fun SoundScreen(st: StereoState, vm: StereoViewModel) {
    DisposableEffect(Unit) { vm.soundVisible = true; onDispose { vm.soundVisible = false } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { vm.loadSound() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Stepper(tx("volume"), nx(st.volume), { vm.volumeStep(-1) }, { vm.volumeStep(1) },
                    minusEnabled = st.volume > 0, plusEnabled = !st.volumeHard || st.volume < st.volumeMax)
                st.bass?.let { b -> Stepper(tx("bass"), signed(b), { vm.setTone("bass", b - 1) }, { vm.setTone("bass", b + 1) },
                    busy = "bass" in st.busy, minusEnabled = st.bassMin == null || b > st.bassMin, plusEnabled = st.bassMax == null || b < st.bassMax) }
                st.treble?.let { t -> Stepper(tx("treble"), signed(t), { vm.setTone("treble", t - 1) }, { vm.setTone("treble", t + 1) },
                    busy = "treble" in st.busy, minusEnabled = st.trebleMin == null || t > st.trebleMin, plusEnabled = st.trebleMax == null || t < st.trebleMax) }
                st.balance?.let { b -> Stepper(tx("balance"), if (b == 0) tx("centre") else if (b < 0) "${tx("left_short")} ${nx(-b)}" else "${tx("right_short")} ${nx(b)}", { vm.balanceStep(-1) }, { vm.balanceStep(1) },
                    minusLabel = tx("left_short"), plusLabel = tx("right_short"), busy = "balance" in st.busy,
                    minusEnabled = st.balanceMin == null || b > st.balanceMin, plusEnabled = st.balanceMax == null || b < st.balanceMax) }
                Text(tx("limits_hint"),
                    color = C.Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Panel(Modifier.fillMaxWidth()) {
            SwitchRow(tx("mute"), null, st.muted, busy = "mute" in st.busy) { vm.toggleMute() }
            st.phaseInverted?.let { p -> SwitchRow(tx("invert_phase"), tx("absolute_polarity"), p, busy = "phase" in st.busy) { vm.setPhase(it) } }
        }
        st.filter?.let { f ->
            SectionLabel(tx("digital_filter"))
            Panel(Modifier.fillMaxWidth()) {
                RowItem(tx("current_filter"), f.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                Text(tx("filter_fixed"), color = C.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
            }
        }
        GhostButton(tx("reset_tone"), { vm.resetTone() }, Modifier.fillMaxWidth().padding(top = 16.dp))
        Spacer(Modifier.height(24.dp))
    }
}

private fun signed(v: Int) = if (v > 0) "+" + nx(v) else if (v < 0) "-" + nx(-v) else nx(v)

// ====================================================================== Settings

@Composable
fun SettingsScreen(st: StereoState, vm: StereoViewModel, version: String, update: String?, onCheckUpdate: () -> Unit) {
    val ctx = LocalContext.current
    var langDialog by remember { mutableStateOf(false) }
    var forgetDialog by remember { mutableStateOf(false) }
    var appLangDialog by remember { mutableStateOf(false) }
    if (appLangDialog) AlertDialog(onDismissRequest = { appLangDialog = false }, title = { Text(tx("app_language")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(listOf("") + I18n.CHOICES) { code ->
                    val on = I18n.choice == code
                    Text(if (code.isBlank()) tx("same_as_phone") else I18n.NAMES[code] ?: code,
                        color = if (on) C.Accent else C.Text, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { I18n.choose(ctx, code); appLangDialog = false }.padding(vertical = 12.dp))
                }
            }
        }, confirmButton = { TextButton(onClick = { appLangDialog = false }) { Text(tx("close")) } })
    if (forgetDialog) AlertDialog(onDismissRequest = { forgetDialog = false },
        title = { Text(tx("forget_title")) },
        text = { Text(tx("forget_text")) },
        confirmButton = { TextButton(onClick = { forgetDialog = false; vm.forgetLimits() }) { Text(tx("forget")) } },
        dismissButton = { TextButton(onClick = { forgetDialog = false }) { Text(tx("cancel")) } })
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    fun issue(kind: String) {
        val body = "**App:** $version\n**Android:** ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n" +
            "**Stereo:** ${st.model} ${st.about["Firmware"] ?: ""}\n\n" +
            (if (kind == "bug") "**What happened:**\n\n**What you expected:**\n\n(Please attach the diagnostic report: Settings → Diagnostics → Create diagnostic report → Share report.)"
             else "**Describe the feature request:**\n")
        open("https://github.com/EyalPrice/CambridgeConnectLegacy/issues/new?labels=$kind&title=&body=" + Uri.encode(body))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        SectionLabel(tx("section_stereo"))
        Panel(Modifier.fillMaxWidth()) {
            RowItem(tx("stereo_name"), st.name)
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tx("brightness"), color = C.Text, fontSize = 14.sp)
                    if ("brightness" in st.busy) BusySpinner(Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Segmented(listOf(tx("low"), tx("medium"), tx("high")), st.brightness, { vm.setBrightness(it) })
            }
            if (st.languages.isNotEmpty()) RowItem(tx("stereo_language"), if ("language" in st.busy) tx("changing") else st.language) { langDialog = true }
        }
        SectionLabel(tx("about_stereo"))
        Panel(Modifier.fillMaxWidth()) {
            st.about.forEach { (k, v) ->
                val label = when (k) { "Firmware" -> tx("about_firmware"); "Hardware" -> tx("about_hardware"); "Model" -> tx("about_model"); "Serial number" -> tx("about_serial"); else -> k }
                RowItem(label, v)
            }
            Text(tx("firmware_note"), color = C.Muted, fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
        }
        SectionLabel(tx("section_app"))
        Panel(Modifier.fillMaxWidth()) {
            // Not English? Add "(App language)" so anyone who picked a language by mistake can find the way back.
            RowItem(tx("app_language") + if (I18n.lang.startsWith("en")) "" else " (App language)",
                if (I18n.choice.isBlank()) tx("same_as_phone") else I18n.NAMES[I18n.choice] ?: I18n.choice,
                icon = Icons.Outlined.Language) { appLangDialog = true }
            RowItem(tx("version"), version)
            RowItem(tx("updates"), update ?: tx("check_now")) {
                if (update != null && update.startsWith(tx("update_new", "").trim())) open("https://github.com/EyalPrice/CambridgeConnectLegacy/releases/latest") else onCheckUpdate()
            }
            RowItem(tx("forget_limits"), tx("forget_limits_sub")) { forgetDialog = true }
            RowItem(tx("change_stereo")) { vm.forget() }
        }
        SectionLabel(tx("feedback"))
        // Stacked, full width: in several languages the two labels don't fit side by side.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(tx("report_bug"), { issue("bug") }, Modifier.fillMaxWidth())
            GhostButton(tx("request_feature"), { issue("enhancement") }, Modifier.fillMaxWidth())
        }
        Text(tx("github_note"), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        SectionLabel(tx("support"))
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(tx("support_text"),
                    color = C.Text, fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(12.dp))
                val donate = ctx.getString(R.string.donate_url)
                if (donate.isNotBlank()) PrimaryButton(tx("buy_coffee"), { open(donate) })
                else Text(tx("donation_soon"), color = C.Muted, fontSize = 13.sp)
            }
        }
        ReportProblem(vm, version)
        SectionLabel(tx("about_app"))
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AppMark(48.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Cambridge Connect Legacy", color = C.Text, fontWeight = FontWeight.SemiBold)
                    Text(tx("made_by"), color = C.Muted, fontSize = 12.sp,
                        modifier = Modifier.clickable { open("https://github.com/EyalPrice/CambridgeConnectLegacy") })
                }
            }
        }
        Text(tx("unofficial"), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
    }
    if (langDialog) AlertDialog(onDismissRequest = { langDialog = false }, title = { Text(tx("stereo_language")) },
        text = {
            var langFilter by remember { mutableStateOf("") }
            Column {
            SearchField(langFilter, { langFilter = it }, tx("type_language"))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(st.languages.sortedBy { it.first.lowercase() }.filter { langFilter.isBlank() || it.first.contains(langFilter, true) }) { (name, code) ->
                    Text(name, color = if (name == st.language) C.Accent else C.Text,
                        modifier = Modifier.fillMaxWidth().clickable { vm.setLanguage(code); langDialog = false }.padding(vertical = 12.dp))
                }
            }
            }
        }, confirmButton = { TextButton(onClick = { langDialog = false }) { Text(tx("close")) } })
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
        Text(tx("welcome_subtitle"), color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(28.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                when (st.conn) {
                    Conn.SEARCHING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp)
                        Text("   " + tx("looking"), color = C.Text)
                    }
                    Conn.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp)
                        Text("   " + tx("connecting"), color = C.Text)
                    }
                    Conn.UNREACHABLE -> Text(tx("cant_reach"), color = C.Text)
                    else -> Text(if (cambridge.isEmpty()) tx("none_found") else tx("tap_stereo"), color = C.Text)
                }
                Text(tx("same_wifi"), color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                cambridge.forEach { f ->
                    Spacer(Modifier.height(10.dp))
                    Panel(Modifier.fillMaxWidth().clickable { vm.connect(f.location) }, active = true) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name.ifBlank { tx("cambridge_stereo") }, color = C.Text, fontWeight = FontWeight.SemiBold)
                                Text(f.model, color = C.Muted, fontSize = 12.sp)
                            }
                            Text(tx("connect"), color = C.Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (st.searchInfo().isNotBlank() && st.conn == Conn.NONE) {
                    Text(st.searchInfo(), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (others.isNotEmpty()) {
                    var showOthers by remember { mutableStateOf(false) }
                    TextButton(onClick = { showOthers = !showOthers }) {
                        Text(if (showOthers) tx("hide_others") else tx("show_others", others.size), color = C.Accent)
                    }
                    if (showOthers) others.forEach { f ->
                        TextButton(onClick = { vm.connect(f.location) }) {
                            Text("${f.name.ifBlank { tx("no_name") }} · ${f.model}", color = C.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                GhostButton(tx("search_again"), { vm.search() }, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(ip, { ip = it }, label = { Text(tx("type_ip")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        GhostButton(tx("search_address"), { if (ip.isNotBlank()) vm.search(ip.trim()) }, Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(tx("unofficial_short"), color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 24.dp))
    }
}

// ====================================================================== Diagnostics (Settings)

private const val WEBSITE = "https://eyalprice.github.io/CambridgeConnectLegacy/#community"

/** Closed by default. Creates one detailed diagnostic report the user can read, share, and attach to a bug report. */
@Composable
private fun ReportProblem(vm: StereoViewModel, version: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BugReport, null, tint = C.Accent)
            Text(tx("diagnostics"), color = C.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) tx("close") else tx("open"), tint = C.Muted)
        }
        if (expanded) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            Text(tx("diag_text"),
                color = C.Muted, fontSize = 13.sp)
            PrimaryButton(if (busy) tx("creating_report") else tx("create_report"), {
                busy = true; report = null
                scope.launch {
                    report = vm.createDiagnosticReport(version) { progress = it }
                    progress = ""; busy = false
                }
            }, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !busy && vm.device != null)
            if (vm.device == null) Text(tx("connect_first"), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (busy && progress.isNotBlank()) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BusySpinner(); Text("  $progress", color = C.Muted, fontSize = 12.sp, maxLines = 2)
            }
            report?.let { r ->
                Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(320.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg)
                    .verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(r, color = C.Text, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
            GhostButton(tx("share_report"), {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Cambridge Connect Legacy diagnostic report")
                    .putExtra(Intent.EXTRA_STREAM, ReportProvider.uri())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(i, tx("share_report")))
            }, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = report != null && !busy)
            val note = androidx.compose.ui.text.buildAnnotatedString {
                // The translated sentence has {0} where the link word goes.
                val parts = tx("bug_note", "\u0000").split("\u0000")
                append(parts[0])
                withLink(androidx.compose.ui.text.LinkAnnotation.Url(WEBSITE,
                    androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = C.Accent,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)))) { append(tx("bug_note_link")) }
                append(parts.getOrElse(1) { "" })
            }
            Text(note, color = C.Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
