# Checkout V2 — Fase 5D

Branch: `feature/checkout-v2-integration`.
HEAD verificato: `f158588422851224d853bb63d5b4affc34fa16dd`.
Nessun commit creato.

## Analisi e correzioni

### Coerenza del preventivo

`CheckoutService.quote` esegue la lettura in una transazione READ_COMMITTED,
acquisendo prima il lock PESSIMISTIC_WRITE sul cliente tramite
`CustomersRepository.findByIdForUpdate`. Solo dopo legge carrello e occorrenze;
i prodotti vengono bloccati in lettura e aggiornati dalla sorgente DB in ordine
crescente di ID. Aggiunta, rimozione e svuotamento in CartService acquisiscono
lo stesso lock sul cliente prima di accedere al carrello. Nessuna modifica ai
lock, alle transazioni del backend, allo schema o al checkout.

Se il preventivo acquisisce il lock per primo, descrive correttamente lo stato
precedente alla mutazione successiva. Questo non costituisce una lettura sporca:
e' uno dei due ordini di serializzazione ammessi. Una modifica successiva rende
il preventivo superato, e checkout risponde 409 senza creare ordini.

Nel client della Fase 5C la richiesta DELETE e quella POST /cart/quote potevano
partire insieme: l'ordine dei clic non garantiva l'ordine di acquisizione del
lock sul server. La pagina ora esclude reciprocamente mutazioni e generazione
del preventivo. Il blocco resta attivo fino al completamento del refresh del
carrello. Anche checkout e richieste duplicate vengono bloccati durante queste
operazioni. Un errore rilascia il blocco senza ripristinare una conferma vecchia.
CSRF, token confermato e chiave conservata per i retry restano invariati.

### Checkbox

Il selettore generico input applicava larghezza 100% e altezza 48px anche alla
checkbox. Una regola limitata a `#checkoutForm input[type="checkbox"]` imposta
20x20px, allineamento e spaziatura, con outline visibile al focus da tastiera.
Markup, label cliccabile, stato disabled e logica di conferma non sono cambiati.
La verifica di questa fase comprende CSS e test client, non un nuovo collaudo
visivo dell'applicazione.

### Arresto del runner

Il processo poteva terminare tra Get-Process e Get-CimInstance: l'accesso a
CommandLine su un risultato nullo falliva con StrictMode. Ora il risultato CIM
viene controllato prima dell'accesso. Sono gestite anche eccezioni durante la
lettura di StartTime se una nuova verifica conferma che il processo e' assente.
Un PID ancora presente ma non attestabile, riutilizzato o con comando estraneo
continua a essere rifiutato. Non e' stato aggiunto alcun kill di processi.
Le verifiche di ownership del container e lo Stop mediante RunId restano attivi.

## Test aggiunti

- Quattro casi MySQL concorrenti: add/remove, preventivo prima/dopo la mutazione.
  Thread, transazioni e CONNECTION_ID distinti; verifica del blocco del secondo
  attore, del contenuto firmato, delle quantita' e del totale. Un preventivo
  superato viene rifiutato dal checkout senza consumare il carrello.
- Quattro test client: add e remove bloccano preventivo/checkout anche durante
  il refresh; preventivo in corso blocca mutazioni e duplicati; errore di
  mutazione rilascia il blocco senza ripristinare una conferma superata.
- Nove scenari mock del processo: assente, uscita prima di CIM, CommandLine
  nullo dopo uscita, uscita durante StartTime, PID vivo non attestabile,
  comando estraneo, CommandLine nullo con PID vivo, launcher proprio vivo,
  PID riutilizzato. Nessun processo reale terminato dai test statici.

## Risultati effettivi

| Verifica | Passati | Fallimenti | Errori | Saltati |
| --- | ---: | ---: | ---: | ---: |
| Nuovi casi MySQL, esecuzione mirata | 4 | 0 | 0 | 0 |
| Maven `./mvnw.cmd -B clean verify` | 204 | 0 | 0 | 0 |
| Di cui Spring/MySQL | 41 | 0 | 0 | 0 |
| Di cui schema MySQL | 13 | 0 | 0 | 0 |
| Tutti i test client (`node --test src/test/js/*.test.cjs`) | 19 | 0 | 0 | 0 |

Conteggi Maven letti dai report XML in target/surefire-reports; i quattro casi
mirati sono compresi nella suite completa e non vanno sommati ai 204.
Test statici PowerShell e relativi mock: PASS.
Compilazione launcher con JDK 21, --release 17: PASS.
Entry point `CheckoutBrowserLauncher check`: PASS per configurazione, classpath
senza risorse/classi di test del progetto, redazione segreti, ownership e manifest.
Nessun deadlock o timeout di completamento osservato; i timeout brevi nei test
concorrenti verificano intenzionalmente l'attesa sui lock prima del rilascio.
Gli errori SQL attesi dai test negativi dei vincoli non sono fallimenti della suite.

## Isolamento e rischi residui

Solo MySQL 8.4.3 temporanei Testcontainers e H2 in memoria. Ryuk attivo e reuse
disabilitato per le esecuzioni Maven. Datasource MySQL ricavati dai container;
la fixture Spring verifica URL, database e UUID prima delle operazioni JDBC.
V1 applicata prima di JPA, Hibernate validate e DataInitializer disabilitato
nella suite Spring/MySQL. Nessun accesso a database persistenti, nessun
Flyway clean, nessun avvio del runner browser in questa fase.
I cinque ID container registrati nelle due esecuzioni Maven risultano assenti
al controllo finale. Nessuna pulizia generica o rimozione di risorse estranee.

Nessun rischio bloccante individuato. Modifiche da altre schede o altri client
possono ancora invalidare un preventivo: 409 e riconferma rimangono il contratto
previsto. Resta il warning gia' presente di Flyway sul supporto dichiarato di
MySQL 8.4; migrazione V1 e tutte le verifiche MySQL sono passate. Nessuna modifica
alle dipendenze introdotta per questo warning.

## File della Fase 5D

- src/main/resources/templates/cart.html
- src/main/resources/static/css/account.css
- src/test/java/com/TreDL/ecommerce/service/CheckoutMySqlIntegrationTests.java
- src/test/js/checkout-flow.test.cjs
- tools/checkout-browser.ps1
- tools/checkout-browser/runner-static-tests.ps1
- CHECKOUT_V2_PHASE5D_REPORT.md

Le modifiche approvate della Fase 5A, incluse le rinomine CSS gia' nell'index,
restano presenti. Nessun test esistente rimosso, disabilitato o indebolito.
Nessuna modifica agli altri worktree o ai riferimenti degli altri branch.
