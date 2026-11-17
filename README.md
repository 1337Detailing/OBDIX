# OBDIX

Application Android Kotlin/Jetpack Compose pour le **diagnostic OBD-II générique**. Material You, mode clair/sombre, Bluetooth SPP, BLE (selon GATT du clone), Wi-Fi ELM/TCP et USB série OTG (pilotes courants).

## Fonctions

- Lecture DTC OBD-II émissions (mémorisés, en attente, permanents), effacement OBD-II mode 04 avec confirmation.
- Paramètres temps réel SAE J1979 : disponibilité des PID interrogée avant affichage ; graphiques simples.
- Connectivité ELM327 avec identification, détection automatique du protocole (ATSP0), temporisation et reconnexion pour lectures.
- Écrans Codage et Services **verrouillés** tant qu'un pilote constructeur validé n'est pas disponible. Aucun codage BMW, effacement ciblé par ECU, reset CBS, scan de tous les calculateurs ou flash n'est revendiqué.

## Compiler

Android Studio récent, JDK 17, SDK Android 37. Dans un environnement avec Gradle 9.6 :

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

Le CI `Android CI` vérifie chaque push sur `main`. APK de debug : artefact `obdix-debug`.

## Sortir une version

Créer une fois la clé de signature et la **conserver définitivement** :

```sh
keytool -genkeypair -v -keystore obdix-release.jks -alias obdix -keyalg RSA -keysize 3072 -validity 10000
base64 -w0 obdix-release.jks
```

Dans `Settings > Secrets and variables > Actions`, créer : `KEYSTORE_BASE64` (sortie base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`obdix`), `KEY_PASSWORD`. Ne jamais committer la clé. Puis créer un tag sur `main` :

```sh
git tag v1.0.0 && git push origin v1.0.0
```

Le workflow signe et publie `OBDIX-v1.0.0.apk` dans GitHub Releases. Passer à `v1.0.1` pour la suivante ; `versionCode` est calculé en `major*1000000 + minor*1000 + patch`. Installer les mises à jour par-dessus exige la **même clé, le même applicationId et un versionCode supérieur**.

## Limites et sécurité

ELM327 générique n'ouvre pas les calculateurs châssis/carrosserie BMW et ne fournit pas les jobs EDIABAS, NI le codage. Un câble K+DCAN USB n'est pas un ELM327. Le nom du protocole (CAN/ISO/KWP/J1850) est négocié par le firmware ELM, selon ses capacités réelles. Les PID 0x23 (rail) et 0x5C (huile) ne sont affichés que si déclarés par le véhicule. Aucun débit turbo calculé sans capteur adapté.

Les défauts génériques n'apportent pas le statut actif BMW, ni la gravité exacte, ni des causes propres à chaque modèle. L'effacement mode 04 peut effacer des données d'adaptation émissions et des freeze frames ; ne pas effacer avant diagnostic.

Sources étudiées : voir [NOTICE.md](NOTICE.md). Aucune source GPL externe ni base BMW propriétaire copiée dans cette version.
