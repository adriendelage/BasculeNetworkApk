# Bascule 5G

Bascule automatiquement entre « 5G préféré » et « 4G préféré » selon la qualité réelle de la 5G
(RSRP, SINR, test Internet sur le réseau mobile), puis retente la 5G régulièrement.

## Fonctionnement

- **En 5G** : si la 5G est mauvaise pendant 20 s (RSRP < -115 dBm, SINR < -3 dB, hors service,
  ou plus d'Internet mobile), passage en **4G préféré**.
- **En 4G** : nouvel essai 5G après 3 min, puis 6, 12… jusqu'à 30 min tant que l'essai échoue.
  Un essai réussi remet le délai à 3 min. Perdre le Wi-Fi (tu quittes un lieu) déclenche un essai dans 1 min.
- Tous les seuils se règlent dans l'appli.

Le changement de mode réseau est réservé au système : l'appli passe par **Shizuku**
(sans root) pour lancer `cmd phone set-allowed-network-types-for-users`, la même chose que l'écran « Mode réseau ».

## Installation

1. Installe **Shizuku** (Play Store), active *Options de développement > Débogage sans fil*,
   puis démarre Shizuku depuis son appli (« Démarrer via débogage sans fil »).
2. Compile et installe l'APK :
   - Android Studio : ouvrir le dossier, *Run* ;
   - ou pousse le dossier sur GitHub : le workflow *Build APK* produit l'APK dans l'onglet *Actions*.
3. Dans Bascule 5G : accepte les permissions, appuie sur « Autoriser Bascule 5G dans Shizuku ».
4. **Teste d'abord** « Passer en 4G maintenant » puis « Passer en 5G maintenant » et vérifie dans
   *Paramètres > Connexions > Réseaux mobiles > Mode réseau* que le réglage suit. Le journal affiche la sortie.
5. Appuie sur « Batterie : ne pas restreindre l'appli » (Samsung tue sinon les services en arrière-plan),
   puis « Démarrer la bascule automatique ».

## Après un redémarrage

Shizuku sans root s'arrête au redémarrage du téléphone. Relance-le depuis son appli
(ou active son option de démarrage automatique si ta version la propose) ; Bascule 5G
repart tout seul et attend Shizuku.
