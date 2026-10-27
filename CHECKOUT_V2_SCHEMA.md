# 3DL: schema iniziale checkout V2

## Baseline e ambito

Baseline: `ui_experiments` e `origin/ui_experiments`, commit `64d41e8`,
coincidenti con HEAD al momento della verifica del 27 ottobre 2026.
Nessun fetch, commit, merge o push eseguito.

Questa fase prepara SQL, dipendenze di test e contratto JDBC. Non modifica
checkout, entity, repository o DTO. Il codice corrente usa ancora Double/double
e `order_details`: **non è compatibile con la V1** e non deve essere avviato
contro il nuovo schema prima del successivo adeguamento applicativo.
Non è prevista alcuna migrazione incrementale, colonna monetaria doppia,
procedura di backfill o importazione di ordini precedenti.

## Schema definitivo proposto

La sola migrazione `src/main/resources/db/checkout-v2/V1__create_checkout_v2.sql`
crea tutte le tabelle applicative da un database vuoto:

| Tabella | Contratto |
| --- | --- |
| `customers` | Credenziali, ruolo, username univoco, email opzionale |
| `products` | Catalogo, `prezzo DECIMAL(19,2)` non negativo |
| `cart` | Un carrello per cliente |
| `cart_products` | Lista a occorrenze: coppie duplicate ammesse, senza quantità né vincolo univoco sulla coppia |
| `orders` | Cliente, data, totale `DECIMAL(19,2)`, valuta EUR, spedizione, chiave di idempotenza e impronta della richiesta |
| `order_items` | Righe OrderItem: `line_number INTEGER NOT NULL` positivo e univoco per ordine, quantità positiva, nome e prezzo unitario storici `DECIMAL(19,2)`, riferimento opzionale al prodotto |

Ogni importo è obbligatorio e non negativo. Intervallo positivo massimo:
`99999999999999999.99`. Nessun DOUBLE/FLOAT o `order_details` nella V1.
`order_items.id_product` diventa NULL se viene eliminato un prodotto;
nome e prezzo storici rimangono nell'ordine. I riferimenti da carrelli ancora
popolati impediscono invece la cancellazione del prodotto.
Gli ordini non vengono cancellati implicitamente tramite cascade.

`order_items.line_number` è obbligatorio, con CHECK `line_number > 0`
(`ck_order_items_line_number`) e UNIQUE `(id_order, line_number)`
(`uq_order_items_order_line`). La numerazione può ripartire da 1 per ogni
ordine; il database non impone contiguità né assegnazione automatica.
Il codice applicativo futuro dovrà assegnare i numeri e leggere lo storico
con `ORDER BY line_number` all'interno dell'ordine: né l'ID tecnico né
l'ordine di inserimento garantiscono l'ordinamento delle righe.
I CHECK sono verificati su H2 e richiedono un MySQL che li applichi;
il target dei test è MySQL 8.4.3. La V1 viene corretta direttamente perché
non è stata applicata a database persistenti; non viene introdotta V2.
`idempotency_key` e `request_hash` restano invariati e NOT NULL.

L'unicità `(id_customer, idempotency_key)` è persistita nel database.
La chiave è `VARBINARY(128)` per confrontare i byte esatti, senza dipendere
dalla collation MySQL; il servizio dovrà accettare chiavi UTF-8 da 1 a 128 byte,
senza trim o normalizzazione implicita. `request_hash BINARY(32)` conserva
l'impronta SHA-256 del payload canonico. Non memorizzare un hash costante:
quello dei test è soltanto una fixture. La chiave resta riservata finché esiste
l'ordine; non prevedere cancellazione o scadenza automatica degli ordini.

## Contratto applicativo da implementare nella fase successiva

- Usare `BigDecimal` per prezzo catalogo, prezzo storico, totale ordine e tutti
  i valori monetari intermedi/DTO. Mappare precisione 19 e scala 2.
  Creare i decimali da stringhe o valori decimali esatti, mai da double.
  Per EUR rifiutare frazioni oltre i centesimi (`setScale(2, UNNECESSARY)`),
  valori negativi e overflow, prima dell'accesso al database.
- Serializzare con Jackson come numeri JSON, per esempio `"total": 12.30`;
  non usare serializer che trasformino gli importi in stringhe.
  Aggiornare i test HTTP per verificare `isNumber()` sui DTO effettivi.
