package fr.adriendelage.bascule5g

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var tvShizuku: TextView
    private lateinit var btnShizuku: Button
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var edRsrp: EditText
    private lateinit var edSinr: EditText
    private lateinit var edBad: EditText
    private lateinit var edRetry: EditText
    private lateinit var edRetryMax: EditText

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refresh() }
    }
    private val shizukuBinder = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val shizukuDead = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()

        Shizuku.addRequestPermissionResultListener(shizukuResult)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinder)
        Shizuku.addBinderDeadListener(shizukuDead)

        val perms = mutableListOf(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AppState.status.collect { tvStatus.text = it } }
                launch { AppState.log.collect { tvLog.text = it.joinToString("\n").ifEmpty { "—" } } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuResult)
        Shizuku.removeBinderReceivedListener(shizukuBinder)
        Shizuku.removeBinderDeadListener(shizukuDead)
        super.onDestroy()
    }

    private fun refresh() {
        val running = Shell.running()
        val granted = Shell.granted()
        tvShizuku.text = when {
            !running -> "Shizuku : non démarré. Ouvre l'appli Shizuku et lance-la (débogage sans fil)."
            !granted -> "Shizuku : démarré, mais pas encore autorisé pour Bascule 5G."
            else -> "Shizuku : OK ✓"
        }
        btnShizuku.visibility = if (running && !granted) View.VISIBLE else View.GONE
        btnToggle.text = if (prefs.enabled) "Arrêter la bascule automatique" else "Démarrer la bascule automatique"
    }

    // ---------- Actions ----------

    private fun toggleService() {
        if (prefs.enabled) {
            SwitcherService.stop(this)
            prefs.enabled = false
        } else {
            if (!Shell.granted()) {
                toast("Shizuku doit être démarré et autorisé d'abord")
                return
            }
            saveSettings(quiet = true)
            prefs.enabled = true
            SwitcherService.start(this)
        }
        refresh()
    }

    private fun manual(on: Boolean) {
        if (!Shell.granted()) { toast("Shizuku n'est pas prêt"); return }
        thread {
            val (ok, out) = Radio.set5g(Radio.slot(), on)
            AppState.log("Test manuel ${if (on) "5G" else "4G"} : ${if (ok) "OK" else "ÉCHEC – $out"}")
        }
        if (prefs.enabled) toast("Le service tourne : il pourra rebasculer de lui-même")
    }

    private fun diagnostic() {
        if (!Shell.granted()) { toast("Shizuku n'est pas prêt"); return }
        thread {
            val slot = Radio.slot()
            val r = Radio.rawGet(slot)
            val mask = Radio.getMask(slot)
            AppState.log(
                "Diagnostic slot $slot : code ${r.code}, sortie « ${r.out.take(200)} » → " +
                    (mask?.let { "5G ${if ((it and Radio.NR_BIT) != 0L) "autorisée" else "retirée"}" } ?: "illisible")
            )
        }
    }

    private fun saveSettings(quiet: Boolean = false) {
        fun v(e: EditText, def: Int) = e.text.toString().trim().toIntOrNull() ?: def
        prefs.rsrpMin = v(edRsrp, prefs.rsrpMin).coerceIn(-140, -60)
        prefs.sinrMin = v(edSinr, prefs.sinrMin).coerceIn(-20, 30)
        prefs.badSeconds = v(edBad, prefs.badSeconds).coerceIn(5, 300)
        prefs.retryMin = v(edRetry, prefs.retryMin).coerceIn(1, 120)
        prefs.retryMaxMin = v(edRetryMax, prefs.retryMaxMin).coerceIn(prefs.retryMin, 240)
        fillSettings()
        if (!quiet) toast("Réglages enregistrés")
    }

    private fun batterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        val intent = if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }

    // ---------- Interface ----------

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        title("Bascule 4G / 5G")
        tvShizuku = text("")
        btnShizuku = button("Autoriser Bascule 5G dans Shizuku") { Shizuku.requestPermission(1) }
        btnToggle = button("") { toggleService() }

        section("État")
        tvStatus = text("").apply { typeface = Typeface.MONOSPACE }

        section("Réglages")
        edRsrp = field("RSRP 5G minimum (dBm, ex. -115)")
        edSinr = field("SINR 5G minimum (dB, ex. -3)")
        edBad = field("Durée de mauvais signal avant bascule (s)")
        edRetry = field("Premier nouvel essai 5G après (min)")
        edRetryMax = field("Délai max entre deux essais (min)")
        fillSettings()
        button("Enregistrer les réglages") { saveSettings() }

        section("Tests")
        button("Passer en 5G maintenant") { manual(true) }
        button("Passer en 4G maintenant") { manual(false) }
        button("Diagnostic (lire le mode réseau)") { diagnostic() }
        button("Batterie : ne pas restreindre l'appli") { batterySettings() }

        section("Journal")
        tvLog = text("—").apply { typeface = Typeface.MONOSPACE; textSize = 12f }
    }

    private fun fillSettings() {
        edRsrp.setText(prefs.rsrpMin.toString())
        edSinr.setText(prefs.sinrMin.toString())
        edBad.setText(prefs.badSeconds.toString())
        edRetry.setText(prefs.retryMin.toString())
        edRetryMax.setText(prefs.retryMaxMin.toString())
    }

    private fun title(s: String) = text(s).apply { textSize = 24f; setTypeface(typeface, Typeface.BOLD) }

    private fun section(s: String) = text(s).apply {
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 8)
    }

    private fun text(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
        root.addView(this)
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
        root.addView(this)
    }

    private fun field(label: String): EditText {
        text(label).textSize = 13f
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            root.addView(this)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
