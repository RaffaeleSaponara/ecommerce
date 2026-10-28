# Checkout V2 — rilascio su database nuovo, esecutore unico Windows

Checkout V2 crea ordini: **non gestisce pagamenti reali**.
Questa guida descrive una procedura futura soggetta ad autorizzazione della
destinazione. Non autorizza deploy, merge, accesso allo storico o migrazioni persistenti.

## Perimetro qualificato e prerequisiti

- JDK 21, sorgenti --release 17; PowerShell 7.2+ su Windows, disco locale fisso NTFS.
- MySQL 8.4.3; Flyway core/mysql 10.20.1 fissati nel POM; Connector/J 8.0.32.
  Flyway è tooling/test e non è incluso nel JAR applicativo normale.
- Un solo nodo Windows autorizzato e un solo account operatore dedicato.
  Imporre questa esclusività con controllo accessi all'host, distribuzione delle
  credenziali e firewall verso MySQL. Non distribuire le credenziali del migratore
  su altri nodi. Tutti gli esecutori devono utilizzare questo protocollo.
- Store NTFS canonico per l'operatore/nodo, fuori dal repository e sotto il suo
  profilo locale. Nessuna share, unità rimovibile, junction, symlink o reparse point.
  Non cambiare store per aggirare un tentativo precedente.
- Binari Java/PowerShell, sorgenti del tool e script helper devono essere protetti
  dall'amministrazione dell'host. L'account operatore e gli amministratori del
  sistema sono fidati; non è una difesa contro un amministratore ostile.
- DBA: database dedicato realmente nuovo, nessun contenuto preesistente, esclusione
  del database e del server storico secondo inventario approvato. Nome, host, porta,
  certificato TLS e UUID vanno verificati tramite canale DBA fidato.
- Congelare accessi DDL e cambiamenti a utenti/ruoli/grant durante il protocollo.
  Il controllo di schema vuoto usa i metadati visibili all'account: il DBA deve
  attestare anche l'assenza di oggetti non visibili all'account.
- HTTPS tramite proxy verso backend 127.0.0.1:9086. MySQL remoto con CA affidabile
  al truststore Java e sslMode=VERIFY_IDENTITY.

Flyway 10.20.1 segnala che MySQL 8.4 supera il supporto dichiarato testato.
Le prove su 8.4.3 non sono una certificazione ufficiale. Approvare esplicitamente
questa coppia e qualificare TLS/rete finali prima del rilascio; non aggiornare
dipendenze senza una nuova regressione.

## Configurazione protetta

Iniettare credenziali da secret manager nell'ambiente del solo processo previsto,
mai nel repository, negli argomenti Java, nei transcript o nei report.

| Variabile | Significato |
| --- | --- |
| CHECKOUT_V2_PLAN_STORE | Percorso assoluto dello store canonico dell'operatore sul nodo autorizzato |
| CHECKOUT_V2_DB_URL | JDBC single-host, porta esplicita, nome DB esatto |
| CHECKOUT_V2_DB_NAME | Nome dedicato: checkoutv2[a-z0-9]{1,40} |
| CHECKOUT_V2_DB_SERVER_UUID | UUID atteso del server nuovo, comunicato dal DBA |
| CHECKOUT_V2_DB_USERNAME / CHECKOUT_V2_DB_PASSWORD | Account runtime e password |
| CHECKOUT_V2_MIGRATION_USERNAME / CHECKOUT_V2_MIGRATION_PASSWORD | Solo tooling: account e password diversi da runtime |
| CHECKOUT_QUOTE_SECRET_BASE64 | Solo applicazione: Base64 standard di almeno 32 byte casuali |

Esempio descrittivo privo di credenziali:

```text
jdbc:mysql://db.example.invalid:3306/checkoutv2release?sslMode=VERIFY_IDENTITY&connectTimeout=5000&socketTimeout=15000&serverTimezone=UTC
```

URL multipli, parametri sconosciuti, credenziali nell'URL, reconnect, failover,
sessionVariables e creazione automatica del database sono rifiutati.
Sono ammessi solo sslMode, connectTimeout, socketTimeout e serverTimezone=UTC.
sslMode=REQUIRED è ammesso esclusivamente per loopback letterale nei test locali;
su host remoti serve VERIFY_IDENTITY. Non disabilitare la verifica del certificato.
Non esiste fallback verso il datasource applicativo o localhost:3306/ecommerce.

Generazione HMAC in console protetta, senza stampare il valore:

