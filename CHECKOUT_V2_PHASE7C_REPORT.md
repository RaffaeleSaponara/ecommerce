# Checkout V2 — Fase 7C: avvii V2 esclusivi e sviluppo isolato

## Giudizio

**READY FOR MERGE del codice proposto**, dopo revisione e commit esplicitamente
autorizzato della Fase 7C. Nessun commit o merge è stato eseguito.
Il ramo committato resta cef97febe0a6715712b4ac1eebbe2373213db13d:
le correzioni di questa fase sono ancora nel working tree.

Il blocco rilevato in 7B è rimosso: non esiste più un percorso ordinario di avvio
default/dev con fallback al datasource storico e Hibernate update.
La qualifica del rilascio in produzione e la relativa autorizzazione restano separate.

## Configurazione e bootstrap

CheckoutStartupEnvironment è l'EnvironmentPostProcessor registrato in spring.factories.
Rifiuta modi di avvio non qualificati prima della creazione dei bean; ricontrolla
profili e configurazione finale mediante BeanFactoryPostProcessor prima di JPA.
Per checkout-release delega alla ReleaseEnvironment esistente, mantenendo tutte
le invarianti, requisiti di identità/privilegi e configurazione protetta.
Sono rifiutati provider/URL alternativi JPA, pool o JNDI e override DDL pericolosi.

| Modo | Comportamento |
| --- | --- |
| Nessun profilo / default | Rifiutato prima di datasource/JPA, anche con URL esplicito |
| dev | Ritirato e rifiutato; rimosso H2 file |
| checkout-release | Unico avvio persistente supportato, esplicito e solo; URL/account/HMAC obbligatori, identità e grant verificati, Hibernate validate |
| checkout-browser | Solo tramite bootstrap del launcher e datasource proprietario verificato; il solo nome del profilo nel JAR non basta |
| test | Solo con risorse/classi di test del progetto; H2 in memoria o datasource MySQL Testcontainers verificato; il JAR normale rifiuta test |

application.properties non contiene URL/username/password/driver di default
e non usa più DB_URL/DB_USERNAME/DB_PASSWORD con fallback.
application-dev.properties non contiene alcun datasource persistente.
Gli avvii applicativi usano validate, generate-ddl=false, Flyway runtime e SQL init
disabilitati; nessun Hibernate update/create su database persistenti.
L'eccezione create-drop resta confinata ai test H2 in memoria preesistenti.

DataInitializer richiede checkout-browser e app.data-initializer.enabled=true
esplicito; è disabilitato per ogni altro profilo, anche se il flag viene impostato.
Il runner pinna la configurazione, rifiuta datasource esterni e registra il
proprio GuardedSource. Le credenziali e l'HMAC restano effimeri/in memoria.

## Sviluppo locale

CHECKOUT_V2_DEVELOPMENT.md è stato scritto prima dell'implementazione.
Si riutilizza il runner già esistente; nessun nuovo servizio o database di sviluppo.

Dalla root, con Git, Maven Wrapper, JDK 21, PowerShell 7.2+ e Docker Desktop Linux:

    .\tools\checkout-browser.ps1 -Action Start -Port 9085
    .\tools\checkout-browser.ps1 -Action Status -RunId '<RunId>'
    .\tools\checkout-browser.ps1 -Action Stop -RunId '<RunId>'

Interfaccia: http://127.0.0.1:9085. Conservare il RunId restituito.
Non eseguire Maven clean mentre il runner è attivo.

MySQL 8.4.3 temporaneo su tmpfs, nessun volume persistente, porta DB dinamica
su loopback, credenziali casuali e identità/ownership verificate. V1 applicata
esplicitamente prima di JPA; Hibernate validate; tre prodotti demo esatti.
Ogni sessione riparte da dati nuovi, senza dipendere da utenti/catalogo/storico.

Il vecchio vincolo al nome del branch di integrazione è sostituito da controlli
del worktree Git e del blob V1 (file locale uguale a HEAD). Il runner sarà
utilizzabile anche dopo il futuro merge. Restano integri i controlli RunId,
owner del worktree, PID/CommandLine, label, UUID e storage delle risorse da arrestare.

Ultima prova reale sul codice finale:
- RunId 38de18b7a729437ea252fe6eac7b1a90.
- READY, catalogo HTTP 200, esattamente tre prodotti demo con nomi/prezzi attesi.
- Stop con lo stesso RunId riuscito; processo/container/listener rimossi.
- Evidenza non sensibile in target/phase7c-development-proof.json.

## Modello e database

V2 è l'unico modello mantenuto. Nessun adattatore order_details o importatore legacy.
La V1 qualificata è invariata rispetto a HEAD e al branch schema.
Nessun database legacy è stato contattato, eliminato o modificato.
Nessun DROP DATABASE, Flyway clean o migrazione persistente.
Tutte le operazioni SQL di preparazione/regressione usano solo nuove fixture
MySQL temporanee possedute dai test oppure H2 in memoria.

## Verifiche e risultati finali

Eseguito Maven clean verify, includendo package/repackage e Failsafe dopo il JAR.
Nessun test precedente è stato disabilitato o indebolito.

