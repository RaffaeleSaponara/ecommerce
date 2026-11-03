# Webhook e riconciliazione

- Stripe resta disabilitato; il checkout pubblico continua a essere legacy.
- POST /api/stripe/webhook usa firma SDK, corpo originale limitato a 256 KiB,
  STRIPE_WEBHOOK_SECRET e modalità test. Solo quel POST è escluso dal CSRF.
- Un 200 conferma il salvataggio durevole, non il pagamento. Firma errata: 400;
  database indisponibile: 503. Payload e segreti non sono conservati nei log.
- Eventi e lavoro sintetico di riconciliazione risiedono in payment_events.
  Lease di tre minuti, retry crescente da uno a 60 minuti, batch di 20 elementi.
- Letture/chiusure Stripe avvengono fuori transazione. Ordine, tentativo ed evento
  vengono bloccati prima dell'applicazione atomica dell'esito.
- Incassi verificati confermano una sola volta, senza nuovo decremento.
  Una richiesta di annullamento non impedisce di registrare un incasso riuscito.
- Reintegro: sessione expired/unpaid senza PaymentIntent, oppure sessione chiusa
  unpaid con PaymentIntent canceled. Complete/unpaid senza Intent resta incerta.
- Un tentativo locale terminale non avvia cancellazioni sul provider.
- Tipi non pertinenti: 204; per eventi gestiti il 200 attesta persistenza riuscita.
- Anomalie monetarie o terminali non riaprono ordini e non modificano le scorte.
  Richiedono gestione manuale/futura Fase 4; nessun rimborso automatico.
- Le ricevute senza associazione restano recuperabili con anomalia esplicita.
  Il limite di recupero idempotente di 12 ore della Fase 2 resta invariato.
- La consegna email AFTER_COMMIT resta best effort, senza garanzia di ritentativo.