- Introdurre l'entity `OrderItem` e la relazione uno-a-molti dall'ordine.
  Aggregare le occorrenze per prodotto al checkout; copiare nome e prezzo
  correnti in ciascuna riga. Calcolare `total = sum(unit_price * quantity)`.
  Il carrello resta a occorrenze, senza cambiare il comportamento di rimozione.
- Mantenere EUR come valuta canonica e obbligatoria in entity e risposta.
  Le date `DATETIME(6)` seguono una convenzione UTC da applicare nel codice.
- In una sola transazione: lock del carrello del cliente, ricerca della chiave
  anche prima di rifiutare un carrello vuoto, creazione ordine e righe,
  svuotamento del carrello e commit. Una ripetizione della stessa richiesta
  restituisce l'ordine già memorizzato senza ricalcolare prezzi o svuotare
  nuovamente il carrello. Una stessa chiave con payload diverso restituisce
  conflitto. L'hash descrive la richiesta canonica del client, non il carrello
  mutabile dopo il checkout; definire tale payload insieme al nuovo contratto HTTP.
- Il vincolo univoco gestisce anche le corse concorrenti: dopo una violazione,
  rileggere l'ordine in una nuova transazione, fuori da quella fallita.
  Verificare atomicità e replay nel futuro servizio reale; i test JDBC attuali
  dimostrano i vincoli e il rollback del database, non l'implementazione HTTP.
- Impedire modifiche successive a snapshot, totale, cliente e chiave nell'API.
  Il database non impone l'immutabilità delle righe né la somma tra tabelle:
  questi invarianti devono essere garantiti dal servizio transazionale.

## Protezioni attuali

Flyway core e modulo MySQL hanno scope Maven **test**: non sono inclusi
nell'applicazione distribuita. `spring.flyway.enabled=false` è esplicito nelle
configurazioni default, dev e test. La V1 è in un percorso dedicato diverso
da `db/migration`, selezionato soltanto dai test JDBC. Non è stato aggiunto
un profilo applicativo in grado di migrare un datasource persistente.
I test invocano Flyway programmaticamente con `cleanDisabled(true)` e
`baselineOnMigrate(false)`. Non eseguono Flyway clean.

Le configurazioni Hibernate attuali restano quelle della baseline:
**non avviare l'applicazione default/dev per provare questa migrazione**.
I test Spring preesistenti continuano a usare H2 in memoria e create-drop;
non rappresentano una validazione JPA dello schema V2.

## Test riproducibili

```powershell
.\mvnw.cmd -B '-Dtest=CheckoutV2*SchemaTests' test
.\mvnw.cmd -B test
```

Le classi non caricano Spring e non leggono DB_URL o credenziali applicative.
H2 usa un nome UUID nuovo per metodo, senza file e senza DB_CLOSE_DELAY:
il database scompare alla chiusura dell'ultima connessione.
MySQL usa esclusivamente un container Testcontainers `mysql:8.4.3`, database
`checkout_v2_test`, credenziali temporanee, porte assegnate dinamicamente,
senza reuse né volumi persistenti. Docker deve essere già disponibile;
altrimenti i casi MySQL risultano saltati. Non esistono URL MySQL di fallback.
Le cancellazioni JDBC nei teardown riguardano esclusivamente fixture di questi
database temporanei. Non è stato cancellato alcun database persistente.

Lo stesso contratto verifica V1/versionamento/checksum, seconda migrate senza
modifiche, precisione/scala e aritmetica BigDecimal, numero JSON, overflow,
snapshot dopo modifica/eliminazione del prodotto, chiavi per cliente e
case-sensitive, lettura da altra connessione dopo commit, vincoli, occorrenze
duplicate del carrello, rollback e riuso della chiave dopo rollback.
Il contratto condiviso verifica inoltre persistenza dei numeri di riga dopo
commit da una nuova connessione, ordinamento storico esplicito dopo inserimenti
fuori sequenza, rifiuto di duplicati nello stesso ordine (INSERT/UPDATE), riuso
fra ordini diversi dello stesso cliente e rifiuto di NULL/zero/negativi.
Le fixture degli altri test includono il numero di riga e continuano a verificare
prezzi, quantità, FK, snapshot e obbligatorietà delle chiavi di idempotenza.
H2 in modalità MySQL non sostituisce una prova sul motore MySQL.

### Risultati del 27 ottobre 2026

Windows, Temurin JDK 21.0.9, Maven Wrapper 3.9.9, Flyway 10.20.1,
H2 2.3.232 e Testcontainers 1.20.4 (versioni gestite dal parent Boot).

