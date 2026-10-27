# Checkout V2 — revisione finale prima del commit

> Nota di consolidamento prima del commit: il POM autonomo `model-verification/pom.xml`
> era un workaround temporaneo ed e stato rimosso. Tutti i test del modello e dello
> schema H2 sono eseguiti dal POM principale con `.\mvnw.cmd -B clean verify`.
> I riferimenti a quel POM e agli stati intermedi nei paragrafi seguenti sono storici.

27 ottobre 2026. Worktree esclusivo:
`<checkout-v2-integration-worktree>`.
Branch: `feature/checkout-v2-integration`.
HEAD: `d01bdf6c153b33f2c52e584b4b148d5df068549a`.
Nessun commit, merge, push o intervento su altri worktree.

## Esito e problemi individuati

Nessun problema bloccante di codice rimane aperto nella revisione.
Sono stati individuati e corretti quattro problemi concreti:

| Gravità | Problema prima della revisione | Correzione |
| --- | --- | --- |
| Alta / P1 | Il rendering del carrello interpolava nome, descrizione, categoria e URL immagine del catalogo in innerHTML e attributi HTML. Contenuti malevoli nel catalogo potevano introdurre markup/event handler nella pagina autenticata. | Rendering con createElement, textContent, attributi impostati via DOM e listener espliciti; immagini limitate a percorsi locali ammessi. Nessun innerHTML o handler inline costruito da dati del catalogo. |
| Media / P2 | Un preventivo inizialmente valido poteva scadere mentre la transazione attendeva il lock di un prodotto e venire poi usato per creare l'ordine. | Ricontrollo della scadenza dopo l'acquisizione/refresh dei lock del catalogo, prima della decisione di creare l'ordine. Il replay già persistito resta indipendente dalla scadenza. |
| Media / P2 | Il client cancellava la chiave pendente su qualsiasi 4xx. Dopo una risposta persa, un successivo 401/403/408/429 poteva far perdere l'identità del tentativo originale. | Conservazione della coppia chiave/token per errori di sessione/CSRF, timeout e rate limit; il retry continua a usare gli stessi byte. Il 409 continua a invalidare la conferma e richiedere un nuovo preventivo. |
| Bassa / P3 | DataInitializer era registrato anche nei contesti Spring delle verifiche isolate e inseriva prodotti dimostrativi nell'H2 di test vuoto. Non riguardava database persistenti, ma rendeva meno controllata la verifica. | Registrazione condizionale e app.data-initializer.enabled=false nel classpath di test. Il comportamento preesistente default/dev è preservato; il test dei prezzi dell'initializer continua a usare un repository mock. |

La gravità P1 riguarda l'iniezione attraverso dati del catalogo: non è stata
aggiunta alcuna API pubblica di scrittura del catalogo né è stato assunto un
accesso non autorizzato al database. È stato eliminato il sink pericoloso.

Anche il riepilogo indicativo del carrello ora preserva i decimali JSON e usa
centesimi BigInt. Non rimangono calcoli monetari floating point nel carrello;
il backend resta l'unica fonte del totale ordine e richiede la conferma firmata.

## Sicurezza dei preventivi verificata

- HMAC-SHA256 sul payload canonico UTF-8, con firma confrontata tramite
  MessageDigest.isEqual. Il controllo di ricodifica canonica tramite stringa
  avviene soltanto dopo la verifica crittografica, senza accettare MAC parziali.
- Il segreto di produzione proviene da CHECKOUT_QUOTE_SECRET_BASE64 o dalla
  proprietà esterna checkout.quote-secret-base64; nessun valore runtime
  hardcoded o fallback casuale del server. La chiave deve avere almeno 32 byte.
- Il valore costante nei test è una fixture pubblica, confinata a src/test.
  Il JAR finale è stato ispezionato: fixture, classi di test e Flyway runtime
  non sono presenti; application.properties contiene solo il riferimento env.
- Payload protetto: versione, customerId, scadenza, valuta EUR, totale e righe
  con productId, nome, quantità, prezzo unitario e subtotale.
- L'intestatario è confrontato con il cliente risolto dal Principal autenticato;
  prezzi, cliente e totale non sono affidati a campi arbitrari del client.
