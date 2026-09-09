# Codex Limits Widget — PoC Android

PoC volutamente piccolo per verificare una sola ipotesi:

> Un'app Android può autenticarsi tramite il device-code flow Codex e leggere
> direttamente i limiti da `https://chatgpt.com/backend-api/wham/usage`
> senza dipendere da un PC.

## Cosa fa

1. Richiede un device code a OpenAI.
2. Apre `https://auth.openai.com/codex/device` nel browser.
3. Completa lo scambio OAuth.
4. Mantiene access token/account ID **solo in RAM**.
5. Esegue `GET /backend-api/wham/usage`.
6. Se riceve HTTP 200:
   - calcola `100 - used_percent`;
   - salva **solo le percentuali non sensibili**;
   - aggiorna un widget Android.
7. Se riceve 401/403:
   - il PoC si ferma;
   - non tenta scraping, cookie extraction o altri workaround.

## Perché i token NON vengono salvati

Questo non è ancora il prodotto finale. Prima dimostriamo che l'accesso diretto
Android → usage funziona realmente con l'account. Solo dopo ha senso aggiungere:

- Android Keystore + AES/GCM;
- refresh token;
- aggiornamento periodico;
- refresh al tap sul widget;
- gestione logout/revoca;
- UX definitiva.

## Build

Il progetto è una base Android Studio, senza dipendenze HTTP esterne.

Configurazione:
- Kotlin
- minSdk 26
- target/compile SDK 35
- Android Gradle Plugin 8.7.3
- Kotlin plugin 2.0.21

Aprire la cartella in Android Studio e fare Sync/Build. Se Android Studio propone
un aggiornamento compatibile di Gradle/AGP, accettarlo solo se necessario.

## Test

1. Installa l'APK debug.
2. Apri **Codex Limits PoC**.
3. Tocca `1. Richiedi codice OpenAI`.
4. Tocca `2. Apri pagina OpenAI`.
5. Accedi a OpenAI e inserisci il codice.
6. Torna nell'app.
7. Tocca `3. Completa login e leggi limiti`.

### Esito A — SUCCESSO

Vedrai:
- piano;
- percentuale usata;
- percentuale residua;
- reset epoch;
- widget aggiornato.

A quel punto si può passare alla fase 2 produttiva.

### Esito B — HTTP 401/403 su USAGE

È un risultato utile: significa che, con l'autenticazione ottenuta dal device flow,
l'endpoint web non accetta la richiesta diretta dal PoC. In tal caso NON ha senso
investire nel widget definitivo finché non scegliamo un'altra superficie supportata.

## Sicurezza del PoC

- nessuna password passa nell'app;
- nessun access/refresh/id token viene salvato;
- nessun token viene mostrato nei log UI;
- nessun PC o server personale è richiesto;
- le sole percentuali residue vengono persistite localmente.
