# Checkout V2 — modello e test, 27 ottobre 2026

> Nota di consolidamento prima del commit: il POM autonomo `model-verification/pom.xml`
> era un workaround temporaneo ed e stato rimosso. Tutti i test del modello e dello
> schema H2 sono eseguiti dal POM principale con `.\mvnw.cmd -B clean verify`.
> I riferimenti a quel POM e agli stati intermedi nei paragrafi seguenti sono storici.

## Isolamento e baseline

- Worktree: `<checkout-v2-integration-worktree>`.
- Branch locale: `feature/checkout-v2-integration`.
- SHA iniziale e HEAD finale (nessun commit): `d01bdf6c153b33f2c52e584b4b148d5df068549a`.
- `ui_experiments` rimane a `64d41e8e039603d00d99fdbd2a38da4fe848d06f`.
- Tutte le scritture ai sorgenti e gli output di build sono nel nuovo worktree.
- Nessun commit, merge, push, cancellazione di branch o worktree.

## Modello implementato

`Money.validate` normalizza a scala 2 senza arrotondamento e rifiuta null,
negativi, frazioni di centesimo e overflow di DECIMAL(19,2). Il massimo è
99999999999999999.99; zeri decimali aggiuntivi esatti sono accettati.
La moltiplicazione per quantità verifica anche l'overflow del subtotale.

Products usa BigDecimal e valida sia dal setter sia nei callback JPA.
Orders usa BigDecimal, EUR, chiave byte[] di 1–128 byte e hash byte[] di
32 byte, con copie difensive. Non genera chiavi o hash.
Il mapping usa esattamente `idempotency_key VARBINARY(128)` e
`request_hash BINARY(32)`, entrambi NOT NULL.

OrderItem conserva snapshot di nome e prezzo, quantità positiva e numero
riga positivo persistito in `line_number INTEGER NOT NULL`, univoco per
ordine. Snapshot e numero riga non hanno setter e non sono aggiornabili da
Hibernate. La lettura usa `@OrderBy("lineNumber ASC")`; la vista pubblica è
ordinata e non modificabile. Il riferimento al prodotto è opzionale dopo
la cancellazione via FK V1 ON DELETE SET NULL. La vecchia relazione
order_details è stata rimossa, anche dal lato Products.

Customers non propaga REMOVE agli ordini e non usa orphanRemoval.
Il carrello mantiene la lista a occorrenze. CartService, repository e lock
sono invariati. I DTO serializzano i BigDecimal come numeri JSON;
OrderDTO espone date, total, currency, shipment e items di OrderItemDTO.
Le righe espongono lineNumber, productName, unitPrice, quantity e subtotal.
Non espongono credenziali, entity, chiavi o hash. CartDTO continua a
raggruppare le occorrenze tramite ProductDTO, con prezzi BigDecimal.
DataInitializer crea 12.90, 14.50 e 7.90 da stringhe decimali esatte.

## File modificati

- src/main/java/com/TreDL/ecommerce/model/Products.java
- src/main/java/com/TreDL/ecommerce/model/Orders.java
- src/main/java/com/TreDL/ecommerce/model/Customers.java
- src/main/java/com/TreDL/ecommerce/dto/ProductDTO.java
- src/main/java/com/TreDL/ecommerce/dto/CartDTO.java (documentazione della semantica conservata)
- src/main/java/com/TreDL/ecommerce/dto/OrderDTO.java
- src/main/java/com/TreDL/ecommerce/DataInitializer.java

## File creati

- src/main/java/com/TreDL/ecommerce/model/Money.java
- src/main/java/com/TreDL/ecommerce/model/OrderItem.java
- src/main/java/com/TreDL/ecommerce/dto/OrderItemDTO.java
- src/test/java/com/TreDL/ecommerce/model/CheckoutV2ModelTests.java
- model-verification/pom.xml
- model-verification/.gitignore
- CHECKOUT_V2_MODEL_REPORT.md

## Verifiche eseguite

Comando riproducibile dalla radice:

```powershell
mvn -B -f model-verification/pom.xml test
```

Il POM autonomo compila entity, DTO, initializer e ProductsRepository, poi
esegue i nuovi test del modello e il contratto H2 preesistente. Non modifica
il POM principale o la selezione dei suoi test. È una verifica parziale
esplicita, necessaria mentre OrdersService legacy non compila.

