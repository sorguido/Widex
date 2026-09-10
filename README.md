# Widex

Android app + Home widget per visualizzare i limiti residui di Codex senza dipendere da un PC.

## Stato

Il PoC iniziale ha verificato con successo il flusso Android → OpenAI device-code login → `/backend-api/wham/usage`.

La versione 0.2 aggiunge:

- nome app **Widex**;
- login OpenAI richiesto solo al primo collegamento o quando la sessione viene invalidata;
- token conservati cifrati con **Android Keystore + AES/GCM**;
- refresh automatico dell'access token tramite refresh token;
- dashboard con barre per **5 HOURS** e **WEEK**;
- reset mostrati in data/ora leggibile;
- pulsante **Aggiorna** per refresh live in app;
- Home widget con le stesse due barre;
- pulsante ↻ nel widget per refresh live;
- aggiornamento periodico ogni circa 60 minuti tramite `JobScheduler` nativo Android;
- nessuna dipendenza runtime esterna.

## Screenshots

| App | Widget Home |
| --- | --- |
| <img src="screenshots/app.jpeg" alt="Widex app" width="320"> | <img src="screenshots/widget.jpeg" alt="Widex Home widget" width="320"> |

## Comportamento del login

Al primo avvio Widex mostra `Collega account OpenAI`, apre il normale device-code flow di Codex nel browser e attende l'autorizzazione.

Dopo il login le credenziali vengono cifrate localmente. Alle aperture successive l'app mostra direttamente i limiti e rinnova l'access token in background quando necessario.

Se OpenAI invalida il refresh token, Widex elimina le credenziali locali non più utilizzabili e torna alla schermata di collegamento.

## Widget

Il widget Home mostra:

```text
WIDEX · CODEX                 ↻
5 HOURS                    78%
████████████████░░░░
Reset 01:34

WEEK                       43%
█████████░░░░░░░░░░░
Reset lun 14 set · 18:42

                         Agg. 23:48
```

Il tap su `↻` richiede un aggiornamento live. Il tap sul resto del widget apre Widex.

L'aggiornamento orario è intenzionalmente non esatto: Android può differirlo per risparmio energetico/Doze. Il refresh manuale resta disponibile in ogni momento.

## Build

Configurazione attuale:

- Kotlin
- minSdk 26
- target/compile SDK 35
- Java/Kotlin JVM target 17
- Android Gradle Plugin 8.7.3
- Kotlin plugin 2.0.21

Aprire il progetto in Android Studio e usare:

`Build → Generate App Bundles or APKs → Build APK(s)`

APK debug:

`app/build/outputs/apk/debug/app-debug.apk`

## Sicurezza

- username/password/2FA restano nel browser OpenAI;
- access token, refresh token e account ID sono cifrati prima della persistenza;
- la chiave AES è gestita da Android Keystore e non viene salvata nel repository;
- le percentuali e gli orari di reset vengono salvati separatamente come cache non sensibile;
- nessun PC, server domestico o backend personale è richiesto.

## Nota di compatibilità

Widex usa il device-code flow e l'endpoint usage attualmente utilizzati dall'ecosistema Codex. `/backend-api/wham/usage` non è una API pubblica garantita per applicazioni terze: un cambiamento lato OpenAI potrebbe richiedere un aggiornamento dell'app.
