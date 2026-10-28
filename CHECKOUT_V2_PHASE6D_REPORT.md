# Checkout V2 — Fase 6D: esecutore unico e store Windows protetto

## Perimetro e stato

Worktree: C:\Users\sapor\.codex\worktrees\checkout-v2-integration\ecommerce.
Branch feature/checkout-v2-integration; HEAD invariato
3ed237335b9aa3bec578ce80bf45337f1f2f1c55.
Nessun commit, merge, rebase, push o deploy.
Questa fase implementa l'Alternativa A approvata, senza configurare una destinazione persistente.

## Protocollo implementato

Preflight → approvazione esplicita → Migrate → grant manuali DBA → Verify.

Il piano canonico JSON lega host configurato e IP effettivo, porta, UUID server,
database, CURRENT_USER dei due account, versioni MySQL/Flyway, SHA-256 dei byte
esatti V1, stato iniziale vuoto, nonce casuale e scadenza (900 secondi).
Include identità store/operatore/nodo. Modifiche di contenuto o formattazione
non superano fingerprint e confronto canonico.

L'approvazione richiede il fingerprint esplicito dalla sessione Windows
dell'operatore autorizzato. Il fingerprint non autentica: l'autorità deriva
dalla sessione fidata e dal diritto esclusivo sullo store. Nessun endpoint di
approvazione remoto o approvazione automatica.

Lo store richiede Windows, disco locale fisso NTFS e percorso nel profilo
operatore senza reparse point. DACL protetta dall'ereditarietà, proprietario
corrente e unica ACE FullControl per quel SID, verificati ad ogni accesso.
Init non riutilizza né ripara store esistenti. Record append-only, CREATE_NEW,
FileShare.None, WriteThrough e Flush(true); nessun record viene sovrascritto.
Record corrotti/parziali bloccano il workflow.

Migrate registra start per destinazione e used per piano prima delle scritture
DB. Ricontrolla configurazione, endpoint autenticato, account, versioni, V1 e
schema vuoto. I byte V1 acquisiti sono gli stessi consegnati al ResourceProvider
Flyway; non viene riletto un file potenzialmente cambiato durante l'esecuzione.
Rimossi il vecchio parametro ConfirmNewDatabase e l'API migrate(Runnable).

Mutex Windows globale per UUID/DB, indipendente dal percorso store, più advisory
lock MySQL. Controllo di CONNECTION_ID/IS_USED_LOCK sulla sessione proprietaria,
monitor periodico e verifica prima delle nuove operazioni SQL del runner.
Le proxy JDBC mantengono il controllo anche attraverso metadati, statement e
result set, impedendo unwrap e cambi di catalogo. Perdita del controllo
irreversibile per quel lease; nuova operazione rifiutata.

Successo/fallimento sono registrati per destinazione. Start senza esito dopo
arresto forzato è stato incerto. Ogni tentativo precedente blocca nuovi piani e
Migrate; nessun retry/reset/repair automatico. Verify è ripetibile solo per il
piano riuscito, con grant corretti e checksum V1 valido.

## Privilegi

Prima della V1 runtime ha solo USAGE e viene autenticato senza selezionare il DB.
Dopo V1 il DBA concede CRUD individualmente a customers, products, cart,
cart_products, orders, order_items, e SELECT soltanto a flyway_schema_history.
La lettura della history serve al gate obbligatorio prima di JPA.
Il migratore mantiene gli otto grant qualificati sul solo DB dedicato.
Il runner non crea database/account e non concede privilegi.

Ogni connessione verifica CURRENT_USER, CURRENT_ROLE, mandatory_roles e l'intero
SHOW GRANTS. Rifiuta grant globali, db.* runtime, altri database, privilegi DDL,
GRANT OPTION, scritture sulla history, ruoli attivi e assegnati ma attivabili.

## Prove negative dei tre rilievi originali