```powershell
$checkoutKeyBytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$env:CHECKOUT_QUOTE_SECRET_BASE64 = [Convert]::ToBase64String($checkoutKeyBytes)
[Array]::Clear($checkoutKeyBytes, 0, $checkoutKeyBytes.Length)
```

Conservarlo nel secret manager e riutilizzarlo fra riavvii/istanze. Non generarlo
ad ogni avvio e non fornirlo al tooling di migrazione.

## Provisionamento DBA prima del Preflight

Il tool non crea database o account e non concede grant. Il DBA deve verificare
identità server e autorizzazione della destinazione prima delle proprie operazioni.
I seguenti sono template, non istruzioni eseguite dal runner. Sostituire i
segnaposto tramite il canale protetto; restringere gli host, senza '%' in produzione.

```sql
CREATE DATABASE `checkoutv2release`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'checkoutv2runtime'@'<AUTHORIZED_RUNTIME_HOST>'
  IDENTIFIED BY '<RUNTIME_PASSWORD_FROM_SECRET_MANAGER>' REQUIRE SSL;
CREATE USER 'checkoutv2migrator'@'<AUTHORIZED_EXECUTOR_HOST>'
  IDENTIFIED BY '<MIGRATION_PASSWORD_FROM_SECRET_MANAGER>' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON `checkoutv2release`.* TO 'checkoutv2migrator'@'<AUTHORIZED_EXECUTOR_HOST>';
```

Prima di V1, runtime deve avere soltanto USAGE (nessun grant applicativo):
MySQL non consente pregrant CRUD per singola tabella inesistente senza CREATE.
Il Preflight autentica runtime senza selezionare un database e controlla UUID,
versione, CURRENT_USER e SHOW GRANTS. Il relativo account deve essere
autenticabile anche dal nodo autorizzato, con host/account DBA pianificati.
Il piano lega l'identità completa CURRENT_USER dei due account.

Il migratore ha esattamente gli otto privilegi indicati sul solo database nuovo,
nessun privilegio amministrativo, globale, DDL aggiuntivo o GRANT OPTION.
Sono rifiutati ruoli attivi, mandatory_roles e ruoli assegnati anche se inattivi
ma attivabili; tutti i grant vengono esaminati, inclusi grant globali e su altre basi.

## Store protetto e approvazione fidata

Il percorso seguente è un esempio sotto il profilo dell'operatore; la directory
padre deve esistere. Impostarlo una sola volta e mantenerlo come store canonico.

```powershell
$env:CHECKOUT_V2_PLAN_STORE = Join-Path $env:USERPROFILE 'CheckoutV2MigrationLedger'
.\tools\checkout-release.ps1 -Action InitStore
```

InitStore rifiuta directory esistenti e non ripara permessi o registri.
Crea directory e file con DACL protetta dall'ereditarietà, proprietario corrente
e unica ACE FullControl per quel SID; ogni accesso ricontrolla ACL, nodo e policy.
Registra un identificativo casuale dello store e un hash dell'identità nodo.
I record sono append-only: CREATE_NEW, FileShare.None, WriteThrough e Flush(true).
Non esistono operazioni di sostituzione, consumo inverso, pulizia o reset.
Record parziali/corrotti sono rifiutati. La durabilità dipende dal contratto NTFS
e dal rispetto dei flush da parte dello storage; non equivale a una prova contro
guasti hardware o rollback amministrativi del disco.

L'approvazione deve essere eseguita interattivamente dall'operatore autorizzato
sul nodo fidato, dopo verifica del contenuto del piano e del fingerprint attraverso
il canale di change approval. Il fingerprint è integrità, **non autenticazione**.
L'autenticazione è la sessione Windows dell'operatore con accesso esclusivo allo
store protetto. Non pubblicare un endpoint remoto di approvazione né eseguire
Approve automaticamente al termine di Preflight.

## Protocollo: Preflight → Approvazione → Migrate → Grant DBA → Verify

Dalla root del progetto, con ambiente protetto e le credenziali corrette:

```powershell
.\tools\checkout-release.ps1 -Action Preflight
# Leggere PLAN <64-hex>; esaminare il corrispondente JSON nello store protetto.
# Dopo approvazione esplicita tramite il canale fidato:
$approvedFingerprint = '<64_HEX_FROM_REVIEWED_PLAN>'
.\tools\checkout-release.ps1 -Action Approve -PlanFingerprint $approvedFingerprint
.\tools\checkout-release.ps1 -Action Migrate -PlanFingerprint $approvedFingerprint
# Ora il DBA applica i soli grant per tabella della sezione successiva.
.\tools\checkout-release.ps1 -Action Verify -PlanFingerprint $approvedFingerprint
```

