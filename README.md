# Interprete Offline

App Android per tradurre conversazioni vocali senza cloud. Registra la voce, trascrive localmente, traduce sul dispositivo e, quando disponibile, legge la traduzione ad alta voce.

L'obiettivo è essere plug-and-play: installi l'APK, apri l'app con internet la prima volta, aspetti la preparazione dei modelli e poi puoi usare le lingue scaricate anche in modalità aereo.

## Download

Scarica l'ultima versione da GitHub Releases:

```text
https://github.com/alelagamba/interprete-offline/releases/latest/download/interprete-offline-release.apk
```

Se Android lo richiede, abilita l'installazione da browser o file manager. Dopo l'installazione puoi aprire l'app normalmente dal launcher.

## Primo Utilizzo

1. Apri l'app con connessione internet, meglio se Wi-Fi.
2. Concedi il permesso microfono.
3. Lascia completare la preparazione automatica dei modelli.
4. Scegli lingua di partenza e lingua di arrivo.
5. Premi il pulsante di registrazione e parla.
6. Dopo il primo download, puoi usare la coppia preparata anche offline.

Al primo avvio il download può essere lento: è normale, perché i modelli vocali sono grandi. Se cambi lingua in seguito, l'app scaricherà solo i modelli mancanti per quella nuova coppia.

## Cosa Scarica

L'app prepara automaticamente:

- Parakeet TDT per speech-to-text locale;
- ML Kit Translation per traduzione offline;
- voci Piper per italiano, inglese e tedesco quando la voce in-app è attiva.

I modelli vengono salvati sul dispositivo. La modalità aereo funziona dopo che la coppia linguistica scelta è stata preparata almeno una volta online.

## Lingue

Lingue principali:

- Italiano
- Inglese
- Tedesco

Nelle impostazioni puoi attivare anche le lingue extra beta. Sono utili per provare più coppie, ma qualità e voce possono variare rispetto alle tre lingue principali.

## Funzioni

- Traduzione vocale offline end-to-end.
- Selezione manuale lingua di partenza e arrivo.
- Download automatico dei modelli prima dell'utilizzo.
- TTS in-app con Piper per italiano, inglese e tedesco.
- Fallback al TTS di sistema dove disponibile.
- UI minimale pensata per uso immediato.

## Limiti Attuali

- L'APK pubblico è pensato per dispositivi Android `arm64-v8a`.
- La traduzione usa ML Kit Translation: è veloce e offline, ma non ha la qualità di un LLM grande.
- Il primo setup può richiedere alcuni minuti su connessioni lente.
- Le lingue extra sono beta.

## Build Locale

```bash
git clone https://github.com/alelagamba/interprete-offline.git
cd interprete-offline
./gradlew :app:assembleDebug
```

Su macOS, se Java non è configurato ma Android Studio è installato:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

APK debug:

```text
app/build/outputs/apk/debug/interprete-offline-debug.apk
```

## Pubblicare Una Release

Le Release vengono generate quando viene pushato un tag `v*`:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Il workflow allega:

```text
interprete-offline-release.apk
```

Per firmare l'APK servono questi GitHub Actions secrets:

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

## Note Tecniche

La repo è volutamente app-only. Per evitare di includere il monorepo speech completo, `app/libs/` contiene gli AAR precompilati necessari:

- `speech-sdk-release.aar`: runtime speech Android con Parakeet TDT e patch usate dall'app.
- `sherpa-onnx-static-link-onnxruntime-1.13.4.aar`: runtime sherpa-onnx per le voci Piper.

## Thanks

Il runtime speech deriva dal lavoro di `soniqo/speech-android` e `speech-core`, distribuiti con licenza Apache 2.0. Grazie agli autori originali per la base tecnica su cui è costruita questa app.
