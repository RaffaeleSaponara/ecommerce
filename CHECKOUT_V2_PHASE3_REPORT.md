# Checkout V2 — report Fase 3

> Nota di consolidamento prima del commit: il POM autonomo `model-verification/pom.xml`
> era un workaround temporaneo ed e stato rimosso. Tutti i test del modello e dello
> schema H2 sono eseguiti dal POM principale con `.\mvnw.cmd -B clean verify`.
> I riferimenti a quel POM e agli stati intermedi nei paragrafi seguenti sono storici.

Data: 27 ottobre 2026. Le fasi 0–2 restano documentate nel report storico
CHECKOUT_V2_MODEL_REPORT.md; il precedente stato non compilabile è superato.

## Isolamento

- Worktree: `<checkout-v2-integration-worktree>`.
- Branch: `feature/checkout-v2-integration`.
- Baseline e HEAD: `d01bdf6c153b33f2c52e584b4b148d5df068549a` (nessun commit creato).
- `ui_experiments`: invariato a `64d41e8e039603d00d99fdbd2a38da4fe848d06f`.
- Nessuna scrittura in altri worktree; nessun merge, push o eliminazione di worktree.

## File della Fase 3

Creati, sotto `src/main/java/com/TreDL/ecommerce/`:

- `service/CheckoutService.java`
- `service/QuoteSigner.java`
- `service/CheckoutException.java`
- `controller/CheckoutErrorHandler.java`
- `dto/CheckoutRequest.java`
- `dto/QuoteDTO.java`
- `dto/QuoteLineDTO.java`

Modificati:

