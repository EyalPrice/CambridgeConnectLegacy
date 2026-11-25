package io.github.eyalprice.ccl

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object C {
    val Bg = Color(0xFF0B0C0F)
    val Surface = Color(0xFF15171C)
    val Surface2 = Color(0xFF1A1C22)
    val Bar = Color(0xFF0F1014)
    val Line = Color(0xFF23262D)
    val Line2 = Color(0xFF2A2D35)
    val Text = Color(0xFFECEDEF)
    val Muted = Color(0xFF9BA0AA)
    val Accent = Color(0xFFE8A33D)
    val AccentBg = Color(0xFF1F1A12)
    val Green = Color(0xFF4CC38A)
    val Danger = Color(0xFFC62F35)
    val Warn = Color(0xFF2A2112)
}

@Composable
fun CclTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent, onPrimary = C.Bg, background = C.Bg, onBackground = C.Text,
            surface = C.Surface, onSurface = C.Text, surfaceVariant = C.Surface2, onSurfaceVariant = C.Muted,
            outline = C.Line2, error = C.Danger,
        ),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.3.sp, modifier = modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
fun Panel(modifier: Modifier = Modifier, active: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) C.AccentBg else C.Surface)
            .border(1.dp, if (active) C.Accent else C.Line, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
fun CircleIconButton(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = C.Text,
                     size: Dp = 44.dp, filled: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) C.Text else C.Surface)
            .border(if (filled) 0.dp else 1.dp, C.Line2, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (filled) C.Bg else tint, modifier = Modifier.size(size * 0.45f)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopBar(title: String, st: StereoState, onSound: () -> Unit, onSettings: () -> Unit,
           onPower: () -> Unit, onPowerHold: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(
                    when { st.conn != Conn.OK -> C.Muted; st.standby -> C.Accent; else -> C.Green }))
                Spacer(Modifier.width(6.dp))
                Text("${st.name.ifBlank { "Not connected" }} · Vol ${st.volume}", color = C.Muted, fontSize = 12.sp)
            }
        }
        LabeledButton(Icons.Outlined.Tune, "Sound", onSound)
        LabeledButton(Icons.Outlined.Settings, "Settings", onSettings)
        Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(C.Surface).border(1.dp, C.Line2, CircleShape)
                    .combinedClickable(onClick = onPower, onLongClick = onPowerHold)
                    .semantics { contentDescription = "Power. Tap for standby, hold for full off" },
                contentAlignment = Alignment.Center,
            ) {
                val (icon, tint) = when {
                    st.conn != Conn.OK -> Icons.Filled.PowerOff to C.Muted
                    st.power == "ON" -> Icons.Filled.PowerSettingsNew to C.Green
                    st.power == "IDLE" -> Icons.Filled.Bedtime to C.Accent
                    st.power == "OFF" -> Icons.Filled.PowerOff to C.Muted
                    else -> Icons.Filled.PowerSettingsNew to C.Muted
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(when { st.conn != Conn.OK -> "Power"; st.power == "IDLE" -> "Standby"; st.power == "OFF" -> "Off"; else -> "On" },
                color = C.Text, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun LabeledButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.width(58.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(C.Surface).border(1.dp, C.Line2, CircleShape),
            contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.Text, modifier = Modifier.size(20.dp)) }
        Text(label, color = C.Text, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun Banner(text: String, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(C.Warn).border(1.dp, Color(0xFF5A4318), RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color(0xFFF2D6A2), fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) {
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.Bg),
                contentPadding = PaddingValues(horizontal = 14.dp)) { Text(action, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun ArtBox(url: String, size: Dp, radius: Dp = 12.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(C.Bar).border(1.dp, C.Line, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center) {
        AppMark(size * 0.4f, faded = true)
        if (url.isNotBlank()) {
            AsyncImage(model = url, contentDescription = "Artwork", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The app icon: a white disc with a dark centre on navy. */
@Composable
fun AppMark(size: Dp, faded: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        if (!faded) drawRoundRect(Color(0xFF1F2A44), size = this.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.2f))
        drawCircle(if (faded) Color(0xFF3A3E48) else Color.White, radius = r * 0.5f)
        drawCircle(if (faded) C.Bar else Color(0xFF1F2A44), radius = r * 0.2f)
    }
}

@Composable
fun MiniPlayer(st: StereoState, title: String, onOpen: () -> Unit, vm: StereoViewModel) {
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))
            .background(C.Surface2).border(1.dp, C.Line2, RoundedCornerShape(14.dp)).padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            ArtBox(if (st.hasMetadata) st.playback.artUrl else "", 38.dp, 8.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (st.tuning) CircularProgressIndicator(Modifier.padding(start = 6.dp).size(12.dp), color = C.Accent, strokeWidth = 1.5.dp)
                }
                Text("${st.sourceName} · Vol ${st.volume}", color = C.Muted, fontSize = 11.sp, maxLines = 1)
            }
        }
        TextButton(onClick = { vm.volumeStep(-1) }) { Text("−", color = C.Text, fontSize = 20.sp) }
        TextButton(onClick = { vm.volumeStep(1) }) { Text("+", color = C.Text, fontSize = 20.sp) }
        val playing = st.transport == "PLAYING" || st.playback.spotifyPlaying
        val ctx = androidx.compose.ui.platform.LocalContext.current
        PlayButton(playing, st.playPending, 44.dp) { playPressed(ctx, st, vm, playing) }
    }
}

/** Rotary knob: drag around it clockwise to raise, anticlockwise to lower. */
@Composable
fun VolumeKnob(value: Int, max: Int, muted: Boolean, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(value)
    val change0 by rememberUpdatedState(onChange)
    val degPerStep = 270f / max.coerceAtLeast(1) * 1.4f
    Box(modifier.size(232.dp).semantics { contentDescription = "Volume $value of $max" }, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(max) {
                var last = 0f
                var acc = 0f
                var local = 0
                detectDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        local = current
                        acc = 0f
                        last = Math.toDegrees(atan2((o.y - size.height / 2f).toDouble(), (o.x - size.width / 2f).toDouble())).toFloat()
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    val p = change.position
                    val a = Math.toDegrees(atan2((p.y - size.height / 2f).toDouble(), (p.x - size.width / 2f).toDouble())).toFloat()
                    var d = a - last
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f
                    last = a
                    acc += d
                    val steps = (acc / degPerStep).toInt()
                    if (steps != 0) {
                        acc -= steps * degPerStep
                        val nv = (local + steps).coerceIn(0, max)
                        if (nv != local) {
                            local = nv
                            change0(nv)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    change.consume()
                }
            }
        ) {
            val stroke = 8.dp.toPx()
            val r = size.minDimension / 2 - stroke
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(r * 2, r * 2)
            drawArc(C.Line, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(if (muted) C.Muted else C.Accent, 135f, 270f * value / max.coerceAtLeast(1), false, tl, sz,
                style = Stroke(stroke, cap = StrokeCap.Round))
            val inner = r * 0.77f
            drawCircle(C.Surface2, inner)
            drawCircle(if (dragging) C.Accent else C.Line2, inner, style = Stroke(2.dp.toPx()))
            val ang = Math.toRadians((135.0 + 270.0 * value / max.coerceAtLeast(1)))
            val ir = inner * 0.85f
            drawCircle(C.Accent, 6.dp.toPx(), Offset(center.x + (ir * cos(ang)).toFloat(), center.y + (ir * sin(ang)).toFloat()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", color = C.Text, fontSize = 46.sp, fontWeight = FontWeight.SemiBold)
            Text(if (muted) "MUTED" else "VOLUME", color = C.Muted, fontSize = 11.sp, letterSpacing = 1.3.sp)
        }
    }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, enabled: Boolean = true,
            minusLabel: String = "−", plusLabel: String = "+") {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, enabled = enabled, border = BorderStroke(1.dp, C.Line2),
            modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text(minusLabel, color = C.Text, fontSize = if (minusLabel.length > 1) 14.sp else 20.sp, fontWeight = FontWeight.SemiBold) }
        Text(value, color = C.Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = onPlus, enabled = enabled, border = BorderStroke(1.dp, C.Line2),
            modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text(plusLabel, color = C.Text, fontSize = if (plusLabel.length > 1) 14.sp else 20.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = C.Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Text))
    }
}

@Composable
fun RowItem(title: String, value: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = C.Muted, fontSize = 14.sp)
        if (onClick != null) Text("  ›", color = C.Muted, fontSize = 14.sp)
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface).border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, o ->
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(9.dp)).background(if (i == selected) C.Accent else Color.Transparent)
                .clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(o, color = if (i == selected) C.Bg else C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.Bg)) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, C.Line2), colors = ButtonDefaults.outlinedButtonColors(containerColor = C.Surface, contentColor = C.Text)) {
        Text(text, fontSize = 14.sp)
    }
}


