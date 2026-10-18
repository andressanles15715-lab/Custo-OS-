# Accueil TV – launcher Android TV (style tvOS)

Un écran d'accueil pour Android TV : fond dégradé qui prend la couleur de l'app
sélectionnée, grand visuel et titre en haut, rangée d'apps aux coins arrondis
avec zoom, ombre et reflet au focus, horloge et date en haut à droite.

## Compiler et installer

1. Ouvrir ce dossier dans **Android Studio** (Hedgehog ou plus récent) et laisser
   la synchronisation Gradle se terminer.
2. Brancher la TV en ADB (Options pour développeurs → Débogage USB / réseau),
   puis lancer l'app (bouton Run), ou générer l'APK :
   `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
3. Installation manuelle : `adb install app-debug.apk`

## L'utiliser comme écran d'accueil

Appuyer sur la touche Accueil de la télécommande : Android propose de choisir
l'écran d'accueil. Sur certaines TV, il faut forcer le choix :

```
adb shell cmd package set-home-activity com.smartformatique.tvhome/.MainActivity
```

## Commandes

- Flèches : naviguer · OK : ouvrir l'app
- Appui long sur OK : infos de l'app (désinstaller, forcer l'arrêt…)
- Bouton Accueil : revient au début de la rangée
- Roue crantée en haut à droite : réglages Android
- Les apps récemment ouvertes passent en premier

## Personnaliser

Tout est dans `app/src/main/java/com/smartformatique/tvhome/MainActivity.kt`
(tailles des cartes, intensité du zoom, couleurs, textes dans `res/values/strings.xml`).
