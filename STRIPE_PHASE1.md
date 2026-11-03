# Dominio pagamenti — Fase 1

- Il percorso HTTP Checkout V2 continua a creare ordini LEGACY. Stripe è disabilitato
  (payments.stripe.enabled=false); nessun endpoint o comando admin simula un incasso.
- StripePaymentDomain è interno al package service e attivato solo esplicitamente
  nei test di dominio. Un futuro adattatore verificato dovrà essere il suo chiamante.
- Un ordine Stripe prenota lo stock alla creazione; confermare non lo decrementa
  nuovamente. La scadenza locale e la richiesta di chiusura non rilasciano unità.
- closeUnpaid richiede una chiusura definitiva verificata dal provider. Non è un
  timer né un comando amministrativo. In Fase 1 manca deliberatamente tale adattatore.
- Conferma e chiusura terminale sono serializzate sull'ordine, poi sul tentativo.
  Il primo esito valido prevale; un successivo esito incompatibile richiede
  riconciliazione (409), senza riaprire l'ordine o cambiare ancora le scorte.
- Gli annullamenti amministrativi Stripe sono bloccati anche dopo un incasso:
  coordinamento dei rimborsi e annullamento pagato appartengono alle fasi successive.
- V11 preserva gli ordini storici senza inventare tentativi o incassi. Un tentativo
  per ordine; eventi e rimborsi hanno solo strutture persistenti, nessun processore.
- Restano da realizzare: adattatore Stripe, associazione dei riferimenti provider,
  webhook autenticati, riconciliazione, rimborsi ed esposizione degli stati nell'UI.
  L'email AFTER_COMMIT conserva il limite attuale di consegna best effort.