Esito: **25 test passati, 0 fallimenti, 0 errori, 0 saltati**:
12 casi del modello e 13 casi del contratto schema H2.
Copertura: precisione e limiti monetari, quantità/subtotali, snapshot,
validazione e copie difensive binarie, righe duplicate e appartenenza,
ordinamento dopo commit e nuova sessione, JSON numerico e assenza di dati
sensibili, prezzi esatti dell'initializer, occorrenze duplicate nel carrello,
modifica/cancellazione prodotto e conservazione dello storico,
rimozione dalla collezione ordini senza orphan removal, impossibilità di
cancellare un cliente con storico. Gli hash nei test sono SHA-256 calcolati
su un payload di fixture, mai impostati dal codice applicativo.

Il test JPA applica la V1 originale con Flyway programmatico e
`cleanDisabled(true)`, poi costruisce Hibernate con `hbm2ddl.auto=validate`.
Non usa create/update per mascherare divergenze di mapping.
I database sono solo `jdbc:h2:mem:` con nome casuale, senza file o
DB_CLOSE_DELAY; si esauriscono con la chiusura delle connessioni.
Le cancellazioni SQL del test riguardano solo le fixture in memoria.
Nessun collegamento MySQL, avvio dell'applicazione o uso del profilo dev.
Nessun Flyway clean o attivazione Flyway default/dev.
SQL V1 e configurazioni applicative sono invariati.

Log: `model-verification/test.log`, report XML in
`model-verification/target/surefire-reports/` (artefatti ignorati da Git).
`git diff --check`: nessun errore.

## Build completo e limiti dichiarati

```powershell
mvn -B test
```

**BUILD FAILURE: il branch di integrazione è temporaneamente non compilabile.**
Tre errori in OrdersService.java, lasciato invariato:

1. Riga 28: somma double + BigDecimal.
2. Riga 35: setTotal riceve double invece di BigDecimal.
3. Riga 36: setProducts non esiste nel modello a OrderItem.

Il build si arresta prima della compilazione/esecuzione dei test generali.
Nessun test esistente è stato eliminato, disabilitato o riscritto per
nascondere il problema. Il risultato verde del POM autonomo non equivale
alla suite applicativa completa.
Log: `target/checkout-v2-root-build.log`.

Le fixture legacy di CheckoutHttpTests e SessionSecurityTests usano ancora
costruttori e relazioni rimossi (Products/Orders, setOrders/getProducts) e
si aspettano il JSON products; CartServiceTests e CartConcurrencyTests
assegnano ancora prezzi double. Andranno adeguate al contratto finale nel
prossimo intervento: al momento non sono compilate dal build principale,
che si arresta prima su OrdersService. Nessun endpoint è disabilitato.

## Compatibilità residua

Nessuna incompatibilità di mapping rilevata da Hibernate sulla V1 H2.
Il motore MySQL non è stato verificato né contattato: H2 MySQL mode non
costituisce una certificazione MySQL. Le regole tra tabelle (somma righe e
totale), l'assegnazione dei numeri riga, UTC e l'immutabilità dell'ordine
dopo il checkout richiedono il futuro servizio transazionale. La V1 non
impone la somma tra tabelle. Il checkout legacy non può ancora produrre
ordini validi: non assegna key/hash, currency e righe secondo il nuovo
contratto. Nessun adattatore con identità o hash inventati è stato aggiunto.
I consumer del JSON ordine dovranno passare da products a items/currency.

## Prossimo intervento proposto, non implementato

Definire il contratto HTTP della chiave UTF-8 esatta e del payload canonico,
con SHA-256 reale. In una transazione, conservare i lock esistenti,
ricercare il replay prima di rifiutare un carrello vuoto, aggregare le
occorrenze, assegnare line_number stabili, copiare gli snapshot e calcolare
il totale con BigDecimal; persistere ordine/righe e svuotare il carrello
atomicamente. Il replay non deve ricalcolare prezzi o risvuotare il carrello.
Una chiave con payload diverso deve dare conflitto. Gestire la corsa sul
vincolo univoco rileggendo in una nuova transazione dopo il rollback.
Aggiornare fixture e asserzioni HTTP al DTO finale, poi testare atomicità,
rollback, replay persistito e concorrenza. Qualunque verifica su MySQL o
operazione su un database reale richiede una fase autorizzata separatamente.

Lavoro fermato alle fasi 0–2. Nessuna nuova logica di checkout implementata.
