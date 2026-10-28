# Test 3DL

## Comandi

Servono JDK 17+ (verificato con 21), il Maven Wrapper e Docker Linux attivo
per i test MySQL. MySQL installato localmente non viene usato dalla suite.

```powershell
.\mvnw.cmd -B clean verify
node --test src/test/js/*.test.cjs
```

Su Linux/macOS usare ./mvnw. Non servono credenziali locali per i test.
Non aggiungere override esterni di datasource/profili Spring alla configurazione
Maven di IntelliJ. I test usano il profilo test dal loro classpath.

## Cosa esegue la build normale

- Test unitari monetari, DTO, firma HMAC, scadenza e validazione dei preventivi.
- Test Spring/H2 di carrello, checkout, rollback, idempotenza e sicurezza HTTP.
- Test MySQL 8.4.3 reali per V1, mapping Hibernate, vincoli, snapshot, concorrenza,
  lock, rollback e coerenza dei prezzi: nessuna loro asserzione è stata rimossa.
- Test della configurazione dev: database dedicato, segreto richiesto, rifiuto di
  profili impliciti, URL alternativi, schema update/create e impostazioni pericolose.
- Dopo package, Failsafe avvia il normale JAR con dev contro MySQL temporaneo:
  Flyway V1 automatico, Hibernate validate, HTTP, demo opzionale e riavvio senza
  perdita di dati. Un altro container verifica il rifiuto di un database non vuoto
  privo di history, preservando la riga di prova.

Sono rimossi i test dell'infrastruttura eliminata: approvazioni, store NTFS,
orchestrazione del rilascio e grant DBA avanzati. Nessun test del checkout
funzionale è stato disabilitato o escluso dal POM.
La build non dipende più da Windows, NTFS o PowerShell.

## Isolamento

H2 è esclusivamente in memoria ed è una dipendenza di test.
I container MySQL sono temporanei, con tmpfs, credenziali casuali, reuse=false,
label di ownership e porte dinamiche vincolate a 127.0.0.1.
Le fixture verificano container, database e UUID prima delle operazioni SQL.
Testcontainers/Ryuk rimuove le sole risorse della prova.
Nessun test usa il database persistente dev o il vecchio ecommerce.
Flyway clean non viene usato; le verifiche funzionali mantengono transazioni
e connessioni separate per i casi concorrenti.

Report: target/surefire-reports e target/failsafe-reports.
Surefire/Failsafe devono chiudere con zero fallimenti, errori e skip.
L'assenza di Docker fa fallire i test MySQL: non viene nascosta con skip.

## Esecuzioni mirate

```powershell
.\mvnw.cmd -B '-Dtest=CheckoutMySqlIntegrationTests,CheckoutV2MySqlSchemaTests' test
.\mvnw.cmd -B '-Dtest=DatabaseSafetyEnvironmentTests' test
```

Per lo sviluppo dell'applicazione da IntelliJ vedere CHECKOUT_V2_DEVELOPMENT.md.
Per una semplice compilazione usare il goal compile; clean verify esegue tutta
la suite funzionale e verifica anche il JAR normale.