| Rilievo 6C | Prova nel codice di test |
| --- | --- |
| Approvazione non vincolata alla destinazione | planRequiresTrustedApprovalAndIsSingleUse; changedHostWithSameDatabaseIsRejectedBeforeConnection; destinationPortChangedAfterApprovalIsRejectedWithoutDdl; changedUuidAfterPreflightIsRejected; changedUuidAndV1AreRejected; expiredPlanIsRejectedWithoutDdl |
| Perdita controllo lock durante callback | lostLockBeforeFirstDdlStopsAndPersistsFailure; lostLockDuringPartialMigrationBlocksNextSqlAndRetry; concurrentMigratorsCannotBothEnterCallback; interruptedProcessLeavesDurableConsumedAndUncertainState |
| Runtime scrive history | historyReallyRejectsAllWritesAndRuntimeDdl verifica diniego reale INSERT/UPDATE/DELETE history e CREATE; overbroadHistoryAndGlobalGrantsAreRejected; globalPrivilegesAreRejectedEvenWithOtherwiseValidRuntimeGrants; activeAndActivatableRolesAreRejected |

WindowsPlanStoreTests verifica fingerprint non sufficiente, modifica del piano
su disco, formattazione non canonica, ACL debole realmente applicata su NTFS,
copia in store diverso, due CREATE_NEW concorrenti con un solo vincitore,
consumo corrotto non riparabile e mutex comune fra store differenti.
La prova di arresto forzato avvia e interrompe soltanto il proprio processo
figlio, dopo un DDL nel container temporaneo; start/used persistono senza falso
successo e la destinazione resta bloccata. I test di perdita lock terminano
soltanto la sessione del lock di quel database effimero, previa identità del
container e del server. Non esiste un KILL nel tooling operativo.

## File

In questa fase sono aggiornati:

- src/main/java/com/TreDL/ecommerce/release/ReleaseDatabase.java
- src/main/java/com/TreDL/ecommerce/release/FreshDatabaseMigration.java
- tools/checkout-release.ps1
- tools/checkout-release/CheckoutMigration.java
- src/test/java/com/TreDL/ecommerce/release/ReleaseMySqlTests.java
- CHECKOUT_V2_RELEASE_GUIDE.md
- CHECKOUT_V2_PHASE6B_REPORT.md (avviso di documento storico superato)

Nuovi per questa fase:

- src/main/java/com/TreDL/ecommerce/release/MigrationPlan.java
- src/main/java/com/TreDL/ecommerce/release/WindowsPlanStore.java
- src/main/java/com/TreDL/ecommerce/release/MigrationLease.java
- src/main/java/com/TreDL/ecommerce/release/MigrationWorkflow.java
- tools/checkout-release/NativeStore.ps1
- tools/checkout-release/release-static-tests.ps1
- src/test/java/com/TreDL/ecommerce/release/WindowsPlanStoreTests.java
- src/test/java/com/TreDL/ecommerce/release/InterruptedMigrationWorker.java
- CHECKOUT_V2_PHASE6D_REPORT.md

Restano le modifiche approvate della 6B: pom.xml; fixture
CheckoutV2MySqlSchemaTests.java, CheckoutV2SchemaContract.java,
MySqlCheckoutTestDatabase.java; ReleaseEnvironment.java,
ReleaseConfiguration.java, ReleaseEnvironmentTests.java;
META-INF/spring.factories e application-checkout-release.properties.
Nessun file target/, log, manifest temporaneo o database viene aggiunto.
V1 e configurazioni default/dev non modificate.

## Esecuzione e risultati

