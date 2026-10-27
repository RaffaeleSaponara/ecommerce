# Checkout V2 — Fase 4: Spring/Hibernate su MySQL isolato

27 ottobre 2026. Branch `feature/checkout-v2-integration`.
HEAD iniziale e finale: `6c2d9bc7b7eb2141f20984369c1b49dfadb46453`.
Nessun commit, merge o push. Le modifiche di questa fase sono soltanto test e
questo report; sorgenti applicativi, POM, profili e V1 rimangono invariati.

## File modificati e creati

- `src/test/java/com/TreDL/ecommerce/service/CheckoutIntegrationTests.java`:
  conserva il bootstrap H2 originale ed eredita il contratto comune.
- `src/test/java/com/TreDL/ecommerce/service/CheckoutIntegrationContract.java`:
  nuovo contratto astratto con tutti i 30 casi originali, senza rimuovere o
  indebolire asserzioni. L'equivalenza del sorgente e stata verificata rispetto
  al commit di partenza, salvo estrazione del datasource e visibilita dei helper.
- `src/test/java/com/TreDL/ecommerce/service/CheckoutMySqlIntegrationTests.java`:
  nuova suite con i 30 casi condivisi e 7 verifiche specifiche MySQL.
- `src/test/java/com/TreDL/ecommerce/service/MySqlCheckoutTestDatabase.java`:
  proprietario del container, migrazione e datasource con verifica identita.
- `CHECKOUT_V2_PHASE4_REPORT.md`: questo report.

## Isolamento implementato

La suite crea un solo MySQLContainer `mysql:8.4.3` per contesto Spring, con:

- database univoco `checkout_v2_it_<UUID>`;
- utenza di fixture distinta da root e password casuale effimera, non un segreto reale;
- porta host assegnata da Testcontainers;
- label univoca `com.3dl.checkout.run`;
- `withReuse(false)` e dati MySQL su tmpfs `/var/lib/mysql` da 1 GiB;
- nessun bind mount, volume persistente o URL di database applicativo.

Il bootstrap del test, prima dell'avvio di Spring/JPA:

1. Avvia il solo container posseduto dalla fixture e ne verifica immagine,
   label di proprieta e tmpfs.
2. Legge il server UUID da `auto.cnf` dentro quel container, senza utilizzare
   una connessione SQL esterna per scegliere la destinazione.
3. Costruisce il datasource con URL, username, password e driver ottenuti
   esclusivamente dai getter del MySQLContainer.
4. Verifica URL JDBC, DATABASE(), server UUID e VERSION() della connessione,
   poi controlla che il database assegnato sia vuoto.
5. Applica soltanto V1 con Flyway programmatico: cleanDisabled(true),
   baselineOnMigrate(false), createSchemas(false).
6. Registra il datasource esplicito in Spring e imposta Hibernate validate,
   open-in-view=false, DataInitializer disabilitato e Flyway automatico disabilitato.

Il datasource esplicito impedisce di ricadere sull'autoconfigurazione del
MySQL storico. Le proprieta test di connessione provengono anch'esse dal
container, compreso il driver MySQL al posto del driver H2 del profilo test.

Le connessioni e gli Statement/PreparedStatement sono protetti da proxy JDBC:
prima delle esecuzioni SQL, batch e commit viene nuovamente confrontata
l'identita del database/server. Anche le DELETE delle fixture e gli INSERT/
UPDATE di Hibernate passano attraverso questi controlli. I tentativi di
cambiare catalogo, eseguire USE o creare/eliminare database sono rifiutati;
un test ne verifica il rifiuto prima dell'esecuzione. Il normale utente della
fixture ha accesso al proprio database nel container.

Il lifecycle del contesto Spring chiude Hibernate e arresta solo il container
posseduto dalla fixture. In caso di errore durante il bootstrap viene comunque
richiamata la chiusura di quel container. Il tmpfs non contiene dati persistenti.

Durante tutte le esecuzioni sono stati imposti temporaneamente al processo:
DOCKER_HOST verso la pipe locale Docker Desktop Linux, reuse disabilitato e
Ryuk attivo. Le variabili precedenti sono state ripristinate in finally.
Nessuna modifica a configurazioni Docker o variabili d'ambiente persistenti.

