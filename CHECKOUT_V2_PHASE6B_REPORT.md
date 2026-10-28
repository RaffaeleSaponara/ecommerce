# Checkout V2 — Fase 6B: configurazione per database nuovo

> Documento storico: il protocollo di migrazione e i grant della Fase 6B sono superati dalla Fase 6D. Per le operazioni usare esclusivamente CHECKOUT_V2_RELEASE_GUIDE.md e CHECKOUT_V2_PHASE6D_REPORT.md.

## Stato iniziale e decisione

Branch verificato: feature/checkout-v2-integration.
HEAD iniziale e finale invariato: 3ed237335b9aa3bec578ce80bf45337f1f2f1c55.
Worktree inizialmente pulito. ui_experiments invariato a
64d41e8e039603d00d99fdbd2a38da4fe848d06f; branch schema invariato a
d01bdf6c153b33f2c52e584b4b148d5df068549a.
Destinazione prevista: esclusivamente un database MySQL nuovo e vuoto.
Nessuna importazione, lettura o modifica del database legacy.

## Architettura

Il profilo checkout-release e' distinto da default, dev e checkout-browser.
ReleaseEnvironment e' registrato tramite spring.factories come
EnvironmentPostProcessor: valida dopo ConfigData e prima delle definizioni
applicative. Richiede tutti i parametri DB protetti, HMAC Base64 di almeno 32
byte e il solo profilo esplicito checkout-release. Rifiuta combinazioni e
attivazione tramite profili default impliciti.

Sono richiesti URL, username e password senza fallback. L'URL deve avere un
solo host, porta esplicita, nome coerente con CHECKOUT_V2_DB_NAME e prefisso
checkoutv2 seguito da soli caratteri alfanumerici minuscoli. ecommerce e' quindi
rifiutato su qualsiasi host/porta, non soltanto su localhost:3306. Sono vietati
credenziali nell'URL, failover, creazione schema, sessionVariables e opzioni non
ammesse. Il server deve corrispondere all'UUID protetto atteso e a MySQL 8.4.3.
TLS VERIFY_IDENTITY e' obbligatorio da remoto; REQUIRED e' ammesso soltanto per
loopback letterale nella qualificazione locale.

Le invarianti di rilascio vengono verificate e mantenute a priorita' massima:
Hibernate validate/generate-ddl=false, open-in-view=false, show-sql=false,
Flyway e SQL init automatici disabilitati, DataInitializer/H2 console disabilitati,
cookie HttpOnly/Lax/Secure e backend vincolato a 127.0.0.1. Sono vietati anche
gli alias javax/jakarta di generazione schema JPA che potrebbero sostituire
validate. Nessuna modifica alle configurazioni default/dev o ai loro
comportamenti al di fuori del nuovo profilo.

ReleaseConfiguration registra un datasource esplicito; ogni connessione verifica
URL effettivo, database, UUID, versione, account autenticato e grant. Prima di
JPA la factory richiede esattamente la V1 riuscita nella history. Lo schema
strutturale viene poi validato da Hibernate. Le eccezioni non espongono password,
HMAC o dettagli delle eccezioni JDBC sottostanti. Il segreto non e' generato nel
runtime release: stabilita' fra riavvii/istanze e custodia sono responsabilita'
del secret manager, descritte nella guida.

## Procedura separata di migrazione

Entry point operativo: tools/checkout-release.ps1.
Launcher non incluso nel JAR applicativo: tools/checkout-release/CheckoutMigration.java.
FreshDatabaseMigration contiene il protocollo JDBC condiviso, senza dipendenze
Flyway nel runtime. Il launcher fornisce soltanto l'azione Flyway della V1.

Azioni consentite:

```powershell
.\tools\checkout-release.ps1 -Action Preflight
.\tools\checkout-release.ps1 -Action Migrate -ConfirmNewDatabase $env:CHECKOUT_V2_DB_NAME
.\tools\checkout-release.ps1 -Action Verify
```

