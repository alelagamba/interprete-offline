# Interprete Offline

App Android per tradurre conversazioni vocali senza cloud. L'app registra la voce, trascrive localmente con Parakeet TDT, traduce sul dispositivo con ML Kit e, quando richiesto, legge la traduzione ad alta voce.

L'obiettivo è essere plug-and-play: installi l'APK, apri l'app con internet la prima volta, aspetti la preparazione automatica dei modelli e poi usi le lingue scaricate anche in modalità aereo.

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
5. Premi il microfono e parla, oppure attiva la modalità simultanea dalle impostazioni.
6. Dopo il primo download, la coppia linguistica preparata funziona anche offline.

Al primo avvio il download può essere lento: è normale, perché i modelli vocali e le voci neurali possono essere grandi. Se cambi lingua o motore voce, l'app scaricherà solo i modelli mancanti.

## Modalità

- `Turni`: modalità classica. Premi il microfono, parla, l'app trascrive, traduce e può leggere la traduzione.
- `Simultanea`: modalità sottotitoli. Mentre una persona parla, la traduzione appare progressivamente sullo schermo come un teleprompter. È pensata per seguire guide, lezioni, spiegazioni o conversazioni lunghe.
- `Simultanea con cuffie`: se abiliti la voce simultanea e hai cuffie Bluetooth o cablate collegate, l'app legge in cuffia ogni frase tradotta in ordine.

La modalità simultanea mantiene le traduzioni già completate sullo schermo e mostra la frase corrente come testo provvisorio. In landscape usa più spazio possibile per il testo tradotto.

## Cosa Scarica

L'app prepara automaticamente:

- Parakeet TDT per speech-to-text locale;
- ML Kit Translation per traduzione offline;
- voci neurali leggere Piper per italiano, inglese e tedesco;
- voce naturale Supertonic opzionale, più espressiva, circa 123 MB;
- eventuali voci Android di sistema, se scegli il motore voce del telefono.

I modelli vengono salvati sul dispositivo. La modalità aereo funziona dopo che la coppia linguistica scelta e le eventuali voci sono state preparate almeno una volta online.

## Lingue

Lingue principali:

- Italiano
- Inglese
- Tedesco

Nelle impostazioni puoi attivare anche le lingue extra beta. Sono utili per provare più coppie europee supportate da ML Kit, ma qualità, ASR e voce possono variare rispetto alle tre lingue principali.

## Impostazioni

- Modalità simultanea.
- Ascolto continuo.
- Lettura ad alta voce.
- Voce naturale, voce in-app leggera o voce Android di sistema.
- Voce simultanea in cuffia.
- Tema sistema, chiaro o scuro.
- Dimensione testo.
- Lingue extra beta.

## Funzioni

- Traduzione vocale offline end-to-end.
- Selezione manuale lingua di partenza e arrivo.
- Download automatico dei modelli prima dell'utilizzo.
- Modalità simultanea per sottotitoli live.
- Lettura della traduzione in cuffia durante la modalità simultanea.
- TTS neurale in-app con Piper e Supertonic.
- Fallback al TTS di sistema dove disponibile.
- UI ottimizzata per uso rapido in portrait e per lettura del testo in landscape.

## Limiti Attuali

- L'APK pubblico è pensato per dispositivi Android `arm64-v8a`.
- La traduzione usa ML Kit Translation: è veloce e offline, ma non ha la qualità di un LLM grande.
- La simultanea è ancora una modalità beta: preferisce frasi complete ai frammenti troppo brevi per evitare traduzioni senza contesto.
- La voce simultanea richiede cuffie per evitare che il microfono riascolti la sintesi vocale.
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
git tag v0.1.3
git push origin main
git push origin v0.1.3
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
- `sherpa-onnx-static-link-onnxruntime-1.13.4.aar`: runtime sherpa-onnx per TTS locale.

## Thanks

Il runtime speech deriva dal lavoro di `soniqo/speech-android` e `speech-core`, distribuiti con licenza Apache 2.0. Grazie agli autori originali per la base tecnica su cui è costruita questa app.