Il wrapper compila main e launcher con soli JAR dipendenti; classi e risorse di
test del progetto non sono caricate. Non avvia Spring o Docker. Il CLI sopprime
l'output dei driver e stampa solo fase/categoria e fingerprint, senza credenziali.
Il piano contiene destinazione configurata ed endpoint IP/porta effettivo,
UUID, DB, CURRENT_USER migrazione/runtime, versioni esatte, SHA-256 dei byte V1,
stato iniziale, nonce, scadenza di 900 secondi e identità store/operatore/nodo.
Il JSON ha serializzazione canonica e fingerprint SHA-256; copie in altro store
o modifiche anche di formattazione sono rifiutate.

Preflight acquisisce mutex Windows globale per UUID/DB e advisory lock MySQL,
ricontrolla identità, privilegi e schema vuoto; non applica DDL o history.
Approve registra una decisione esplicita solo per piano integro, non scaduto e
non consumato. Il mutex è per destinazione anche fra percorsi store differenti.

Migrate richiede il record di approvazione; registra in modo durevole start per
destinazione e used per piano **prima di ogni scrittura al database**.
Riconnette e confronta host configurato, IP effettivo, porta, UUID, DB, entrambi
gli account autenticati, MySQL/Flyway, hash V1 e schema ancora vuoto.
Qualsiasi divergenza consuma comunque il tentativo e blocca la destinazione.
La V1 viene letta una sola volta in memoria: i byte verificati alimentano Flyway,
senza directory di script modificabili durante la migrazione.

Il controllo combina mutex locale e GET_LOCK MySQL con verifica CONNECTION_ID
e IS_USED_LOCK. Un monitor verifica la sessione; ogni nuova operazione SQL del
runner ricontrolla il controllo prima di procedere. La perdita di sessione o mutex
rende il lease definitivamente invalido. Le connessioni del runner sono protette
contro unwrap e cambio catalogo; non possono sostituire il datasource approvato.
Sono fissati target=1, cleanDisabled=true, baselineOnMigrate=false,
createSchemas=false e outOfOrder=false.

## Grant DBA DOPO V1

Dopo Migrate riuscito, prima di Verify e dell'avvio runtime:

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON `checkoutv2release`.`customers`
  TO 'checkoutv2runtime'@'<AUTHORIZED_RUNTIME_HOST>';
GRANT SELECT, INSERT, UPDATE, DELETE ON `checkoutv2release`.`products`
  TO 'checkoutv2runtime'@'<AUTHORIZED_RUNTIME_HOST>';
GRANT SELECT, INSERT, UPDATE, DELETE ON `checkoutv2release`.`cart`
  TO 'checkoutv2runtime'@'<AUTHORIZED_RUNTIME_HOST>';
GRANT SELECT, INSERT, UPDATE, DELETE ON `checkoutv2release`.`cart_products`
  TO 'checkoutv2runtime'@'<AUTHORIZED_RUNTIME_HOST>';
GRANT SELECT, INSERT, UPDATE, DELETE ON `checkoutv2release`.`orders`
  TO 'checkoutv2runtime'@'<AUTHORIZED_RUNTIME_HOST>';
GRANT SELECT, INSERT, UPDATE, DELETE ON `checkoutv2release`.`order_items`
  TO 'checkoutv2runtime'@'<AUTHORIZED_RUNTIME_HOST>';
GRANT SELECT ON `checkoutv2release`.`flyway_schema_history`
  TO 'checkoutv2runtime'@'<AUTHORIZED_RUNTIME_HOST>';
