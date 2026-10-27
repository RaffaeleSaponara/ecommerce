# Checkout V2 — Fase 5A: ambiente browser implementato, non avviato

## Stato e ambito

Branch: `feature/checkout-v2-integration`.
HEAD invariato: `f158588422851224d853bb63d5b4affc34fa16dd`.
Nessun commit, merge o push. Questa fase ha eseguito soltanto compilazioni,
check statici e test client con DOM/fetch simulati. Nessun avvio Spring,
Docker o container; nessuna migrazione o connessione a database.

## File

Creati:

- tools/checkout-browser.ps1
- tools/checkout-browser/CheckoutBrowserLauncher.java
- tools/checkout-browser/browser-checkout.properties
- tools/checkout-browser/runner-static-tests.ps1
- src/test/js/catalog-rendering.test.cjs
- src/test/js/static-assets.test.cjs
- CHECKOUT_V2_PHASE5A_REPORT.md

Modificato: src/main/resources/templates/prodotti.html.
Rinominate senza modifiche al contenuto:

- src/main/resources/static/CSS/account.css -> static/css/account.css
- src/main/resources/static/CSS/home.css -> static/css/home.css
- src/main/resources/static/CSS/prodotti.css -> static/css/prodotti.css

Le rinomine case-only sono state registrate con git mv a doppio passaggio per
funzionare anche su Windows: risultano nello staging, senza creare commit.
Gli altri file restano modificati/non tracciati. Nessuna modifica a default,
dev, migrazione V1 o logica transazionale applicativa.

## Runner e classpath

Il runner richiede PowerShell 7.2 o successivo, JDK 17+ con java/javac,
Maven Wrapper e Docker Desktop Linux. Start controlla il branch e il daemon
locale senza tentare di avviare Docker o cambiare il suo contesto.

La preparazione usa soltanto i goal Maven compile e dependency:build-classpath,
con scope dipendenze test per disporre di Flyway. Non esegue test di integrazione.
Il runtime effettivo e assemblato in una directory nuova per RunId:

1. Soli bytecode main con corrispondente sorgente Java attuale, inclusi i nested.
2. Sole risorse src/main/resources, ricopiate da sorgente per non ereditare
   risorse obsolete/casing precedenti nel target generico.
3. Classi del solo launcher compilate separatamente con --release 17.
4. Dipendenze JAR; nessuna directory target/test-classes o src/test/resources.

Il ramo Java check verifica configurazione e classpath e termina prima del
ramo serve. Rifiuta classi/risorse di test del progetto, configurazioni diverse
da quelle previste e ddl-auto diverso da validate. La funzione reale di
assemblaggio del runner e stata invocata separatamente e verificata: restituisce
un solo classpath e il launcher compilato passa check sul runtime assemblato.
Non e stato invocato Start.

Spring sara avviato con la sola configurazione esterna obbligatoria tramite
spring.config.location e il profilo checkout-browser. Non vengono caricate
le configurazioni applicative storiche. Il datasource viene inoltre registrato
esplicitamente dal launcher prima di JPA: configurazioni datasource esterne
sono rifiutate e non esiste un percorso di fallback al MySQL applicativo.

## Comportamento previsto di Start (non eseguito)

- Crea e stampa un RunId nuovo, prima di creare risorse runtime.
- Genera 32 byte HMAC con RandomNumberGenerator e li passa esclusivamente
  nell'ambiente del processo Java figlio. Non modifica l'ambiente del chiamante.
- Filtra dal processo figlio gli override Spring, datasource, Docker e opzioni
  Java automatiche ereditate. Non inoltra argomenti applicativi arbitrari.
- Avvia Java senza finestra aggiuntiva; il segreto resta nell'ambiente/memoria
  del figlio per tutta la vita dell'applicazione, non negli argomenti CLI.
- Il launcher crea un solo MySQL 8.4.3 via Docker CLI, credenziali casuali
  effimere e database checkout_browser_<RunId>. Nessun riuso.
- Dati su tmpfs /var/lib/mysql da 1 GiB; nessun volume persistente o bind mount.
- Pubblicazione equivalente a --publish 127.0.0.1::3306: porta host dinamica,
  mai la porta storica 3306. Il web e vincolato a 127.0.0.1:9085.
- Verifica container ID, immagine, label RunId e worktree, storage e binding;
  ricava UUID server dal file auto.cnf dentro il container posseduto.
- Attende soltanto quel server con timeout e verifica URL, DATABASE(), UUID e
  versione prima della migrazione. Nessun altro datasource viene tentato.
- Applica solo V1 al database nuovo/vuoto: cleanDisabled(true),
  baselineOnMigrate(false), createSchemas(false).
- Avvia Spring/Hibernate validate, open-in-view=false e Flyway automatico false.
- DataInitializer e esplicitamente abilitato soltanto qui, dopo la migrazione
  e sotto il datasource controllato, per i tre prodotti dimostrativi.
- Il datasource ricontrolla identita sulle connessioni e prima delle istruzioni
  JDBC/commit; rifiuta cambi di catalogo e creazione/eliminazione di database.
- Registra READY soltanto dopo il completamento di Spring e degli initializer.

Il codice usa Docker CLI direttamente, con --rm e label di proprieta; non
utilizza container riusabili o un lifecycle JUnit che terminerebbe il collaudo.
Non richiede Ryuk per il browser: la supervisione/cleanup appartiene al runner.
Le suite Testcontainers esistenti non sono state modificate.

## Segreti, manifest e arresto