- Firma alterata, token non canonico o intestatario diverso: rifiuto. Scadenza:
  controllata all'ingresso e dopo l'attesa dei lock catalogo. Il momento di
  decisione precede la scrittura dell'ordine; non si usa la data del client.
- Chiave assente: nessun nuovo ordine, risposta 503. Un replay già memorizzato
  può restituire il proprio ordine senza ricrearlo, anche con segreto assente.
- Nessun logger, risposta o messaggio applicativo include il segreto HMAC.
  Non è stato caricato o stampato alcun segreto reale durante la verifica.

## Idempotenza e transazioni verificate

- Idempotency-Key viene convertita in UTF-8 rigoroso, 1–128 byte, senza trim,
  normalizzazione o folding. Il lookup include sempre il cliente autenticato.
- request_hash è il vero SHA-256 della richiesta canonica confermata documentata
  nel report Fase 3: versione + confirmPrices=true + token firmato esatto.
- Replay prima dei controlli del carrello: restituisce lo stesso ID e gli stessi
  snapshot anche dopo svuotamento, riempimento successivo o scadenza del token.
- Stessa chiave con diverso payload confermato valido: HTTP 409, verificato
  anche con richieste concorrenti. Le richieste malformate restano errori 400.
- Il lock PESSIMISTIC_WRITE sul cliente precede accesso e mutazioni del carrello;
  quote, checkout e mutazioni condividono lo stesso lock stabile.
- CheckoutService usa READ_COMMITTED per vedere il commit della richiesta che
  lo precede al lock e una sola transazione per ordine, righe e svuotamento.
- UNIQUE V1 su (id_customer, idempotency_key) resta una protezione persistente,
  oltre alla serializzazione del servizio. Il contratto JDBC ne verifica il
  rifiuto dei duplicati; i test del servizio verificano le corse reali H2.
- Nessun catch prosegue in una transazione SQL fallita. Errori durante ordine,
  dopo flush delle righe o prima del commit causano rollback completo; verificati
  conservazione del carrello e riuso della chiave dopo rollback.
- Prodotti letti in ordine di ID con PESSIMISTIC_READ e refresh, mantenendo i
  lock fino al commit: un writer catalogo già attivo viene atteso e i dati
  vengono riletti; un writer successivo non modifica il prezzo dopo la verifica.
- La cancellazione di un prodotto ancora presente nel carrello è impedita
  dalla FK V1. Se viene prima rimosso dal carrello e poi eliminato, il vecchio
  preventivo viene rifiutato senza ordini parziali. La V1 non modella stock o
  un indicatore separato di disponibilità commerciale.

## Compatibilità e controlli preservati

- Hibernate validate passa sullo schema V1 originale, applicato solo a H2
  volatile nei test del modello e del checkout. Nessuna modifica alla V1.
- idempotency_key: byte[] VARBINARY(128), NOT NULL, copie difensive.
- request_hash: byte[] BINARY(32), NOT NULL, copie difensive.
- line_number: int persistito, positivo, univoco per ordine e lettura ordinata;
  importi DECIMAL(19,2), valuta CHAR(3) EUR, date in UTC per convenzione.
- Nessun endpoint restituisce entity: carrello, catalogo, preventivo e checkout
  restituiscono DTO; password, hash di richiesta e chiavi idempotenti non sono
  esposti. Il token di preventivo contiene dati di ordine autenticati dalla
  firma, non il segreto di firma, e non costituisce da solo autorizzazione.
- SecurityConfig, CartService e CustomersRepository non sono stati modificati
  dalla revisione. Sessione, autorizzazione, form logout e CSRF rimangono attivi.
- Nuovi ordini sempre tramite JSON, conferma esplicita e preventivo firmato;
  nessun percorso legacy o adattatore privo di conferma è stato introdotto.
- Frontend: conferma obbligatoria, errori 409, retry e reload coperti dai test;
  quantità e prezzi del browser non decidono gli importi del backend.
- DataInitializer non è registrato nei contesti Spring di test. Il test diretto
  dei prezzi dimostrativi usa soltanto un mock, senza scritture JDBC.
- Flyway rimane disabilitato nei profili default, dev e test. Le sole migrate
  programmatiche sono su jdbc:h2:mem:, con cleanDisabled(true). Nessun clean.

## Test aggiunti durante la revisione

7 casi Java, tutti in CheckoutIntegrationTests:

