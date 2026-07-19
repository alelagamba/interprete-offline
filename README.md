# Interprete Offline

App Android per interpretazione vocale offline: registra la voce, trascrive localmente, traduce sul dispositivo e, quando disponibile, legge la traduzione ad alta voce.

La repo e volutamente minimale: contiene solo l'app Android finale e gli AAR precompilati necessari al runtime speech/TTS. Non contiene il monorepo sorgente `speech-android`.

## Download APK

Quando esiste almeno una Release, l'APK pubblico si scarica da:

```text
https://github.com/alelagamba/interprete-offline/releases/latest/download/interprete-offline-release.apk
```

Su Android puo essere necessario autorizzare l'installazione da browser o file manager.

## Primo Avvio

Apri l'app con connessione internet, preferibilmente Wi-Fi. L'app prepara automaticamente i modelli necessari per la coppia linguistica selezionata:

- Parakeet TDT per speech-to-text locale;
- ML Kit Translation per la traduzione offline;
- voci Piper per italiano, inglese e tedesco quando la voce in-app e attiva.

Dopo la preparazione iniziale, la coppia linguistica scaricata funziona anche in modalita aereo. Se cambi lingua, riapri l'app online una volta per scaricare i modelli mancanti.

## Lingue

Stabili:

- Italiano
- Inglese
- Tedesco

Le impostazioni includono un toggle per lingue extra beta. Le lingue extra dipendono dai modelli ML Kit disponibili e vengono scaricate solo quando selezionate.

## Build Locale

```bash
git clone https://github.com/alelagamba/interprete-offline.git
cd interprete-offline
./gradlew :app:assembleDebug
```

Su macOS, se Java non e configurato ma Android Studio e installato:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

APK debug:

```text
app/build/outputs/apk/debug/interprete-offline-debug.apk
```

## Release GitHub

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

## Componenti Binari

La cartella `app/libs/` contiene:

- `speech-sdk-release.aar`: SDK speech Android precompilato con Parakeet TDT e patch `TRANSCRIBE_ONLY`/`TtsModel.NONE` usate dall'app.
- `sherpa-onnx-static-link-onnxruntime-1.13.4.aar`: runtime sherpa-onnx usato per le voci Piper in-app.

Questa scelta mantiene la repo app-only e installabile senza includere il sorgente completo dell'SDK speech.

## Limiti Noti

- APK pubblico pensato per dispositivi Android `arm64-v8a`.
- La traduzione usa ML Kit Translation: e offline e veloce, ma non ha la qualita di un LLM grande.
- Le lingue extra sono beta.
- Il primo download puo richiedere tempo perche i modelli vocali sono grandi.

## Licenza E Attribuzione

Il runtime speech deriva da `soniqo/speech-android` e `speech-core`, distribuiti con licenza Apache 2.0. Questa repo mantiene la licenza e l'attribuzione del progetto originale.