- Suite finale `test`: **106 casi, 97 passati, 0 fallimenti, 0 errori,
  9 saltati**, `BUILD SUCCESS`.
- Contratto V2 H2: **9 passati**. Contratto V2 MySQL: **9 saltati** perché
  il motore Docker locale non è disponibile; nessuna validazione MySQL reale.
- La prima esecuzione completa nel sandbox aveva 2 errori nei test HTTP
  preesistenti (`HomePageHttpTests`), per connessione loopback negata.
  L'esecuzione finale con loopback consentito passa anche quei casi.
- `git diff --check`: nessun errore. Nessun sorgente applicativo Java modificato.

Log locali ignorati da Git: `target/schema-test.log` (prima prova mirata),
`target/checkout-v2-full-test.log` (prova nel sandbox) e
`target/checkout-v2-full-verified.log` (suite finale). Report JUnit:
`target/surefire-reports/`. Un successivo clean Maven elimina questi artefatti;
non si tratta di Flyway clean.

### Verifica della correzione line_number — 27 ottobre 2026

Correzione eseguita nel solo worktree dell'Agente Migrazioni, con HEAD detached
`a92c83f16c3a95dd2a3014004371249165e4b395`. Nessuna V2 aggiunta.
Le classi H2 e MySQL ereditano gli stessi quattro nuovi test dal contratto JDBC;
il totale dei casi schema passa da 9 a 13 per motore.

- Comando completo: `.\mvnw.cmd -B test`.
- Esito finale: **114 casi, 101 passati, 0 fallimenti, 0 errori, 13 saltati**,
  `BUILD SUCCESS` (24.309 secondi).
- H2 2.3.232: **13 passati**, inclusi numero di riga persistito dopo commit,
  ordinamento esplicito, unicità per ordine e vincoli su NULL/zero/negativi.
- MySQL: **13 saltati**; il motore Docker Desktop Linux non è disponibile
  (pipe `dockerDesktopLinuxEngine` assente). Il contratto è aggiornato, ma
  la compatibilità sul motore MySQL reale resta da verificare con Docker attivo.
- La prima prova nel sandbox aveva 2 errori nei test HTTP preesistenti per
  loopback negato; la ripetizione con loopback consentito passa anche quei test.
- Log: `target/line-number-full-test.log` (prima prova) e
  `target/line-number-full-verified.log` (esito finale); report in
  `target/surefire-reports/`. Sono artefatti locali ignorati da Git.
- Nessun database persistente utilizzato; nessun Flyway clean, modifica a
  entity/DTO/checkout, commit, merge, rebase o push. `DATA_MIGRATION_PLAN.md`
  preservato integralmente, verificato tramite SHA-256 prima/dopo il riallineamento.

Rimane l'incompatibilità applicativa già descritta: l'applicazione corrente
non deve essere avviata contro V1 prima dell'adeguamento delle entity e del
checkout, che dovranno assegnare e usare esplicitamente `line_number`.

## Passaggio al nuovo schema: operazioni future, non eseguite

1. Completare e testare entity/DTO/checkout V2 secondo il contratto sopra.
   Eliminare i mapping legacy anziché aggiungere compatibilità o backfill.
2. Identificare esattamente destinazione, host/porta, motore/versione, nome
   database/schema e account. Ottenere ulteriore autorizzazione prima di
   connettersi a qualunque database reale o persistente. Prima di qualsiasi
   operazione distruttiva richiedere conferma riferita a quella destinazione.
   Preferire un nuovo database vuoto, senza cancellare quello esistente.
3. Solo dopo autorizzazione, predisporre un profilo dedicato esclusivamente V2,
   senza URL o credenziali di fallback verso default/dev. Rendere Flyway una
   dipendenza runtime in quella fase e impostare esplicitamente la location
   `classpath:db/checkout-v2`; tenere default/dev disabilitati. Configurare
   Hibernate `ddl-auto=validate`, mai update/create sul nuovo ambiente.
4. Verificare che la destinazione autorizzata sia vuota. Applicare soltanto V1,
   con clean disabilitato e senza baseline automatica; validare schema e
   checksum. Un database non vuoto deve far interrompere il passaggio.
5. Eseguire i test MySQL e poi i test integrati V2: checkout transazionale,
   totale, snapshot, replay dopo riavvio, concorrenza, conflitto chiave/payload,
   rollback e numeri JSON. Solo allora avviare la versione V2 sul nuovo schema.

L'eventuale eliminazione dei vecchi database rimane una decisione separata,
subordinata all'identificazione precisa e a conferma esplicita del proprietario.
