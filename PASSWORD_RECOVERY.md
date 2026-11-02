# Recupero password locale

Avviare soltanto Mailpit: `docker compose --profile dev-mail up -d mailpit`.
Non serve ricreare MySQL o rimuovere volumi. Interfaccia su
http://127.0.0.1:8025, SMTP su 127.0.0.1:1025; porte configurabili in .env.example.
Mailpit cattura i messaggi senza inoltrarli a destinatari reali; lo storage è
temporaneo. Documentazione: https://mailpit.axllent.org/docs/install/docker/.

Avviare l'app con profilo dev, poi Login → Password dimenticata. Aprire il link
dal messaggio in Mailpit. L'URL proviene da APP_PUBLIC_URL, mai da Host/Forwarded.
Token da 256 bit, scadenza 30 minuti, solo SHA-256 nel database, una riga per
account. Una nuova richiesta sostituisce il precedente; due utilizzi concorrenti
sono serializzati sul cliente. GET non consuma il token. Reset e invalidazione
del token sono atomici; nessuna chiamata SMTP avviene nella transazione.

V8 aggiunge soltanto password_reset_tokens. Non applicata ai database persistenti
durante lo sviluppo. Se V7 non è ancora applicata seguire REGISTRATION_MIGRATION.md.

Limiti indipendenti in memoria: richieste 3/email e 10/IP ogni 15 minuti;
conferme reset 30/IP ogni 15 minuti. Contatori limitati a 10.000 email e 5.000 IP
per tipo, con scadenza opportunistica; si azzerano al riavvio, non sono condivisi.
La risposta alla richiesta resta identica per account inesistenti, limiti, invio
disabilitato ed errori SMTP. SMTP è sincrono con timeout: la latenza può variare;
non viene garantita l'indistinguibilità temporale delle richieste.

Al reset non si effettua login. Le sessioni già registrate nella stessa istanza
sono segnate scadute e rifiutate alla successiva richiesta; il riavvio perde già
le sessioni locali. Non c'è revoca distribuita o garanzia su login in volo non
ancora registrati. Il cambio password autenticato revoca anche il token attivo.
Il reset non azzera i blocchi del login. La notifica è best effort: un errore SMTP
non annulla una password già aggiornata. Nessuna coda o retry automatico.

Invio disabilitato fuori dev salvo APP_MAIL_ENABLED=true esplicito. Per un futuro
provider configurare spring.mail.host/port/username/password e SMTP auth/TLS in
configurazione esterna, MAIL_FROM e APP_PUBLIC_URL HTTPS. Mai versionare segreti.
Le pagine di recupero non caricano risorse esterne, hanno no-referrer, no-store e
CSP restrittiva. Non abilitare log SMTP/debug o access log che includano query:
i link contengono necessariamente il token. Non aggiungere analytics alle pagine.