I comandi sopra sono documentazione operativa, non migrazioni persistenti
eseguite in questa fase. Il launcher reale e' stato collaudato soltanto con
le credenziali generate dalle fixture su MySQL temporaneo.

Preflight usa sole letture e SHOW GRANTS: controlla identita', grant dei due
account distinti e schema privo di tabelle/viste/routine visibili. Non crea
flyway_schema_history. Il comando mostra la destinazione pubblica e il risultato;
per errori mostra fase/categoria senza valori protetti o stack trace driver.

Migrate acquisisce un advisory lock per database e riesegue preflight prima del
callback. Flyway riceve soltanto la V1 originale, target=1, cleanDisabled=true,
baselineOnMigrate=false, createSchemas=false e outOfOrder=false. Nessuna azione
per creare database/utenti, clean, baseline, repair, drop, truncate o reset.
Una seconda esecuzione Migrate su database popolato viene rifiutata.
Verify controlla identita', grant, history V1 e checksum tramite Flyway validate;
non e' presentato come sostituto della validazione strutturale Hibernate.

Runtime: soltanto SELECT, INSERT, UPDATE, DELETE sul database dedicato.
Migratore: gli stessi privilegi piu' CREATE, ALTER, INDEX, REFERENCES. Non DROP.
Sono rifiutati grant globali/aggiuntivi, cross-database, per singola tabella,
GRANT OPTION, account root e ruoli. Gli account e le password devono essere
distinti. L'applicazione rifiuta la presenza delle credenziali di migrazione.

## File creati

- src/main/java/com/TreDL/ecommerce/release/ReleaseDatabase.java
- src/main/java/com/TreDL/ecommerce/release/ReleaseEnvironment.java
- src/main/java/com/TreDL/ecommerce/release/ReleaseConfiguration.java
- src/main/java/com/TreDL/ecommerce/release/FreshDatabaseMigration.java
- src/main/resources/application-checkout-release.properties
- src/main/resources/META-INF/spring.factories
- tools/checkout-release.ps1
- tools/checkout-release/CheckoutMigration.java
- src/test/java/com/TreDL/ecommerce/release/ReleaseEnvironmentTests.java
- src/test/java/com/TreDL/ecommerce/release/ReleaseMySqlTests.java
- CHECKOUT_V2_RELEASE_GUIDE.md
- CHECKOUT_V2_PHASE6B_REPORT.md

## File modificati

- pom.xml: pin esplicito di flyway.version=10.20.1, gia' usato dal progetto.
- src/test/java/com/TreDL/ecommerce/schema/CheckoutV2MySqlSchemaTests.java:
  tmpfs, loopback, reuse=false, credenziali/schema effimeri e controlli identita'.
- src/test/java/com/TreDL/ecommerce/schema/CheckoutV2SchemaContract.java:
  verifica identita' per connessioni Flyway e prima dei comandi delle fixture;
  H2 accettato solo in memoria, MySQL verificato dalla sottoclasse proprietaria.
- src/test/java/com/TreDL/ecommerce/service/MySqlCheckoutTestDatabase.java:
  loopback e rifiuto di storage persistente/bind mount.

Le modifiche alle fixture preesistenti sono necessarie per applicare i vincoli
tmpfs/loopback/identita' a tutta la regressione. Nessuna asserzione preesistente
e' stata rimossa o indebolita. La suite schema ora fallisce se Docker manca,
anziche' risultare saltata. La V1 resta byte-per-byte invariata rispetto a HEAD.

## Test realmente eseguiti