| Verifica finale | Passati | Fallimenti | Errori | Saltati |
| --- | ---: | ---: | ---: | ---: |
| Maven .\mvnw.cmd -B clean verify | 277 | 0 | 0 | 0 |
| Di cui ReleaseEnvironmentTests | 42 | 0 | 0 | 0 |
| Di cui ReleaseMySqlTests | 24 | 0 | 0 | 0 |
| Di cui WindowsPlanStoreTests, NTFS reale | 7 | 0 | 0 | 0 |
| Di cui schema MySQL | 13 | 0 | 0 | 0 |
| Di cui Spring/MySQL checkout | 41 | 0 | 0 | 0 |
| Client completo node --test src/test/js/*.test.cjs | 19 | 0 | 0 | 0 |

Conteggi letti dai 17 report JUnit XML dopo clean verify; le sottosuite sono
incluse nei 277. Sono 22 casi in più rispetto ai 255 della Fase 6B.
Il comando CLI reale è compilato e verificato nella suite MySQL,
incluso il classpath senza classi/risorse di test del progetto.

PowerShell: 40 controlli statici release passati, parser incluso; controlli
statici del runner browser e nove scenari mock di processo passati.
JAR ispezionato: registrazione ReleaseEnvironment e profilo presenti, nessuna
libreria Flyway o classe di test/launcher nel runtime normale.
Nessuna asserzione di sicurezza indebolita e nessun test disabilitato.

Tutti i MySQL usati sono mysql:8.4.3 temporanei con tmpfs, loopback dinamico,
reuse=false e Ryuk attivo. Identità/ownership controllate nelle fixture prima
del provisionamento e delle operazioni amministrative. Al controllo finale:
zero container residui per le etichette com.3dl.checkout.release-test,
com.3dl.checkout.schema-test e com.3dl.checkout.run.
Nessun database persistente contattato, creato, migrato o modificato.
Nessun accesso allo storico, nessun Flyway clean, nessuna pulizia generica.

Git finale: quattro file tracked modificati e ventuno nuovi (25 file totali),
nessuna modifica staged. Nessun artefatto generato nei candidati Git.
Controllo diff/check e scansione di firme di chiavi private/access token superati;
credenziali dei test generate in memoria, nessun valore reale nei file.
V1, default/dev, ui_experiments e feature/checkout-v2-schema invariati:
- ui_experiments: 64d41e8e039603d00d99fdbd2a38da4fe848d06f
- feature/checkout-v2-schema: d01bdf6c153b33f2c52e584b4b148d5df068549a
HEAD resta 3ed237335b9aa3bec578ce80bf45337f1f2f1c55.


Le esecuzioni intermedie hanno individuato: limite eccessivo al setCatalog
richiesto da Flyway anche per il medesimo database, manifest Flyway privo della
versione e passaggio errato del percorso nel test ACL. Correzioni mirate:
consentito solo il medesimo catalogo approvato, versione dal VersionPrinter del
JAR fissato, script di fixture passato con -File. Non sono state indebolite le
precondizioni, le asserzioni di sicurezza o disabilitati test.

## Limiti e prerequisiti operativi

- Il protocollo è cooperativo. Un solo nodo autorizzato e store canonico vanno
  imposti dall'infrastruttura; DBA e account operatore/amministratori sono fidati.
  Non protegge da un amministratore che alteri binari, permessi o ledger.
- Il lease viene controllato prima delle operazioni; non è un fencing token
  imposto atomicamente dal motore a ogni DDL. Un DDL già iniziato può completarsi
  dopo perdita del controllo: nessuna promessa di cancellazione immediata.
- MySQL può lasciare DDL parziali. Preservare schema e ledger, bloccare attivazione
  e richiedere un piano DBA separato. Nessuna recovery distruttiva automatica.
- Flush durevoli seguono il contratto NTFS/storage; crash di processo provato,
  nessuna simulazione di power-loss o garanzia contro guasti fisici/rollback disco.
- Metadati vuoti significano nessun oggetto visibile: l'inventario e l'esclusione
  dello storico devono essere attestati dal DBA, con finestra DDL esclusiva.
- Resta il warning di supporto dichiarato Flyway 10.20.1/MySQL 8.4.3; qualificazione
  formale e TLS/proxy finali restano prerequisiti di rilascio.
- Qualificato su Windows NTFS; altri OS/store non sono supportati e falliscono.
- Checkout V2 non esegue pagamenti reali. Nessun rilascio è autorizzato da questo report.

## Giudizio

**READY FOR COMMIT**, per la revisione del codice dell'Alternativa A qualificata su Windows NTFS. Nessun rischio bloccante individuato nel perimetro implementato. Non equivale ad autorizzazione al rilascio: i prerequisiti infrastrutturali e la qualificazione Flyway/TLS restano obbligatori. Attendere approvazione prima di qualsiasi commit o operazione successiva.
