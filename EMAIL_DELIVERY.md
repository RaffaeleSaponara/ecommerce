# Email degli ordini

V15 aggiunge `email_notifications` e lo snapshot contrattuale sugli ordini.
Conferma, annullamento e spedizione accodano una sola notifica per ordine/tipo,
nella transazione applicativa: un rollback elimina anche la notifica.
Non vengono accodati token, credenziali o dati completi delle carte.
Recupero password e verifica email mantengono il flusso dedicato esistente.

Il worker usa un lease persistente di 15 minuti, batch da 20 e retry con backoff
da 1 minuto fino a 6 ore. Recupera lease scaduti dopo crash e non esegue SMTP
dentro transazioni. I timeout SMTP configurati sono molto inferiori al lease.
`MAIL_WORKER_ENABLED=false` ferma il worker; `APP_MAIL_ENABLED=false` impedisce
l'invio, ma conserva le notifiche. Il prossimo avvio può quindi inviare le
notifiche arretrate: utilizzare soltanto destinatari di prova con Mailpit.

`smtp_accepted_at` significa accettato dal server SMTP, non recapitato alla casella.
Un timeout, un crash dopo l'accettazione o la perdita del lease durante un invio
possono causare una seconda email. Non è garantito l'invio esattamente una volta.
Controllare notifiche con `smtp_accepted_at IS NULL`, `last_error`, `attempts`,
`next_attempt_at` e lease; non stampare destinatari o corpi nei log.

Le condizioni sono copiate alla creazione dell'ordine (anche Stripe in attesa),
con versione/data UTC, e conservate nel messaggio come allegato testuale autonomo.
Gli ordini storici restano senza snapshot: nessuna attribuzione retroattiva.
La versione attuale è esclusivamente DRAFT-TEST; conferme con allegato sono
bloccate fuori dai profili dev/test con `CONTRACT_REVIEW_REQUIRED`.
Non esiste un interruttore che renda definitive le bozze: prima del lancio servono
documenti validati, versione definitiva e una revisione esplicita di questo blocco.
I messaggi già accodati non vengono riscritti al variare dei documenti.
