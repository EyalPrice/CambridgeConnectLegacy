package io.github.eyalprice.ccl

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Home-screen widget: now playing, play/pause, volume, up to four inputs and power. */
class StereoWidget : AppWidgetProvider() {

    /** Only the fields the widget shows, so it is redrawn just when they change. */
    data class Snapshot(
        val connected: Boolean, val title: String, val sub: String, val volume: Int, val playing: Boolean,
        val standby: Boolean, val inputs: List<Input>, val source: Int, val power: String,
    ) {
        companion object {
            fun of(st: StereoState): Snapshot {
                val title = when {
                    st.name.isBlank() -> "Cambridge Connect Legacy"
                    st.isSpotify -> st.playback.title.ifBlank { "Spotify" }
                    st.hasMetadata -> st.playback.title.ifBlank { st.sourceName }
                    else -> st.sourceName
                }
                val sub = when {
                    st.name.isBlank() -> tx("tap_to_open")
                    st.conn != Conn.OK -> tx("not_reachable", st.name)
                    st.standby -> tx("standby_sub", st.name)
                    else -> "${st.name} · ${st.sourceName}"
                }
                val playing = st.transport == "PLAYING" || (st.isSpotify && st.playback.spotifyPlaying) ||
                    (!st.isSpotify && st.playback.state.equals("Playing", true))
                return Snapshot(st.conn == Conn.OK, title, sub, st.volume, playing, st.standby,
                    pickInputs(st.inputs, st.widgetInputs), st.sourceNumber, if (st.conn == Conn.OK) st.power else "")
            }

            /** The inputs the user put on the widget (0–4), in the same order as the tiles. */
            private fun pickInputs(all: List<Input>, chosen: List<Int>): List<Input> =
                all.filter { it.number in chosen }.take(MAX_WIDGET)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, Snapshot.of(context.stereo.state.value))
        val pending = goAsync()
        scope.launch {
            try { context.stereo.connectIfNeeded() } finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (!action.startsWith(PREFIX)) return
        val pending = goAsync()
        scope.launch {
            try {
                val s = context.stereo
                if (s.connectIfNeeded()) {
                    when (action) {
                        VOL_UP -> s.volumeStep(1)
                        VOL_DOWN -> s.volumeStep(-1)
                        TOGGLE -> s.togglePlay()
                        POWER -> s.togglePower()
                        INPUT -> s.selectInput(intent.getIntExtra("n", 0))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val PREFIX = "io.github.eyalprice.ccl.widget."
        private const val VOL_UP = PREFIX + "VOL_UP"
        private const val VOL_DOWN = PREFIX + "VOL_DOWN"
        private const val TOGGLE = PREFIX + "TOGGLE"
        private const val POWER = PREFIX + "POWER"
        private const val INPUT = PREFIX + "INPUT"

        private fun pi(ctx: Context, action: String, code: Int, n: Int = 0): PendingIntent =
            PendingIntent.getBroadcast(ctx, code,
                Intent(ctx, StereoWidget::class.java).setAction(action).putExtra("n", n),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun render(ctx: Context, s: Snapshot) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StereoWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, if (I18n.rtl) R.layout.widget_stereo_rtl else R.layout.widget_stereo)
            v.setTextViewText(R.id.w_title, s.title)
            v.setTextViewText(R.id.w_sub, s.sub)
            v.setTextViewText(R.id.w_vol, if (s.connected) tx("vol_short", s.volume) else tx("vol_dash"))
            v.setImageViewResource(R.id.w_play, if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_icon, open)
            v.setOnClickPendingIntent(R.id.w_title, open)
            v.setOnClickPendingIntent(R.id.w_play, pi(ctx, TOGGLE, 10))
            v.setOnClickPendingIntent(R.id.w_up, pi(ctx, VOL_UP, 11))
            v.setOnClickPendingIntent(R.id.w_down, pi(ctx, VOL_DOWN, 12))
            v.setOnClickPendingIntent(R.id.w_power, pi(ctx, POWER, 13))
            // Power icon follows the stereo: green power = on, amber moon = standby, grey = off / not reachable.
            when (s.power) {
                "ON" -> { v.setImageViewResource(R.id.w_power, android.R.drawable.ic_lock_power_off); v.setInt(R.id.w_power, "setColorFilter", 0xFF4CC38A.toInt()) }
                "IDLE" -> { v.setImageViewResource(R.id.w_power, R.drawable.ic_moon); v.setInt(R.id.w_power, "setColorFilter", 0xFFE8A33D.toInt()) }
                else -> { v.setImageViewResource(R.id.w_power, android.R.drawable.ic_lock_power_off); v.setInt(R.id.w_power, "setColorFilter", 0xFF9BA0AA.toInt()) }
            }
            // Buttons share the row equally: 1 fills it, 2 take half each, up to 4. Unused ones are hidden.
            val slots = listOf(R.id.w_in0, R.id.w_in1, R.id.w_in2, R.id.w_in3)
            slots.forEachIndexed { i, id ->
                val inp = s.inputs.getOrNull(i)
                if (inp == null) {
                    v.setViewVisibility(id, android.view.View.GONE)
                } else {
                    v.setViewVisibility(id, android.view.View.VISIBLE)
                    v.setTextViewText(id, inp.name)
                    v.setInt(id, "setBackgroundResource", if (inp.number == s.source) R.drawable.widget_btn_on else R.drawable.widget_btn)
                    v.setTextColor(id, if (inp.number == s.source) 0xFF0B0C0F.toInt() else 0xFFECEDEF.toInt())
                    v.setOnClickPendingIntent(id, pi(ctx, INPUT, 20 + i, inp.number))
                }
            }
            mgr.updateAppWidget(ids, v)
        }
    }
}