## Copertura MySQL

I 30 casi condivisi verificano sul motore reale:

- ordine riuscito, aggregazione delle occorrenze, BigDecimal esatto e EUR;
- snapshot persistiti di nome/prezzo e replay dopo modifica del catalogo;
- svuotamento atomico, rollback dopo errori e carrello intatto;
- riutilizzo della chiave dopo rollback;
- replay su carrello vuoto o ripopolato e dopo scadenza del preventivo;
- conflitto con stessa chiave e richiesta diversa;
- chiavi case-sensitive e isolate per cliente;
- concorrenza con stessa chiave, chiavi diverse e add/remove/clear del carrello;
- modifica del carrello precedente al checkout;
- retry concorrente dopo rollback;
- lock catalogo prima/dopo checkout, refresh dei prezzi e scadenza durante l'attesa;
- rifiuto di preventivi alterati, scaduti, non confermati o appartenenti ad altri;
- autenticazione, CSRF e risposte HTTP numeriche senza entity o dati sensibili;
- assenza di DataInitializer, overflow, prodotto eliminato e segreto assente.

Le 7 verifiche specifiche aggiuntive sono:

| Test | Verifica |
| --- | --- |
| ownsMySqlSchemaAndServiceStartsReadCommittedTransaction | Profilo test, Hibernate validate, InnoDB, strict mode, server/database attesi, niente transazione di test condivisa; READ-COMMITTED osservato durante la transazione avviata dal servizio stesso. |
| concurrentCheckoutsUseDistinctPhysicalConnectionsAndReplayOneOrder | Due thread, due transazioni e due CONNECTION_ID() differenti; secondo checkout bloccato dal lock, poi replay dello stesso ordine. |
| persistsExactBinaryColumnsDecimalMetadataAndOrderedLines | Chiave UTF-8 di 128 byte, hash SHA-256 reale da 32 byte, metadata VARBINARY/BINARY/INTEGER/DECIMAL, NOT NULL, totale esatto e righe persistite ordinate. |
| preservesHistoryAcrossCollectionRemovalProductDeletionAndCustomerDeleteAttempt | Nessun orphan removal degli ordini; FK impedisce la cancellazione del cliente; eliminazione prodotto con riferimento a NULL e snapshot/replay invariati. |
| databaseRejectsDuplicateCustomerKeyAndForeignKeyViolations | Violazioni reali UNIQUE e FK rifiutate dal server senza alterare l'ordine esistente. |
| realSqlConstraintFailureAfterOrderFlushRollsBackAllRowsAndPreservesCart | CHECK violato davvero sul server dopo il flush di ordine/righe; rollback completo, carrello intatto e retry della stessa chiave riuscito. |
| guardedDatasourceRefusesCatalogEscapeBeforeExecutingMutations | Cambio catalogo e USE rifiutati prima di eseguire le operazioni; fixture intatta. |

I test non hanno una transazione Spring di test condivisa. Le gare usano
ExecutorService, latch e transazioni applicative/TransactionTemplate distinte.
Il caso specifico delle connessioni confronta i veri CONNECTION_ID() MySQL.
L'esecuzione dei metodi della classe e sequenziale, mentre i thread concorrenti
sono creati esplicitamente soltanto nei singoli test.

## Problemi osservati e correzioni

La prima esecuzione mirata ha prodotto 36 successi e una asserzione fallita:
il CHECK MySQL `ck_orders_total` genera errore 3819 con SQLState HY000.
Con questa versione di Connector/J e Spring JdbcTemplate viene esposto come
UncategorizedSQLException, invece della DataIntegrityViolationException
inizialmente attesa dal nuovo test.

Correzione limitata al nuovo test: verificare esattamente tipo reale, codice
3819, SQLState HY000 e nome del vincolo. Restano obbligatorie le asserzioni di
rollback, assenza di ordine/righe, carrello intatto e retry riuscito.
Nessuna modifica a SQL, mapping o servizio applicativo e stata necessaria.

Hibernate 6.6.5.Final ha validato la V1 ed eseguito tutte le operazioni su
MySQL 8.4.3 con Connector/J 8.0.32. Non sono emerse incompatibilita di mapping,
precisione, campi binari, ordinamento, lock o isolamento nella copertura eseguita.

