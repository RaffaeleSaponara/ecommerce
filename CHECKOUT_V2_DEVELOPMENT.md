# Checkout V2 — sviluppo e avvii sicuri (piano Fase 7C)

Decisione: Checkout V2 è l'unico modello supportato. Nessuna importazione dello
storico, compatibilità con order_details o migrazione dei dati legacy.
La V1 qualificata resta immutata. Nessun database esistente viene contattato
o cancellato per questa modifica.

## Workflow documentato prima dell'implementazione

Si riutilizza il runner già approvato, con comandi e porta invariati.
Il profilo dev precedente su H2 file viene ritirato; gli avvii default/dev
falliscono prima di JPA. Non si aggiunge un nuovo servizio di sviluppo.
Il runner verifica il worktree Git corrente e che V1 coincida con il blob
approvato nel suo HEAD, senza vincolo al nome del branch. Può quindi essere
usato anche dopo il futuro merge nello stabile. RunId/owner restano legati
al percorso del singolo worktree: nessuna pulizia coinvolge altri checkout.

Dalla root, con Git, Maven Wrapper, JDK 21, PowerShell 7.2+ e Docker Desktop Linux attivo:

    .\tools\checkout-browser.ps1 -Action Start -Port 9085
    .\tools\checkout-browser.ps1 -Action Status -RunId '<RunId>'
    .\tools\checkout-browser.ps1 -Action Stop -RunId '<RunId>'

Aprire http://127.0.0.1:9085. Start restituisce il RunId; conservarlo per Stop.
Non eseguire Maven clean mentre il runner è attivo, perché lo stato proprietario
è sotto target/. Non sostituire Stop con cancellazioni generiche.

Ogni sessione usa MySQL 8.4.3 nuovo con tmpfs, nessun volume persistente,
porta DB dinamica solo loopback, credenziali e HMAC effimeri. Il runner verifica
ID/label/storage/UUID prima di V1 e delle operazioni sulle fixture.
Flyway applica esplicitamente V1; Hibernate usa validate. Il seed dei tre
prodotti demo è ammesso esclusivamente nel bootstrap isolato del runner.
Account, carrelli e ordini sono effimeri e si ricreano ad ogni Start.
I cookie non Secure del runner HTTP sono limitati a questo ambiente loopback;
il percorso release conserva cookie Secure e TLS/proxy qualificati.

## Matrice degli avvii prevista

- default, dev o profili arbitrari: rifiutati prima di datasource/JPA,
  anche con URL esplicito. Nessun fallback MySQL/H2 persistente.
- checkout-release: unico avvio persistente supportato; configurazione protetta,
  schema già migrato/Verify, Hibernate validate e tutte le guardie release.
- checkout-browser: ammesso solo dal launcher con bootstrap dedicato e datasource
  proprietario verificato. Il solo nome del profilo nel JAR non abilita lo sviluppo.
- test: ammesso soltanto col classpath di test del progetto. H2 in memoria
  oppure datasource MySQL Testcontainers verificato; nessun URL persistente
  arbitrario. Il JAR normale non contiene risorse/classi che abilitino questo modo.

Le verifiche si eseguono prima del contesto e nuovamente dopo la configurazione
dei bean, prima di JPA, così gli override tardivi non cambiano destinazione o
politica DDL. I provider/URL alternativi JPA/pool/JNDI sono rifiutati.
Hibernate create-drop resta confinato ai test H2 in memoria preesistenti;
tutti gli avvii applicativi e tutti i MySQL usano validate.

## Database persistenti e attivazione

Per un database nuovo autorizzato usare CHECKOUT_V2_RELEASE_GUIDE.md:
Preflight → approvazione → Migrate → grant DBA → Verify → avvio checkout-release.
Il JAR non migra automaticamente e non crea/aggiorna schemi tramite Hibernate.
Catalogo reale e accessi richiedono una procedura separata approvata.
Sviluppo isolato e test non devono ricevere credenziali persistenti.

Nessuna cancellazione dello storico è inclusa. Nessun merge/deploy/commit
autorizzato implicitamente. Checkout V2 crea ordini, non pagamenti reali.