Password DB/root e HMAC non sono scritti in file, configurazioni, argomenti o
manifest. I valori generati sono inoltre redatti per linee su stdout/stderr,
anche se emessi in write separati; logging Spring e SQL del launcher sono
soppressi. Le credenziali di fixture rimangono naturalmente nell'ambiente del
container temporaneo e nella memoria del processo, non nel repository.

Il manifest atomico state.json contiene soltanto RunId, hash del worktree,
PID e istante di creazione, fase, ID container, UUID server, nome database e
porte. Il launcher non procede alla creazione/migrazione/avvio se non riesce
a registrare i passaggi critici. Segnali Stop e percorsi sono vincolati al RunId;
non sono ammessi reparse point o percorsi arbitrari per lo stato.

Stop attesta PID, istante e comando del launcher prima di segnalarlo. Non
termina processi generici: scrive un segnale del solo RunId, il launcher chiude
Spring ordinatamente e solo dopo arresta il container. Il container usa --rm;
un eventuale container posseduto rimasto nello stato Created/stopped puo essere
rimosso per ID dopo le stesse verifiche di ownership/storage.

Errori di avvio e shutdown hook tentano cleanup del solo container posseduto.
Se Java e morto, Stop puo recuperare le risorse tramite label RunId + worktree
e ID, senza password e senza comandi prune. Se Java e ancora vivo ma non
risponde, il runner preserva il database e segnala timeout, anziche rimuoverlo
sotto un'applicazione ancora attiva. Un daemon non raggiungibile produce
CLEANUP_REQUIRED; ripetere Stop quando torna disponibile.

I file tecnici non sensibili sotto target restano disponibili per diagnosi;
nessuna cancellazione ricorsiva o pulizia globale e implementata.

## Catalogo e CSS

/prodotti ora costruisce le card con createElement, textContent, dataset e
addEventListener. Nome, descrizione e categoria non vengono interpretati come
HTML. Le immagini ammettono solo percorsi locali previsti e hanno un fallback.
Classi CSS, struttura delle card, prezzo informativo e comportamento Aggiungi
con CSRF sono conservati. Il totale da confermare rimane quello server firmato;
il formato informativo del prezzo catalogo non e usato per creare l'ordine.

Il casing CSS e ora coerente con tutte le URL /css/... dei template. I tre
fogli sono rinomine al 100%, senza modifiche di stile. Il test controlla ogni
segmento con confronto case-sensitive, anche quando eseguito su Windows.

## Verifiche realmente eseguite

- Parsing AST PowerShell: passato.
- Test PowerShell con Docker interamente simulato: passati. Coprono RunId e
  stato, run estraneo, binding pubblico, volume persistente, UUID errato,
  risorsa propria, risorsa gia assente e recupero Created-only.
- Maven compile + dependency:build-classpath: BUILD SUCCESS; nessun test
  Spring/MySQL eseguito e nessun goal di avvio.
- javac --release 17 -proc:none: compilazione launcher riuscita.
- Java check: configurazione, classpath, redazione segreti, ownership e
  manifest atomico privo di segreti passati.
- Classpath deliberatamente contaminato con target/test-classes: rifiutato.
- Configurazione deliberatamente alterata con ddl-auto=update: rifiutata.
- Assemblaggio della funzione reale del runner: passato, con risorse main
  isolate e CSS corretto. Solo compilazione e check, non Start.
- Test client: 15 passati, 0 fallimenti, 0 skip (10 checkout esistenti,
  4 catalogo e 1 verifica casing CSS).
- git diff --check: nessun errore.

Comandi di verifica che non avviano servizi:

```powershell
.\tools\checkout-browser\runner-static-tests.ps1
node --test src/test/js/checkout-flow.test.cjs src/test/js/catalog-rendering.test.cjs src/test/js/static-assets.test.cjs
```

I 200 test backend della Fase 4 non sono stati rieseguiti: questa fase vieta
avvio di Spring e container. Non vengono quindi presentati come un risultato
nuovo relativo a queste modifiche.

## Comandi definitivi, da NON eseguire prima dell'approvazione runtime

```powershell
.\tools\checkout-browser.ps1 -Action Start -Port 9085
```

Start stampa RunId e attende READY. Da un altro terminale nello stesso worktree:

```powershell
.\tools\checkout-browser.ps1 -Action Status -RunId '<RunId>'
.\tools\checkout-browser.ps1 -Action Stop -RunId '<RunId>'
```

Il browser usera http://127.0.0.1:9085. Al primo accesso usare una sessione
browser pulita e registrare un account fittizio; nessun account reale predefinito.
Non passare credenziali o HMAC da riga di comando.

## Limiti e decisioni prima dell'avvio

- Serve approvazione esplicita per eseguire Start, migrazione e collaudo runtime.
- Il comportamento effettivo Docker/Spring, l'avvio della UI e lo shutdown reale
  non sono ancora stati provati: nessun servizio e stato avviato in questa fase.
- Prima di Start devono essere disponibili PowerShell 7.2+, java/javac,
  Docker Desktop Linux e la porta 9085. Il runner verifica e rifiuta condizioni
  incompatibili; non avvia Docker e non modifica i suoi contesti.
- Timeout, interruzioni forzate del sistema e daemon indisponibile possono
  richiedere un successivo Stop dello stesso RunId. Le guardie privilegiano
  la conservazione delle risorse quando non possono attestarne la proprieta.
- Il warning Flyway/MySQL gia documentato nella Fase 4 non e stato risolto
  aggiornando dipendenze; nessuna dipendenza runtime applicativa e stata aggiunta.

ui_experiments e feature/checkout-v2-schema sono invariati. Nessun altro
worktree modificato e nessun database persistente toccato. Nessun commit,
merge o push. Lavoro fermato in attesa di approvazione.