Flyway 10.20.1 continua a segnalare che MySQL 8.4 supera la propria versione di
supporto verificata dichiarata (8.1). Migrazione, validate e ripetizione senza
modifiche sono comunque passate. Nessun aggiornamento dipendenze e stato eseguito.
I messaggi SQL di FK/CHECK nei casi negativi sono attesi: non sono errori JUnit.

## Esecuzioni e risultati effettivi

Suite mirata, con Docker locale e variabili Testcontainers sopra descritte:

```powershell
.\mvnw.cmd -B '-Dtest=CheckoutMySqlIntegrationTests' '-Djunit.jupiter.execution.parallel.enabled=false' test
```

Dopo la correzione mirata: **37 eseguiti, 37 passati, 0 fallimenti, 0 errori,
0 saltati**. Il report JUnit effettivo e stato letto e verificato prima di
procedere al build completo.

Build completo richiesto:

```powershell
.\mvnw.cmd -B clean verify
```

**BUILD SUCCESS: 200 eseguiti, 200 passati, 0 fallimenti, 0 errori, 0 saltati.**
Compilazione, test, JAR e repackage completati. Tempo del build finale: circa
1 minuto e 33 secondi.

Dettaglio incluso nel totale, non da sommare nuovamente:

- CheckoutMySqlIntegrationTests: 37/37, zero skip.
- CheckoutV2MySqlSchemaTests preesistente: 13/13, zero skip.
- CheckoutIntegrationTests H2: 30/30, zero skip.
- Altre suite: 120/120, zero skip.

Test client invariati:

```powershell
node --test src/test/js/checkout-flow.test.cjs
```

**10 eseguiti, 10 passati, 0 falliti, 0 saltati.**

Tutti i report XML del build completo sono stati letti e aggregati: il totale
verificato e 200/0/0/0. Nessun test preesistente e stato disabilitato o escluso.

Report JUnit: `target/surefire-reports/`.
Log completo finale: `target/phase4-full-verify.log`.
Le prime esecuzioni mirate producevano log in target, eliminati dal successivo
Maven clean; i risultati finali sono quelli dei report del build completo.
Gli artefatti di test/build restano ignorati da Git.

## Rischi residui e riproducibilita

- Nessun blocco di codice individuato nella copertura richiesta.
- Il warning Flyway resta una limitazione dichiarata: un eventuale aggiornamento
  va valutato e verificato separatamente, senza cambiarlo implicitamente qui.
- La nuova suite richiede Docker funzionante e non salta i test quando manca:
  il bootstrap fallisce in modo esplicito, evitando una qualificazione apparente.
- Il datasource di test crea connessioni reali direttamente dal driver e le
  protegge con controlli di identita; non qualifica il comportamento del pool
  Hikari in un deployment reale. Le transazioni JPA e il motore sono reali.
- Le gare sono provate con thread/connessioni distinte nello stesso processo;
  non e una prova di deployment multi-nodo o di carico prolungato.
- Nessun test avvia l'applicazione nei profili default/dev o migra database
  esistenti. Provisioning di un ambiente reale resta una fase separata.

## Stato Git e conferma operativa

Il branch resta `feature/checkout-v2-integration`, HEAD invariato a
`6c2d9bc7b7eb2141f20984369c1b49dfadb46453`. Le sole modifiche non committate
sono i cinque file elencati all'inizio. git diff --check: nessun errore.

ui_experiments resta a `64d41e8e039603d00d99fdbd2a38da4fe848d06f` e
feature/checkout-v2-schema a `d01bdf6c153b33f2c52e584b4b148d5df068549a`.
Nessun altro branch o worktree modificato. Nessun commit, merge o push.

Tutte le connessioni MySQL hanno riguardato container temporanei creati dai
test; nessun accesso al server MySQL applicativo locale/remoto o a database
persistenti. Nessun H2 dev persistente utilizzato. Nessun Flyway clean,
prune o eliminazione di risorse altrui. Dopo il build non risultano container
Testcontainers residui; i container sono stati chiusi automaticamente dai
rispettivi lifecycle e da Ryuk. Nessun database o volume persistente modificato.

Lavoro fermato dopo i test, in attesa di approvazione.
