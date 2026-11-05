package fr.adriendelage.bascule5g

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exécution de commandes avec l'identité "shell" (uid 2000) via Shizuku. */
object Shell {
    data class Result(val code: Int, val out: String)

    // Shizuku.newProcess est privée depuis l'API 13 mais reste fonctionnelle.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    fun running(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun granted(): Boolean = running() && try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun run(cmd: String): Result = try {
        val p = newProcess.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText().trim()
        Result(p.waitFor(), out)
    } catch (t: Throwable) {
        Result(-1, (t.cause ?: t).toString())
    }
}

/**
 * Pilotage des types de réseau autorisés (ce que fait l'écran "Mode réseau").
 * Masque = TelephonyManager.NETWORK_TYPE_BITMASK_* : bit (type - 1).
 */
object Radio {
    const val NR_BIT = 1L shl 19        // NETWORK_TYPE_NR (20)
    private const val LTE_BITS = (1L shl 12) or (1L shl 18) // LTE + LTE_CA
    const val ALL = (1L shl 20) - 1

    fun slot(): Int {
        val s = SubscriptionManager.getSlotIndex(SubscriptionManager.getDefaultDataSubscriptionId())
        return if (s >= 0) s else 0
    }

    fun rawGet(slot: Int): Shell.Result =
        Shell.run("cmd phone get-allowed-network-types-for-users -s $slot")

    /** Lit le masque actuel ; null si la sortie n'est pas interprétable. */
    fun getMask(slot: Int): Long? {
        val r = rawGet(slot)
        if (r.code != 0) return null
        Regex("[01]{12,}").findAll(r.out).lastOrNull()?.let {
            return it.value.toLongOrNull(2)
        }
        return Regex("\\d{4,}").findAll(r.out).lastOrNull()?.value?.toLongOrNull()
    }

    /** Autorise (true) ou retire (false) la 5G. Renvoie (succès, sortie brute). */
    fun set5g(slot: Int, on: Boolean): Pair<Boolean, String> {
        val cur = getMask(slot)?.takeIf { it != 0L } ?: ALL
        val target = if (on) cur or NR_BIT or LTE_BITS else (cur and NR_BIT.inv()) or LTE_BITS
        val r = Shell.run(
            "cmd phone set-allowed-network-types-for-users -s $slot ${java.lang.Long.toBinaryString(target)}"
        )
        val failed = r.code != 0 ||
            Regex("(?i)error|exception|unknown|invalid|denied|usage").containsMatchIn(r.out)
        if (failed) return false to r.out.ifEmpty { "code ${r.code}" }
        // Vérification : relecture du masque
        val after = getMask(slot)
        val ok = after == null || ((after and NR_BIT) != 0L) == on
        return ok to (if (ok) "OK" else "masque relu inattendu : ${java.lang.Long.toBinaryString(after!!)}")
    }
}

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Seuil RSRP 5G (dBm) en dessous duquel la 5G est jugée inutilisable. */
    var rsrpMin: Int
        get() = sp.getInt("rsrpMin", -115)
        set(v) = sp.edit().putInt("rsrpMin", v).apply()

    /** Seuil SINR 5G (dB). */
    var sinrMin: Int
        get() = sp.getInt("sinrMin", -3)
        set(v) = sp.edit().putInt("sinrMin", v).apply()

    /** Durée pendant laquelle la 5G doit rester mauvaise avant de basculer (s). */
    var badSeconds: Int
        get() = sp.getInt("badSeconds", 20)
        set(v) = sp.edit().putInt("badSeconds", v).apply()

    /** Délai avant de retenter la 5G (min), doublé à chaque échec jusqu'au max. */
    var retryMin: Int
        get() = sp.getInt("retryMin", 3)
        set(v) = sp.edit().putInt("retryMin", v).apply()

    var retryMaxMin: Int
        get() = sp.getInt("retryMaxMin", 30)
        set(v) = sp.edit().putInt("retryMaxMin", v).apply()
}

/** État partagé service → écran. */
object AppState {
    val status = MutableStateFlow("Service arrêté")
    val log = MutableStateFlow<List<String>>(emptyList())

    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)

    fun log(msg: String) {
        log.value = (listOf("${fmt.format(Date())}  $msg") + log.value).take(80)
    }
}