1. Assenza del bean DataInitializer nel contesto Spring isolato.
2. Segreto assente: nuovo checkout rifiutato senza modifiche; replay persistito
   ancora leggibile senza creazione di un altro ordine.
3. Prodotto rimosso dal carrello ed eliminato: vecchio preventivo rifiutato.
4. Writer catalogo precedente: attesa del lock e rifiuto del prezzo cambiato.
5. Preventivo che scade durante l'attesa del lock catalogo: rifiuto con rollback.
6. Writer catalogo successivo: attende il commit checkout; snapshot invariato.
7. Richieste concorrenti con stessa chiave e differenti preventivi validi: 409,
   con un solo ordine persistito.

5 casi client aggiunti, conservando tutti i precedenti:

- 401, 403, 408 e 429 dopo una risposta persa: quattro casi che verificano
  conservazione e riuso esatto di chiave e payload fino al successo.
- Dati catalogo contenenti markup e URL eseguibili: nessun sink HTML o handler
  inline e precisione monetaria conservata al limite DECIMAL(19,2).

## Risultati finali

Comando completo dalla radice del worktree:

```powershell
.\mvnw.cmd -B clean verify
```

BUILD SUCCESS: compilazione, test, JAR e repackage completati.
**163 casi: 150 passati, 0 falliti, 0 errori, 13 saltati.**
I 13 saltati sono esclusivamente i test MySQL Testcontainers preesistenti,
per Docker non disponibile. Nessun test è stato disabilitato o escluso.
CheckoutIntegrationTests ora esegue 30 casi; gli altri controlli di sicurezza,
schema, modello e concorrenza del carrello restano verdi.

Test client:

```powershell
node --test src/test/js/checkout-flow.test.cjs
```

**10 passati, 0 falliti, 0 saltati.** Il test esegue lo script effettivo della
pagina con DOM/fetch simulati; non è un collaudo del layout in browser reale.

Log: target/final-review-verify.log e target/final-review-client.log.
Report JUnit: target/surefire-reports/. git diff --check: nessun errore.

## Rischi residui

**Bloccanti per questa revisione di codice:** nessuno identificato dopo le
correzioni. Nessuna richiesta di commit è stata eseguita o anticipata.

**Precondizioni per un eventuale avvio reale, non eseguito né autorizzato:**
configurare un segreto HMAC reale esterno, predisporre il datasource V1
appropriato e verificare MySQL/locking sul motore target in una fase dedicata.
La validazione H2 non certifica MySQL. I profili storici default/dev non vanno
usati per migrare automaticamente database esistenti con questo modello.

**Non bloccanti per la revisione del codice:** assenza di una prova E2E in un
browser reale e di una prova multi-processo/MySQL; le corse sono state provate
su transazioni e connessioni H2 distinte. Timeout/deadlock del database fanno
rollback e richiedono retry con la medesima chiave. Nessuna logica di stock o
pagamento è prevista dal contratto V1/checkout attuale.

## File modificati in questa revisione

- src/main/java/com/TreDL/ecommerce/DataInitializer.java
- src/main/java/com/TreDL/ecommerce/service/CheckoutService.java
- src/main/java/com/TreDL/ecommerce/service/QuoteSigner.java
- src/main/resources/templates/cart.html
- src/test/resources/application.properties
- src/test/java/com/TreDL/ecommerce/service/CheckoutIntegrationTests.java
- src/test/js/checkout-flow.test.cjs
- CHECKOUT_V2_FINAL_REVIEW.md (questo report, creato)

Le modifiche delle fasi precedenti restano non committate nel worktree e sono
elencate nei report storici CHECKOUT_V2_MODEL_REPORT.md e CHECKOUT_V2_PHASE3_REPORT.md.

## Conferma operativa

Nessun database persistente è stato modificato o cancellato. Tutte le
connessioni dei test sono H2 in memoria; nessun collegamento MySQL locale o
remoto e nessun uso dell'H2 persistente dev. Le cancellazioni di righe nelle
fixture riguardano solo gli H2 di test. Nessun Flyway clean o attivazione
Flyway default/dev. ui_experiments resta invariato a
64d41e8e039603d00d99fdbd2a38da4fe848d06f. Nessun altro worktree modificato.

Lavoro concluso e fermato in attesa dell'approvazione del report. Nessun commit,
merge o push.