/** Play/pause button; a thin spinning ring shows while the stereo confirms the change. */
@Composable
fun PlayButton(playing: Boolean, pending: Boolean, size: Dp, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 8.dp)) {
        if (pending) CircularProgressIndicator(Modifier.size(size + 8.dp), color = C.Accent, strokeWidth = 2.5.dp)
        CircleIconButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play",
            onClick, filled = true, size = size)
    }
}


/** Search box for long lists: filters as you type, with a clear button. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String = "Search", modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, placeholder = { Text(hint) }, singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear search") } },
        modifier = modifier.fillMaxWidth())
}


/**
 * Play/pause from the app. On the Spotify input, pressing play also opens the Spotify app
 * (launching it, or bringing it to the front), because Spotify Connect playback starts there.
 */
fun playPressed(ctx: android.content.Context, st: StereoState, vm: StereoViewModel, playing: Boolean) {
    // Open the Spotify app only when no phone is connected to the stereo's Spotify yet;
    // a paused session that is already connected just resumes here.
    if (st.isSpotify && !playing && !st.playback.spotifyActive) {
        val open = ctx.packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (open == null) {
            android.widget.Toast.makeText(ctx, "Spotify isn't installed on this phone.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            open.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ctx.startActivity(open)
        }
        return
    }
    vm.togglePlay()
}