| Verifica finale | Passati | Fallimenti | Errori | Saltati |
| --- | ---: | ---: | ---: | ---: |
| Maven ./mvnw.cmd -B clean verify | 255 | 0 | 0 | 0 |
| Di cui ReleaseEnvironmentTests | 42 | 0 | 0 | 0 |
| Di cui ReleaseMySqlTests | 9 | 0 | 0 | 0 |
| Di cui Spring/MySQL checkout precedente | 41 | 0 | 0 | 0 |
| Di cui schema MySQL | 13 | 0 | 0 | 0 |
| Client node --test src/test/js/*.test.cjs | 19 | 0 | 0 | 0 |

Conteggi letti dai report JUnit XML finali. Le sottosuite sono comprese nei 255.
Le esecuzioni intermedie hanno rilevato errori di implementazione/import e una
configurazione logger non valida nel nuovo launcher; sono stati corretti senza
modificare V1 o indebolire le precondizioni. I risultati sopra sono quelli della
regressione finale successiva alle correzioni.

I test coprono: impostazioni assenti/non valide, URL legacy e opzioni pericolose,
HMAC valido/assente/non valido senza echo dei valori, combinazioni profili,
default implicito, alias JPA, assenza di credenziali migratore nel runtime,
readiness Spring prima del datasource in caso di HMAC errato, preflight senza
DDL, rifiuto schema non vuoto/UUID errato/grant runtime DDL/cross-database/root,
runtime senza history V1, migrazione reale e Verify, avvio Spring release su
MySQL/Hibernate validate senza seed/Flyway e comando standalone compilato con
classpath privo di risorse di test. E' verificata anche la negazione effettiva
di CREATE TABLE per l'utente runtime. Nessun processo di test usa default/dev.

Parser PowerShell del nuovo wrapper: PASS. Test statici del runner browser:
PASS, compresi i nove scenari mock di processo delle fasi precedenti.
JAR prodotto: profilo/guardie/V1 presenti; nessuna libreria Flyway, classe di
test o launcher di migrazione nel normale runtime.
Smoke del JAR senza impostazioni protette: rifiuto prima di JPA/server, senza
fornire alcuna destinazione DB. Non e' un deploy o un avvio ready.

## Compatibilita', isolamento e limiti

Ambiente verificato: JDK 21.0.9 Eclipse Adoptium, Spring Boot 3.4.2,
Flyway 10.20.1, MySQL 8.4.3 e Connector/J 8.0.32. Nessun aggiornamento automatico.
Resta il warning di supporto dichiarato Flyway/MySQL: il passaggio dei test non
lo trasforma in certificazione ufficiale. La qualificazione e accettazione della
coppia e di TLS/CA dell'infrastruttura finale restano prerequisiti di rilascio.

Solo MySQL temporanei con tmpfs, porte dinamiche su loopback, reuse disabilitato,
Ryuk attivo e identita' attestata nelle fixture finali; altri test su H2 in
memoria. Creazione di database/utenti limitata al provisionamento delle fixture
nei container verificati. Nessuna creazione, migrazione o modifica persistente;
nessuna connessione al MySQL storico, nessun Flyway clean o pulizia generica.
I container delle verifiche vengono rimossi dai rispettivi lifecycle; nessuna
risorsa estranea viene fermata. Le risorse etichettate delle suite risultano
assenti al controllo finale.

Rischi/limiti operativi: il DDL MySQL puo' lasciare uno schema parziale se una
migrazione fallisce; viene preservato e ulteriori Migrate sono rifiutati.
Nessun recupero distruttivo automatico. Restrizioni strette su nomi/grant/ruoli
richiedono qualificazione separata per infrastrutture diverse. Non sono stati
provati certificati/proxy della destinazione finale o un deploy reale.
Non e' aggiunto un feature flag: apertura traffico e attivazione restano passi
operativi espliciti. Default/dev restano legacy e non vanno usati per il rilascio.
Il checkout crea ordini, non pagamenti. Nessuna importazione dei dati legacy.

## Prossimo passo

Revisione e approvazione della configurazione/guida, quindi eventuale commit
locale autorizzato separatamente. Prima del merge/deploy, qualificare artefatto
release e configurazione HTTPS/TLS in un ambiente temporaneo equivalente e
approvare esplicitamente la coppia Flyway/MySQL. Nessun commit, merge, rebase,
push o modifica ad altri worktree e' stato eseguito in questa fase.
