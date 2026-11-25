package io.github.eyalprice.ccl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a media session for the stereo while it is on, so that the lock screen and the
 * notification shade show its controls and the phone's volume keys work with the screen locked.
 */
class StereoService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private lateinit var volume: VolumeProvider
    private val images by lazy { ImageLoader(this) }
    private var artUrl = ""
    private var art: Bitmap? = null
    private var shown: Triple<String, String, Boolean>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Stereo controls", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Lock-screen and notification controls for your stereo"
            setShowBadge(false)
        })
        val st0 = stereo.state.value
        session = MediaSession(this, "CambridgeConnectLegacy").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = stereo.togglePlay()
                override fun onPause() = stereo.togglePlay()
                override fun onSkipToNext() = stereo.transport("Next")
                override fun onSkipToPrevious() = stereo.transport("Previous")
                override fun onStop() = stereo.transport("Stop")
            })
            setSessionActivity(openApp())
        }
        volume = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, st0.volumeMax, st0.volume) {
            override fun onAdjustVolume(direction: Int) { if (direction != 0) stereo.volumeStep(direction) }
            override fun onSetVolumeTo(v: Int) { stereo.setVolume(v) }
        }
        session.setPlaybackToRemote(volume)
        session.isActive = true
        val n = build(st0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
        stereo.serviceActive = true
        scope.launch { stereo.state.collect { update(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PREV -> stereo.transport("Previous")
            ACT_NEXT -> stereo.transport("Next")
            ACT_TOGGLE -> stereo.togglePlay()
            ACT_CLOSE -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stereo.serviceActive = false
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun isPlaying(st: StereoState) =
        st.transport == "PLAYING" || (st.isSpotify && st.playback.spotifyPlaying) || (st.isRadio && st.playback.state.equals("Playing", true))

    private fun titles(st: StereoState): Pair<String, String> = when {
        st.isSpotify -> st.playback.title.ifBlank { "Spotify" } to listOf(st.playback.artist, st.playback.album).filter { it.isNotBlank() }.joinToString(" · ")
        st.hasMetadata -> st.playback.title.ifBlank { st.sourceName } to "${st.name} · ${st.sourceName}"
        else -> st.sourceName to st.name
    }

    private fun update(st: StereoState) {
        if (st.conn == Conn.OK && st.standby) { stopSelf(); return }
        volume.currentVolume = st.volume
        val (title, sub) = titles(st)
        val playing = isPlaying(st)
        val url = if (st.hasMetadata) st.playback.artUrl else ""
        if (url != artUrl) {
            artUrl = url
            art = null
            if (url.isNotBlank()) scope.launch {
                val r = images.execute(ImageRequest.Builder(this@StereoService).data(url).allowHardware(false).size(512).build())
                art = ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                shown = null
                update(stereo.state.value)
            }
        }
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, st.name)
            .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .build())
        val key = Triple(title, sub, playing)
        if (key != shown) {
            shown = key
            getSystemService(NotificationManager::class.java).notify(ID, build(st))
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun action(icon: Int, label: String, act: String, code: Int) = Notification.Action.Builder(
        Icon.createWithResource(this, icon), label,
        PendingIntent.getService(this, code, Intent(this, StereoService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    private fun build(st: StereoState): Notification {
        val (title, sub) = titles(st)
        val playing = isPlaying(st)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(sub)
            .setLargeIcon(art)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACT_PREV, 1))
            .addAction(if (playing) action(android.R.drawable.ic_media_pause, "Pause", ACT_TOGGLE, 2)
                       else action(android.R.drawable.ic_media_play, "Play", ACT_TOGGLE, 2))
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACT_NEXT, 3))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Close", ACT_CLOSE, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    companion object {
        private const val CHANNEL = "stereo"
        private const val ID = 7
        private const val ACT_PREV = "ccl.PREV"
        private const val ACT_NEXT = "ccl.NEXT"
        private const val ACT_TOGGLE = "ccl.TOGGLE"
        private const val ACT_CLOSE = "ccl.CLOSE"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, StereoService::class.java))
            } catch (e: Exception) {
                // Android may refuse while the app is in the background; it is retried next time the app opens.
            }
        }
    }
}