```

SELECT sulla history serve al controllo obbligatorio V1 prima di Hibernate.
Nessuna scrittura sulla history, nessun grant runtime su db.*, nessun DDL.
Verify legge history e grant runtime, richiede il successo registrato per quel
piano e invoca Flyway validate sui byte V1 approvati. Il confronto strutturale
JPA avviene successivamente con Hibernate validate.
Verify può essere ripetuto dopo successo, anche dopo scadenza del piano:
non applica migrazioni. Preflight, Approve e Migrate non sono percorsi di retry
per una destinazione con tentativo precedente.

## Errore, interruzione e recupero manuale

MySQL DDL non garantisce rollback dell'intera V1. Un DDL già inviato può
completarsi dopo perdita del lock o arresto del processo: il protocollo blocca
nuove operazioni quando non dimostra l'esclusività, non annulla istantaneamente
operazioni già iniziate. Gli advisory lock sono cooperativi e non impediscono
a DBA o altri strumenti non conformi di cambiare schema/grant.

Un errore registra failure e conserva start/used. Un arresto forzato può lasciare
start/used senza success/failure: questo è **stato incerto**, mai successo.
Anche un fallimento della scrittura del registro dopo DDL richiede analisi.
La presenza di start blocca nuovi tentativi, anche con piano diverso.
Non reinizializzare lo store, non rimuovere record e non automatizzare retry.

Preservare database e ledger, fermare l'attivazione, coinvolgere DBA e operatore,
raccogliere inventario dello schema/history e prove di fine delle operazioni.
Nessun comando di recovery distruttiva o repair è implementato.
Una migrazione parziale richiede un piano separato approvato: può prevedere una
nuova destinazione dedicata con nuovo inventario autorizzato e un nuovo change,
mantenendo integra quella incerta. Non eseguire nuovamente V1 sullo stato parziale.
Il recupero della stessa destinazione o del ledger richiede progettazione e
autorizzazione ulteriori; non è fornito un bypass nel tool.

## Runtime, test, staging e produzione

- Test: esclusivamente MySQLContainer mysql:8.4.3, tmpfs, bind loopback dinamico,
  credenziali casuali, reuse disabilitato, ownership/UUID verificati prima di SQL
  amministrativo. Nessun server persistente è contattato dai test.
- Staging: destinazione nuova autorizzata, stesso protocollo e protezioni della
  produzione, TLS e HTTPS reali, prove funzionali e grant qualificati.
- Produzione: ulteriore approvazione, unico esecutore imposto dall'infrastruttura,
  inventario UUID/certificati, segreti custoditi, finestra DDL e piano operativo.
  Il runner browser non amministra database di staging/produzione.

Avviare l'applicazione in una sessione distinta con sole credenziali runtime,
identità DB e HMAC; le variabili di migrazione vengono rifiutate dall'applicazione.

```powershell
.\mvnw.cmd -B package -DskipTests
java -jar .\target\ecommerce-0.0.1-SNAPSHOT.jar --spring.profiles.active=checkout-release
```

Il package non sostituisce la regressione completa richiesta prima del rilascio.
Il profilo deve essere esplicito e unico: niente default, dev, test o browser
combinati, attivazione implicita o override JPA/DS pericolosi.
Usa Hibernate validate, generate-ddl=false, open-in-view=false, show-sql=false,
Flyway runtime, inizializzazione SQL, DataInitializer e console H2 disabilitati;
cookie Secure, HttpOnly, SameSite=Lax; bind 127.0.0.1:9086.
Configurazione incompleta, HMAC invalido e grant errati bloccano readiness.
La V1 resta invariata. I profili default/dev sono ritirati: il bootstrap li
rifiuta prima di datasource/JPA, anche con URL esplicito. La guardia globale
CheckoutStartupEnvironment verifica prima il modo di avvio e poi la
configurazione finale dei bean, delegando le protezioni release a ReleaseEnvironment.
Il solo profilo test o checkout-browser nel JAR normale non consente l'avvio.
Per sviluppo usare il runner proprietario descritto in CHECKOUT_V2_DEVELOPMENT.md.

Prima dell'apertura del traffico verificare HTTPS/proxy, login/sessione/CSRF,
catalogo, preventivi, ordine atomico, replay e conflitti 409. Il catalogo è vuoto:
caricarlo con una procedura separata approvata, senza seed dimostrativo.
Tra più istanze applicative mantenere HMAC coerente e pianificare session affinity
o store sessioni condiviso. Non è aggiunto un feature flag: attivazione del
traffico è un passaggio esplicito separato da merge/deploy.

## Arresto e rollback

Bloccare nuovi checkout al gateway, attendere richieste in corso e arrestare
Spring ordinatamente. Non riutilizzare il runner browser per il rilascio.
Prima dell'attivazione ritirare il candidato/configurazione e conservare il DB.
Dopo l'attivazione preservare ordini, history e chiavi idempotenti; prevedere
riconciliazione autorizzata. Checkout V2 è il solo modello supportato; nessuna compatibilità con order_details o importazione legacy.
Un rollback applicativo richiede un artefatto V2 compatibile con schema V1 e dati
già creati, con piano approvato. Non riabilitare configurazioni legacy o Hibernate
update. In assenza di un candidato V2 compatibile, bloccare il traffico e preservare
il database. Il rollback Git non è rollback dei dati.