| Verifica | Passati | Fallimenti | Errori | Saltati |
| --- | ---: | ---: | ---: | ---: |
| Maven totale | 314 | 0 | 0 | 0 |
| Surefire, incluso tutto il precedente modello/checkout/release/MySQL | 304 | 0 | 0 | 0 |
| Failsafe: nuovo CheckoutRuntimeJarIT sul JAR normale | 10 | 0 | 0 | 0 |
| Client completo | 19 | 0 | 0 | 0 |

Conteggi dai report XML: 18 suite Surefire e una suite Failsafe.
Nei 314 sono inclusi 27 nuovi test di guardia/bootstrap/seed e 10 nuovi test JAR,
oltre ai precedenti 277. Sottosuite confermate: ReleaseEnvironment 42,
ReleaseMySql 24, WindowsPlanStore 7, schema MySQL 13, checkout MySQL 41.
Le sottosuite non si sommano nuovamente ai 314.

Test negativi: default/dev/profili arbitrari prima di ApplicationPreparedEvent,
URL legacy e H2 file rifiutati senza JDBC, URL/providers alternativi, override
DDL, override tardivi e assenza di un datasource proprietario. I valori legacy
nei test sono dati negativi di configurazione, non destinazioni contattate.

Test JAR: processi separati con java -jar, nessun classpath di test nel figlio.
Sei modi non qualificati, release incompleto e due override pericolosi rifiutati
prima di server/JPA. Release valido su MySQL posseduto avviato con validate,
homepage/catalogo 200, nessun seed o Flyway runtime e history V1 corretta.
Verificata l'assenza di classi/fixture/librerie di test e la V1 incorporata.
Arresto dei soli processi figli, con attesa delimitata di chiusura dei listener.

SHA-256 del JAR finale:
7ec2c05b24f2e976acc9f8aee1ba9ffc8814e607ff069a67b46a58aec2319de3.

Controlli aggiuntivi: parser/statici del runner, nove scenari mock di processo,
quattro nuovi controlli worktree/V1, 40 controlli statici release e compilazione/
check del launcher passati. Nessun container delle suite release/schema/checkout/JAR
rimasto; nessun container delle sessioni di sviluppo o listener 9085 rimasto.

Le prove intermedie hanno corretto soltanto errori nei nuovi test/client:
fixture negativa che raggiungeva un controllo DDL precedente a quello atteso,
import Java ambiguo, uso HttpClient.close non disponibile con --release 17 e
controllo PowerShell di array/ordinamento del catalogo. Le protezioni non sono
state rimosse o allentate. Il limite sorgenti Java 17 è preservato, con runtime JDK 21.
Le evidenze 7A ignorate sotto target sono state preservate e ripristinate dopo clean.

## File modificati

- pom.xml: Failsafe dopo package per i test del JAR.
- src/main/java/com/TreDL/ecommerce/DataInitializer.java: seed solo sviluppo isolato.
- src/main/resources/application.properties: default senza datasource/fallback e policy sicura.
- src/main/resources/application-dev.properties: profilo persistente ritirato.
- src/main/resources/META-INF/spring.factories: guardia globale.
- tools/checkout-browser.ps1: verifica worktree/V1 indipendente dal branch.
- tools/checkout-browser/CheckoutBrowserLauncher.java: bootstrap dedicato e datasource verificabile, policy JPA/SQL esplicite.
- tools/checkout-browser/browser-checkout.properties: validate/generate-ddl=false/SQL init never.
- tools/checkout-browser/runner-static-tests.ps1: nuovi controlli worktree/V1.
- CHECKOUT_V2_RELEASE_GUIDE.md: startup e rollback esclusivamente V2.

Nuovi:
- src/main/java/com/TreDL/ecommerce/release/CheckoutStartupEnvironment.java.
- src/test/java/com/TreDL/ecommerce/release/CheckoutStartupEnvironmentTests.java.
- src/test/java/com/TreDL/ecommerce/release/CheckoutRuntimeJarIT.java.
- CHECKOUT_V2_DEVELOPMENT.md.
- CHECKOUT_V2_PHASE7C_REPORT.md.

## Stato e limiti

Quindici file pertinenti non committati, nessuna modifica staged.
HEAD resta cef97febe0a6715712b4ac1eebbe2373213db13d.
ui_experiments resta 64d41e8e039603d00d99fdbd2a38da4fe848d06f.
feature/checkout-v2-schema resta d01bdf6c153b33f2c52e584b4b148d5df068549a.
Nessuna modifica ad altri worktree, merge, rebase, push, deploy o commit.

Nessun nuovo rischio bloccante individuato. Resta il warning noto Flyway
10.20.1/MySQL 8.4.3 e la qualificazione TLS/CA/rete/proxy/esecutore unico prima
della produzione. Il test JAR di questa fase è uno smoke pubblico di startup;
i flussi autenticati/CSRF/idempotenza sono coperti dalle suite Spring/MySQL e
dalla qualifica HTTPS 7A. Nessun nuovo collaudo visivo browser eseguito.

Checkout V2 crea ordini, non esegue pagamenti reali.
Attendere approvazione prima di commit e successivo merge.
