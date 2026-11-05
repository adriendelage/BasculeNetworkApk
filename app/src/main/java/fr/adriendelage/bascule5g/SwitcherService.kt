package fr.adriendelage.bascule5g

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.app.ServiceCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Surveille la qualité de la 5G et bascule automatiquement :
 *  - 5G autorisée → si 5G mauvaise (signal faible, hors service ou plus de data) pendant N s → 4G
 *  - 4G → retente la 5G après un délai (doublé à chaque échec), ou 1 min après la perte du Wi-Fi
 */
class SwitcherService : Service() {

    companion object {
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "bascule"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 5_000L
        private const val GRACE_MS = 15_000L      // temps laissé au modem pour s'accrocher après bascule
        private const val TRIAL_MS = 45_000L      // durée d'un essai 5G
        private const val PROBE_EVERY_MS = 15_000L
        private const val PROBE_URL = "https://www.google.com/generate_204"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SwitcherService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SwitcherService::class.java).setAction(ACTION_STOP))
        }
    }

    private enum class Mode { UNKNOWN, G5, G4 }

    private val handler = Handler(Looper.getMainLooper())
    private val shellExec = Executors.newSingleThreadExecutor()
    private val probeExec = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var tm: TelephonyManager
    private lateinit var cm: ConnectivityManager
    private var slot = 0

    // Mesures radio
    private var nrRsrp: Int? = null
    private var nrSinr: Int? = null
    private var lteRsrp: Int? = null
    private var displayOnNr: Boolean? = null
    private var inService = true

    // Réseaux
    private var cellNet: Network? = null
    private var wifiUp = false
    private var probeFails = 0
    private var probeBusy = false
    private var lastProbeAt = 0L
    private var lastProbeOk: Boolean? = null

    // Automate
    private var mode = Mode.UNKNOWN
    private var busy = false
    private var badTicks = 0
    private var graceUntil = 0L
    private var trialUntil = 0L
    private var nextRetryAt = 0L
    private var backoffMs = 0L
    private var lastReasons = ""
    private var lastNotif = ""

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ---------- Callbacks téléphonie ----------

    private inner class RadioCb : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener {

        override fun onSignalStrengthsChanged(ss: SignalStrength) {
            val nr = ss.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull()
            val lte = ss.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
            handler.post {
                nrRsrp = nr?.ssRsrp?.takeIf { it != CellInfo.UNAVAILABLE }
                nrSinr = nr?.ssSinr?.takeIf { it != CellInfo.UNAVAILABLE }
                lteRsrp = lte?.rsrp?.takeIf { it != CellInfo.UNAVAILABLE }
            }
        }

        override fun onServiceStateChanged(st: ServiceState) {
            val ok = st.state == ServiceState.STATE_IN_SERVICE
            handler.post { inService = ok }
        }
    }

    private inner class DisplayCb : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) {
            val nr = d.networkType == TelephonyManager.NETWORK_TYPE_NR ||
                d.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                d.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
            handler.post { displayOnNr = nr }
        }
    }

    private val radioCb = RadioCb()
    private val displayCb = DisplayCb()

    private val cellCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { handler.post { cellNet = n } }
        override fun onLost(n: Network) { handler.post { if (cellNet == n) cellNet = null } }
    }

    private val wifiCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { handler.post { wifiUp = true } }
        override fun onLost(n: Network) {
            handler.post {
                wifiUp = false
                // Perte du Wi-Fi = on a probablement changé de lieu : on retente vite la 5G
                if (mode == Mode.G4) {
                    val soon = SystemClock.elapsedRealtime() + 60_000
                    if (nextRetryAt > soon) {
                        nextRetryAt = soon
                        AppState.log("Wi-Fi perdu → nouvel essai 5G dans 1 min")
                    }
                }
            }
        }
    }

    // ---------- Cycle de vie ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tm = getSystemService(TelephonyManager::class.java)
        cm = getSystemService(ConnectivityManager::class.java)
        slot = Radio.slot()
        backoffMs = prefs.retryMin * 60_000L

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bascule 4G/5G", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotif("Démarrage…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        val exec = mainExecutor
        tm.registerTelephonyCallback(exec, radioCb)
        try {
            tm.registerTelephonyCallback(exec, displayCb)
        } catch (e: SecurityException) {
            AppState.log("Permission téléphone absente : détection 5G par le signal uniquement")
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(), cellCb
        )
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(), wifiCb
        )

        AppState.log("Service démarré (SIM slot $slot)")
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.enabled = true
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { tm.unregisterTelephonyCallback(radioCb) }
        runCatching { tm.unregisterTelephonyCallback(displayCb) }
        runCatching { cm.unregisterNetworkCallback(cellCb) }
        runCatching { cm.unregisterNetworkCallback(wifiCb) }
        shellExec.shutdown()
        probeExec.shutdown()
        AppState.status.value = "Service arrêté"
        AppState.log("Service arrêté")
        super.onDestroy()
    }

    // ---------- Logique ----------

    private fun onNr(): Boolean = displayOnNr ?: (nrRsrp != null)

    private fun airplane(): Boolean =
        Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun dataEnabled(): Boolean = try { tm.isDataEnabled } catch (_: Throwable) { true }

    /** Raisons pour lesquelles la 5G est jugée mauvaise (vide = OK). */
    private fun badReasons(): List<String> {
        val r = mutableListOf<String>()
        if (!inService) {
            r += "hors service"
            return r
        }
        if (!onNr()) return r   // en 4G : rien à reprocher à la 5G
        nrRsrp?.let { if (it < prefs.rsrpMin) r += "RSRP 5G $it dBm" }
        nrSinr?.let { if (it < prefs.sinrMin) r += "SINR 5G $it dB" }
        if (dataEnabled()) {
            if (probeFails >= 2) r += "pas d'Internet en 5G"
            if (!wifiUp && cellNet == null) r += "pas de data mobile"
        }
        return r
    }

    private fun maybeProbe(now: Long) {
        val net = cellNet ?: return
        if (probeBusy || now - lastProbeAt < PROBE_EVERY_MS || !dataEnabled()) return
        probeBusy = true
        lastProbeAt = now
        probeExec.execute {
            val ok = try {
                val c = net.openConnection(URL(PROBE_URL)) as HttpURLConnection
                c.connectTimeout = 4_000
                c.readTimeout = 4_000
                c.useCaches = false
                val code = c.responseCode
                c.disconnect()
                code == 204
            } catch (_: Exception) {
                false
            }
            handler.post {
                probeBusy = false
                lastProbeOk = ok
                probeFails = if (ok) 0 else probeFails + 1
            }
        }
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()

        if (!Shell.granted()) {
            publish("En attente de Shizuku (démarré + autorisé)")
            return
        }
        if (airplane()) {
            publish("Mode avion : en pause")
            return
        }
        if (mode == Mode.UNKNOWN) {
            if (!busy) readCurrentMode()
            return
        }
        if (busy) return

        if (mode == Mode.G5) maybeProbe(now)

        val reasons = badReasons()
        lastReasons = reasons.joinToString(", ")

        when (mode) {
            Mode.G5 -> {
                val inTrial = trialUntil > 0
                if (now < graceUntil) {
                    badTicks = 0
                } else if (reasons.isNotEmpty()) {
                    badTicks++
                } else {
                    badTicks = 0
                    if (inTrial && now >= trialUntil) {
                        trialUntil = 0
                        backoffMs = prefs.retryMin * 60_000L
                        AppState.log("Essai 5G réussi, on reste en 5G")
                    }
                }
                if (badTicks * TICK_MS >= prefs.badSeconds * 1000L) {
                    if (inTrial) {
                        backoffMs = (backoffMs * 2).coerceAtMost(prefs.retryMaxMin * 60_000L)
                    } else {
                        backoffMs = prefs.retryMin * 60_000L
                    }
                    trialUntil = 0
                    nextRetryAt = now + backoffMs
                    switchTo(Mode.G4, "5G mauvaise ($lastReasons)")
                }
            }
            Mode.G4 -> {
                if (now >= nextRetryAt) {
                    trialUntil = now + GRACE_MS + TRIAL_MS
                    switchTo(Mode.G5, "essai de la 5G")
                }
            }
            Mode.UNKNOWN -> Unit
        }
        publish(null)
    }

    private fun readCurrentMode() {
        busy = true
        shellExec.execute {
            val mask = Radio.getMask(slot)
            handler.post {
                busy = false
                mode = if (mask == null || (mask and Radio.NR_BIT) != 0L) Mode.G5 else Mode.G4
                if (mode == Mode.G4) nextRetryAt = SystemClock.elapsedRealtime() + 30_000
                graceUntil = SystemClock.elapsedRealtime() + GRACE_MS
                AppState.log(
                    "Mode actuel : " + (if (mode == Mode.G5) "5G préféré" else "4G préféré") +
                        (if (mask == null) " (masque illisible, supposé 5G)" else "")
                )
            }
        }
    }

    private fun switchTo(target: Mode, why: String) {
        busy = true
        shellExec.execute {
            val (ok, out) = Radio.set5g(slot, target == Mode.G5)
            handler.post {
                busy = false
                val label = if (target == Mode.G5) "5G" else "4G"
                if (ok) {
                    mode = target
                    graceUntil = SystemClock.elapsedRealtime() + GRACE_MS
                    badTicks = 0
                    probeFails = 0
                    lastProbeOk = null
                    AppState.log("→ $label : $why")
                } else {
                    trialUntil = 0
                    nextRetryAt = SystemClock.elapsedRealtime() + 60_000
                    AppState.log("Échec de la bascule en $label : $out")
                }
            }
        }
    }

    // ---------- Affichage ----------

    private fun publish(msg: String?) {
        val now = SystemClock.elapsedRealtime()
        val text = msg ?: buildString {
            append("Mode : ")
            append(if (mode == Mode.G5) "5G préféré" else "4G préféré")
            if (trialUntil > 0) append(" (essai en cours)")
            append("\nRéseau : ")
            append(if (!inService) "hors service" else if (onNr()) "5G" else "4G")
            append(if (wifiUp) " · Wi-Fi actif" else "")
            append("\n5G : RSRP ${nrRsrp?.let { "$it dBm" } ?: "—"} · SINR ${nrSinr?.let { "$it dB" } ?: "—"}")
            append("\n4G : RSRP ${lteRsrp?.let { "$it dBm" } ?: "—"}")
            if (mode == Mode.G5) {
                append("\nTest Internet mobile : ")
                append(
                    when (lastProbeOk) {
                        null -> "—"
                        true -> "OK"
                        false -> "échec ×$probeFails"
                    }
                )
                if (lastReasons.isNotEmpty()) append("\nProblème : $lastReasons (${badTicks * TICK_MS / 1000}/${prefs.badSeconds} s)")
            } else {
                val s = ((nextRetryAt - now) / 1000).coerceAtLeast(0)
                append("\nProchain essai 5G dans ${s / 60} min ${s % 60} s")
            }
        }
        AppState.status.value = text

        val short = msg ?: (
            (if (mode == Mode.G5) "5G préféré" else "4G préféré") +
                (if (onNr()) " · 5G" else " · 4G") +
                (nrRsrp?.let { " · RSRP $it" } ?: "") +
                (nrSinr?.let { " · SINR $it" } ?: "")
            )
        if (short != lastNotif) {
            lastNotif = short
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(short))
        }
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Bascule 4G/5G")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
