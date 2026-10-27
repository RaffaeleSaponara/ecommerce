# Revisione sicurezza applicativa — Agente 2

Baseline verificata prima di qualsiasi modifica:
`06110ed2ef4d3d38b4298014ff8c629e45d4d643`, messaggio `jwt fix`.
Worktree inizialmente pulito. Lavoro esclusivo nel worktree assegnato.

## Riscontri e correzioni

- CSRF era disabilitato globalmente, anche con autenticazione tramite cookie.
  Ripristinata la protezione standard Spring Security senza esenzioni.
  Aggiornati login, registrazione, logout in tutti i sette template e le Fetch
  POST/DELETE del carrello. Le richieste senza token o con token errato ricevono 403.
- `GET /cart/checkout` creava ordini e svuotava il carrello: ora solo POST con
  autenticazione e CSRF. GET e HEAD restituiscono 405 senza chiamare i servizi.
  La sequenza createOrder/clearCart e i relativi confini transazionali restano invariati.
- `POST /cart/add` e checkout restituivano entità con percorso verso
  `Customers.password` e relazioni cicliche fra clienti, ordini, prodotti e carrelli.
  Ora restituiscono rispettivamente `CartDTO` e il nuovo `OrderDTO`, con prodotti
  convertiti in `ProductDTO`. Nessun oggetto cliente o relazione JPA raggiunge il JSON.
  Il catalogo e la lettura del carrello usavano già DTO.
- `GET /cart/view` poteva creare e salvare un carrello vuoto tramite `getCart`.
  Ora usa una lettura dedicata: se manca, restituisce una rappresentazione vuota
  senza salvataggi. Il mapping DTO gestisce anche una lista prodotti null.
- La configurazione mescolava form login con endpoint JWT inutilizzati:
  `/auth/login` richiedeva già autenticazione e produceva un cookie non consumato
  da alcun filtro registrato; `/auth/logout` cancellava quel cookie e invalidava
  la sessione senza usare il logout Spring Security. Rimossi questi due endpoint.
  Il frontend non li usa, non usa bearer token e non dipende dal JWT.
  Resta il form login Spring Security, con BCrypt, persistenza del contesto,
  protezione contro session fixation e logout POST gestiti dal framework.
- Le Fetch del carrello da anonimo potevano seguire il redirect al login e vedere
  un 200 HTML come successo: le API `/cart/**` ora rispondono 401.
- `/auth/check` non scrive più un indicatore ridondante in sessione e verifica
  direttamente Authentication, escludendo AnonymousAuthenticationToken.

Il commit `jwt fix` aveva già corretto la chiave incorporata: `JwtUtil` richiede
una chiave esterna Base64 di almeno 32 byte e il filtro ignora token malformati,
scaduti o firmati con altre chiavi. Non sono vulnerabilità ancora presenti.
Il codice JWT e i suoi test restano conservati, ma non sono componenti attivi
dell'autenticazione. Nessuna nuova dipendenza; `pom.xml` invariato.

## File modificati

- `src/main/java/com/TreDL/ecommerce/SecurityConfig.java`
- `src/main/java/com/TreDL/ecommerce/controller/AuthController.java`
- `src/main/java/com/TreDL/ecommerce/controller/CartController.java`
- `src/main/java/com/TreDL/ecommerce/dto/CartDTO.java`
- `src/main/java/com/TreDL/ecommerce/dto/OrderDTO.java` (nuovo)
- `src/main/java/com/TreDL/ecommerce/security/JwtUtil.java`
- `src/main/java/com/TreDL/ecommerce/service/CartService.java`
- `src/main/resources/application.properties`
- `src/main/resources/templates/cart.html`
- `src/main/resources/templates/home.html`
- `src/main/resources/templates/home1.html`
- `src/main/resources/templates/login.html`
- `src/main/resources/templates/prodotti.html`
- `src/main/resources/templates/registrazione.html`
- `src/main/resources/templates/user.html`
- `src/test/java/com/TreDL/ecommerce/security/SessionSecurityTests.java` (nuovo)
- `JWT_CONFIGURATION.md`
- `SECURITY_REVIEW.md` (nuovo)

## Verifica riproducibile

Compilazione: `mvn -o -DskipTests compile`, riuscita con Java 21, target Java 17.

Suite completa, senza download né servizi esterni, usando la dipendenza Mockito
già presente nella cache locale per evitare l'aggancio dinamico bloccato nell'ambiente:

```powershell
mvn -o '-DargLine=-javaagent:C:/Users/sapor/.m2/repository/org/mockito/mockito-core/5.14.2/mockito-core-5.14.2.jar' test
```

Risultato finale: **31 test, 0 fallimenti, 0 errori, 0 saltati**:
25 SessionSecurityTests, 4 JwtSecurityTests, 1 EcommerceApplicationTests,
1 CartServiceTests. `git diff --check` senza errori.

I test MVC di sicurezza usano la vera catena Spring Security, il vero encoder e
il servizio UserDetails, con repository e servizi applicativi simulati.
I test di contesto e dominio esistenti usano esclusivamente il profilo `test`
con H2 in memoria. Nessun avvio dell'applicazione contro datasource di sviluppo
o produzione; nessuna modifica allo schema o migrazione.

Copertura dedicata: credenziali valide/errate, sessione persistente e ID ruotato,
cookie JWT ignorato, token CSRF assente/errato/valido (anche header letto dall'HTML
effettivamente renderizzato), utenti anonimi, GET/HEAD checkout senza effetti,
logout con invalidazione e GET senza effetti, DTO con grafi JPA ciclici contenenti
un hash password, ruolo USER e hash BCrypt in registrazione, form e meta tag
renderizzati per tutte le pagine, catalogo pubblico e lettura senza salvataggi.

Il primo tentativo standard è stato interrotto per il problema di attach Mockito.
La prima esecuzione con javaagent ha evidenziato due aspettative errate nei nuovi
test (GET logout è 404; `id_customer` è un campo scalare ammesso); corrette.
Log finale: `target/security-full-tests.log`; report: `target/surefire-reports/`.

## Limiti e lavoro ancora aperto

- Checkout ancora non atomico, come richiesto: creazione ordine e svuotamento
  possono fallire separatamente; niente idempotenza o protezione da doppio invio.
  Sono esclusi da questo intervento anche importi monetari e concorrenza del dominio.
- Pubblicazione: servono HTTPS e cookie Secure nell'ambiente di produzione,
  gestione delle sessioni per più istanze e protezione dai tentativi ripetuti di login.
- Il frontend inserisce dati catalogo tramite `innerHTML`; prima di accettare
  cataloghi da fonti non fidate serve costruzione DOM sicura/escaping. In questa
  revisione non è stato dimostrato un percorso HTTP per scrivere payload nel catalogo.
- I componenti JWT legacy e le dipendenze restano da rimuovere in un eventuale
  intervento successivo che aggiorni anche i test storici e il pom.
- Non è stata eseguita una scansione CVE delle dipendenze né una prova su browser
  reale o su infrastruttura HTTPS; i controlli effettuati sono compilazione e test MVC/H2.

## Coordinamento con l'altro agente

Nessun test di dominio preesistente è stato modificato e non sono stati toccati
altri worktree. Nessun commit, merge, rebase o push.
I nuovi test dell'altro agente dovranno usare POST e token CSRF per il checkout;
le asserzioni HTTP dovranno aspettarsi DTO e 401 per le API carrello anonime.
Il servizio checkout e il suo comportamento transazionale restano invariati.
