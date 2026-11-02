# Email ordini

Si riutilizzano app.mail.enabled/from e spring.mail.* del recupero password.
Eventi: creazione confermata, transizione ad ANNULLATO o SPEDITO. I DTO catturano
snapshot persistiti dopo flush, senza dati frontend o letture lazy nel listener.
Replay checkout e aggiornamenti allo stesso stato escono prima dell'evento;
gli aggiornamenti del tracking non pubblicano eventi.

Il listener AFTER_COMMIT sospende il contesto transazionale residuo prima di
renderizzare HTML escapato e testo alternativo e chiamare SMTP. Errori di invio
o rendering non propagano eccezioni alle operazioni commerciali. Non vengono
registrati destinatari, indirizzi, contenuti o dettagli delle eccezioni.

Invio sincrono best effort, con i timeout SMTP esistenti: senza outbox/coda
persistente un crash o SMTP indisponibile possono perdere l'email. Nessun retry
automatico né garanzia exactly-once sul trasporto. Non ritentare il checkout
con nuove chiavi per reinviare email. Nessuna migrazione o storico consegne.

Verifica locale: docker compose --profile dev-mail up -d mailpit; avviare dev,
creare un ordine e leggere http://127.0.0.1:8025. Da admin annullare un altro
ordine oppure inserire tracking prima di passare a SPEDITO. Controllare che
ripetere lo stato o aggiungere tracking dopo non produca un nuovo messaggio.
Non inviare email reali nei test: JavaMailSender è simulato e H2 è in memoria.
