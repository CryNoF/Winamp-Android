package cl.winampyt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Mantiene la app viva con la pantalla apagada y da controles en la notificación,
 * en la pantalla de bloqueo y con los botones de audífonos / Bluetooth.
 */
class PlayerService : Service() {

    companion object {
        const val ACT_PREV = "cl.winampyt.PREV"
        const val ACT_TOGGLE = "cl.winampyt.TOGGLE"
        const val ACT_NEXT = "cl.winampyt.NEXT"
        const val ACT_CLOSE = "cl.winampyt.CLOSE"
        private const val CH = "winampyt_playback"
        private const val NID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private var wake: PowerManager.WakeLock? = null
    private var playing = false
    private var title = "WinampYT"
    private var missed = 0

    private val poll = object : Runnable {
        override fun run() {
            queryState()
            main.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServerHolder.start(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Reproducción", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )

        session = MediaSession(this, "WinampYT").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = key("x")
                override fun onPause() = key("c")
                override fun onStop() = key("v")
                override fun onSkipToNext() = key("b")
                override fun onSkipToPrevious() = key("z")
            })
            isActive = true
        }
        updateSession()
        startForeground(NID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        main.postDelayed(poll, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PREV -> key("z")
            ACT_NEXT -> key("b")
            ACT_TOGGLE -> key(if (playing) "c" else "x")
            ACT_CLOSE -> {
                MainActivity.instance?.finishAndRemoveTask()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        wake?.let { if (it.isHeld) it.release() }
        session.release()
        super.onDestroy()
    }

    /** Simula la tecla del reproductor (Z anterior, X play, C pausa, V stop, B siguiente). */
    private fun key(k: String) {
        Bridge.web?.get()?.evaluateJavascript(
            "window.dispatchEvent(new KeyboardEvent('keydown',{key:'$k'}))", null
        )
        main.postDelayed({ queryState() }, 600)
    }

    private fun queryState() {
        val web = Bridge.web?.get()
        if (web == null) {
            // La ventana ya no existe (cerrada desde recientes): no tiene sentido seguir sonando.
            if (++missed > 3) stopSelf()
            return
        }
        missed = 0
        val js = "(function(){try{var a=window.__wy.audio;" +
            "return JSON.stringify({p:!a.paused&&!a.ended,t:window.__wy.title()||''})}catch(e){return '{}'}})()"
        web.evaluateJavascript(js) { raw ->
            try {
                val s = JSONTokener(raw ?: "").nextValue() as? String ?: return@evaluateJavascript
                val o = JSONObject(s)
                val p = o.optBoolean("p", false)
                val t = o.optString("t", "").ifBlank { "WinampYT" }
                if (p != playing || t != title) {
                    playing = p
                    title = t
                    updateSession()
                    getSystemService(NotificationManager::class.java).notify(NID, notification())
                    setWake(p)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun setWake(on: Boolean) {
        if (on) {
            if (wake == null) {
                wake = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WinampYT:play")
            }
            wake?.let { if (!it.isHeld) it.acquire() }
        } else {
            wake?.let { if (it.isHeld) it.release() }
        }
    }

    private fun updateSession() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )
        session.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "WinampYT")
                .build()
        )
    }

    private fun action(act: String, icon: Int, label: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(), Intent(this, PlayerService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(if (playing) "Reproduciendo" else "En pausa")
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(ACT_PREV, android.R.drawable.ic_media_previous, "Anterior"))
            .addAction(
                action(
                    ACT_TOGGLE,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pausa" else "Play"
                )
            )
            .addAction(action(ACT_NEXT, android.R.drawable.ic_media_next, "Siguiente"))
            .addAction(action(ACT_CLOSE, android.R.drawable.ic_menu_close_clear_cancel, "Cerrar"))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
