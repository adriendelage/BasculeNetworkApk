package fr.adriendelage.bascule5g

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Relance le service au démarrage du téléphone (et après mise à jour de l'appli) s'il était actif. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).enabled) SwitcherService.start(context)
    }
}
