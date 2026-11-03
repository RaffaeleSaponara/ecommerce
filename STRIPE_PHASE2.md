# Sessioni Stripe — Fase 2

- Servizio interno senza endpoint. Checkout HTTP legacy invariato.
- payments.stripe.enabled=false resta il default. STRIPE_SECRET_KEY è letta
  dall'ambiente; sono ammesse solo chiavi server sk_test_ / rk_test_.
- Il payload Stripe, la versione API SDK e l'impronta della chiave sono congelati
  prima della chiamata HTTP. URL derivati da app.mail.public-url, mai dal client.
- La stessa chiave 3dl-session-UUID è usata da tutte le richieste concorrenti.
- I conflitti temporanei hanno al massimo tre tentativi; gli esiti ambigui
  preservano la richiesta. Senza session ID, dopo 12 ore è obbligatoria la futura
  riconciliazione: nessuna nuova creazione automatica.
- Chiamate esterne fuori transazione; associazione sotto lock ordine/tentativo.
- Stato sessione e stato pagamento sono verificati separatamente. Sessioni chiuse
  non espongono URL di pagamento. Nessuna conferma ordine o liberazione scorte.
- SDK reale non invocato nei test: adattatore simulato, H2 e MySQL temporaneo.
- Fase 3: webhook autenticati, deduplicazione eventi, riconciliazione e gestione
  degli esiti terminali. Nessun recupero automatico dopo rotazione della chiave/API.