- `src/main/java/com/TreDL/ecommerce/controller/CartController.java`
- `src/main/java/com/TreDL/ecommerce/repository/OrdersRepository.java`
- `src/main/java/com/TreDL/ecommerce/dto/OrderDTO.java` (identificativo pubblico dell'ordine)
- `src/main/resources/application.properties` (solo proprietà della chiave HMAC)
- `src/main/resources/templates/cart.html` (preventivo, conferma, retry)
- `src/test/resources/application-test.properties` (chiave pubblica di fixture)
- `src/test/java/com/TreDL/ecommerce/security/CheckoutHttpTests.java`
- `src/test/java/com/TreDL/ecommerce/security/SessionSecurityTests.java`
- `src/test/java/com/TreDL/ecommerce/service/CartServiceTests.java`
- `src/test/java/com/TreDL/ecommerce/service/CartConcurrencyTests.java`

Rimosso:

- `src/main/java/com/TreDL/ecommerce/service/OrdersService.java`: il percorso legacy
  di creazione privo di conferma/idempotenza è sostituito dal servizio V2.

Test e documentazione creati:

- `src/test/java/com/TreDL/ecommerce/service/CheckoutIntegrationTests.java`
- `src/test/java/com/TreDL/ecommerce/service/QuoteSignerTests.java`
- `src/test/js/checkout-flow.test.cjs`
- `CHECKOUT_V2_PHASE3_REPORT.md`

Le modifiche del modello approvate nelle fasi 0–2 restano presenti e non
committate. Il POM autonomo di quella fase resta disponibile, ma la verifica
finale è quella completa del POM principale. Nessun test è stato disabilitato.

## Contratto HTTP

Entrambi gli endpoint richiedono la sessione autenticata e un token CSRF valido.
Il cliente proviene esclusivamente dal Principal: nessun customerId del client
può selezionare l'intestatario dell'ordine. GET/HEAD non creano ordini.

### POST /cart/quote

Nessun body richiesto. Legge il carrello sotto lock e restituisce HTTP 200:

```json
{
  "quoteToken": "<payload-base64url>.<hmac-base64url>",
  "expiresAt": 1793102700,
  "currency": "EUR",
  "total": 25.00,
  "items": [
    {"productId": 7, "productName": "Jig", "unitPrice": 12.50,
     "quantity": 2, "subtotal": 25.00}
  ]
}
```

`expiresAt` è un esempio: la scadenza effettiva è cinque minuti dopo l'emissione,
in secondi Unix UTC. Prezzi/subtotali/totale sono numeri JSON BigDecimal.
La richiesta di preventivo non crea ordini e non modifica il carrello.

### POST /cart/checkout

Header richiesti:

```text
Content-Type: application/json
Idempotency-Key: <chiave scelta dal client per questo tentativo>
X-CSRF-TOKEN: <token della sessione>
```

Body:

```json
{"quoteToken":"<token esatto restituito dal preventivo>","confirmPrices":true}
```

HTTP 200 sia per una creazione riuscita sia per il replay:

```json
{
  "id": 9,
  "date": "2026-10-27T12:00:00.123456",
  "total": 25.00,
  "currency": "EUR",
  "shipment": "In elaborazione",
  "items": [
    {"lineNumber": 1, "productName": "Jig", "unitPrice": 12.50,
     "quantity": 2, "subtotal": 25.00}
  ]
}
```

La data è UTC per convenzione applicativa, con precisione microsecondi coerente
con DATETIME(6). Le risposte sono DTO, mai entity JPA; non contengono password,
chiave idempotente, request hash o grafi cliente/prodotto/carrello.

Errori di dominio restituiti come `{"error":"..."}`:

- 400: chiave/body/conferma mancanti o invalidi, firma alterata, token di altro cliente.
- 401: autenticazione assente o cliente non più esistente.
- 403: CSRF mancante o invalido (gestito dalla catena Spring Security).
- 409: carrello vuoto, preventivo scaduto, prodotti/quantità/nomi/prezzi cambiati,
  oppure chiave già usata con una diversa richiesta valida.
- 415: contenuto non JSON sul checkout, incluso il vecchio invio form senza JavaScript.
- 503: chiave di firma non configurata per un nuovo preventivo/ordine.

Gli errori di persistenza e gli importi che eccedono il range monetario
interrompono la transazione; non producono ordini parziali o uno svuotamento
committato. Non viene intercettata una violazione SQL per proseguire in una
transazione già compromessa.

## Idempotenza, prezzi e transazione

La chiave è il valore client convertito con un encoder UTF-8 rigoroso:
1–128 byte, senza trim, normalizzazione Unicode o folding del case.
È memorizzata in `idempotency_key VARBINARY(128)`. Il contratto si applica
al valore ricevuto dall'applicazione dopo la decodifica dell'header HTTP;
per l'interoperabilità dei trasporti il client incluso usa una chiave ASCII.
Il vincolo V1 UNIQUE `(id_customer, idempotency_key)` resta invariato.

Il payload canonico della richiesta confermata è la concatenazione UTF-8:

```text
3DL-CHECKOUT-V2\nconfirmPrices=true\nquoteToken=<token esatto>
```

I due `\n` rappresentano byte LF reali; non c'è un LF finale dopo il token.
SHA-256 di questi byte viene persistito in `request_hash BINARY(32)`.
L'ordine dei campi o gli spazi nel JSON HTTP non cambiano la richiesta logica.
Il contratto minimo comprende soltanto quoteToken e conferma esplicita;
non accetta prezzi del client come fonte del totale. Un preventivo rigenerato
con token differente costituisce una richiesta differente: non riutilizzare
una chiave già consumata per sostituire il preventivo.

Il preventivo firmato contiene JSON compatto UTF-8, emesso dalle record DTO:
version, customerId, expiresAt, currency, total, items. Ogni riga contiene
productId, productName, unitPrice, quantity, subtotal. Le righe sono ordinate
per ID prodotto crescente e gli importi hanno scala 2. HMAC-SHA256 autentica
i byte del JSON. Il token usa Base64 URL-safe senza padding e un punto come
separatore. La verifica usa confronto costante della firma e richiede la
ricodifica canonica esatta. Il limite è 65.536 caratteri.

La chiave HMAC è la configurazione esplicita `CHECKOUT_QUOTE_SECRET_BASE64`
(o proprietà `checkout.quote-secret-base64`): Base64 di almeno 32 byte segreti,
stabili e condivisi tra istanze. Non c'è un segreto casuale generato dal server
o un fallback di produzione. La fixture nel profilo test è pubblica e non
viene usata dal profilo default/dev. Chiave malformata/debole: errore di
configurazione; chiave assente: nuovi preventivi/ordini rifiutati. Una rotazione
invalida preventivi ancora da utilizzare, ma non i replay già persistiti.

CheckoutService opera con `@Transactional`, rollback per tutte le eccezioni
e isolamento READ_COMMITTED. Nella transazione:

1. Identifica il cliente autenticato e acquisisce il lock pessimista già
   esistente su Customers.
2. Cerca l'ordine per cliente/chiave PRIMA di verificare carrello o scadenza.
   Hash identico restituisce il DTO storico; hash differente dà 409.
3. Per una nuova richiesta verifica firma, intestatario e scadenza.
4. Legge il carrello e aggrega le occorrenze. Rilegge/refresh i prodotti dal
   database con lock PESSIMISTIC_READ, in ordine di ID, fino al commit.
5. Confronta righe, quantità, nomi e prezzi correnti con il preventivo firmato.
   Una differenza richiede un nuovo preventivo e una nuova conferma.
6. Calcola il totale con BigDecimal/Money, crea snapshot e line_number da 1,
   salva e flush ordine e righe, poi svuota e flush il carrello.
7. Il commit rende visibili insieme ordine, righe e carrello svuotato;
   qualunque errore provoca rollback dell'intera operazione.

Checkout concorrenti dello stesso cliente, anche su più istanze, attendono lo
stesso lock di riga: il secondo vede il commit del primo grazie a READ_COMMITTED.
Il replay restituisce lo stesso ordine anche con carrello vuoto, un nuovo
carrello popolato, prezzi catalogo cambiati o preventivo ormai scaduto.
Il vincolo univoco SQL resta la protezione finale; il servizio non usa un
catch/retry dentro una transazione fallita. Nessun writer applicativo di ordini
alternativo rimane esposto.

CartService, CustomersRepository e SecurityConfig sono invariati: le altre
mutazioni conservano il lock esistente; sessione, autorizzazione e CSRF restano
attivi. Non sono stati introdotti endpoint che accettano un totale fidato
dal client o conferme inventate dal server.

La pagina carrello mostra il preventivo firmato e richiede una checkbox prima
dell'invio. Genera la chiave CLIENT una sola volta per tentativo e conserva
chiave/token in sessionStorage per i retry, anche dopo reload. Dopo un errore
di rete riusa gli stessi byte di richiesta, senza generare un'altra chiave.
I prezzi del riepilogo confermato sono letti dai lessemi decimali del JSON e
formattati senza conversione monetaria in Number, anche al limite DECIMAL(19,2).
Il vecchio riepilogo del carrello resta indicativo; l'ordine richiede sempre
la conferma del preventivo server. Il rendering delle righe firmate usa
textContent per i nomi dei prodotti.

## Test eseguiti

Comando finale completo, dalla radice del worktree:

```powershell
.\mvnw.cmd -B clean verify
```

**BUILD SUCCESS**, compilazione, test, JAR e repackage riusciti.
156 casi censiti: **143 passati, 0 fallimenti, 0 errori, 13 saltati**.
I 13 saltati sono esclusivamente i test MySQL Testcontainers preesistenti:
Docker non è disponibile (`Could not find a valid Docker environment`).
Nessun test è stato escluso dal comando, rimosso o disabilitato.

| Suite | Passati |
| --- | ---: |
| CheckoutIntegrationTests (V1 reale + Hibernate validate) | 23 |
| QuoteSignerTests | 6 |
| CheckoutV2ModelTests | 12 |
| CheckoutV2H2SchemaTests | 13 |
| CheckoutHttpTests | 11 |
| SessionSecurityTests | 27 |
| CartConcurrencyTests | 14 |
| CartServiceTests | 15 |
| CartServiceUnitTests | 15 |
| JwtSecurityTests | 4 |
| HomePageHttpTests (Tomcat reale su loopback) | 2 |
| EcommerceApplicationTests | 1 |

La suite checkout verifica successo, quantità aggregate, precisione,
snapshot dopo modifica catalogo, replay su transazioni separate, scadenza,
chiave case-sensitive/per cliente, conflitto, carrello cambiato, conferma
falsa/mancante/alterata/di altro cliente, overflow, autenticazione, CSRF e
risposte DTO numeriche. Inietta errori sia nel salvataggio ordine sia dopo
il flush delle righe, e verifica carrello invariato e riuso della chiave dopo
rollback. Le corse su connessioni e transazioni distinte usano latch e un
lock mantenuto fino al rilascio: checkout/checkout con stessa o diversa chiave,
checkout/add-remove-clear, modifica che precede checkout e retry dopo rollback.

La prima suite completa ha rilevato dipendenze dei teardown legacy dai cascade
rimossi. Ora quei teardown cancellano esplicitamente solo le fixture H2 in
ordine di FK. Le asserzioni funzionali e di sicurezza non sono state indebolite.
Il test Unicode delle chiavi usa escape Java per evitare dipendenze dalla
codifica della shell Windows.

Verifica client separata, senza dipendenze aggiuntive:

```powershell
node --test src/test/js/checkout-flow.test.cjs
```

**5 test passati, 0 falliti** sul JavaScript effettivo della pagina: conferma
esplicita e CSRF, nomi resi come testo, retry dopo errore di rete/reload,
invalidazione dopo 409, nessun invio se non è possibile conservare la chiave,
precisione di visualizzazione fino a 99999999999999999.99.
Questo è un test della logica client con DOM/fetch simulati, non un collaudo
manuale del layout in un browser reale.

Log completo: `target/phase3-verify.log`. Report JUnit: `target/surefire-reports/`.
`git diff --check`: nessun errore (eventuali avvisi LF/CRLF sono conversioni Git).

## Problemi residui e confini

- Nessun errore di compilazione residuo e nessun test applicativo fallito.
- Il motore MySQL non è stato validato: i test sono passati su H2; questo non
  certifica il comportamento del dialect MySQL o i suoi lock reali.
- Prima di un avvio V2 reale serve una fase autorizzata di provisioning e
  verifica schema/datasource e la configurazione del segreto HMAC. Il default
  storico con ddl-auto=update non è stato usato per avviare V2 su dati reali.
- I timeout/deadlock del database causano rollback; il client può ritentare
  con la stessa chiave e lo stesso token. Non c'è un retry automatico del
  servizio in una transazione SQL già fallita.
- Il flusso crea un ordine; non implementa pagamento, stock o tariffe di spedizione.

## Conferma sui database e sul repository

Tutte le connessioni eseguite dai test sono H2 in memoria con nomi isolati.
La suite checkout e quella modello applicano V1 programmaticamente solo in
memoria, con Flyway clean disabilitato, e usano Hibernate validate.
Le suite Spring preesistenti usano il profilo test e create-drop soltanto sui
loro database in memoria. La pulizia riguarda righe fixture, mai database
persistenti. Nessun accesso MySQL locale/remoto, H2 dev persistente, Flyway clean,
attivazione Flyway default/dev o modifica della V1. Nessun database persistente
è stato modificato o cancellato. Nessun commit, merge o push.

Lavoro fermato alla Fase 3, in attesa di approvazione.
