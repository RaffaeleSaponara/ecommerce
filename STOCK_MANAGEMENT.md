# Magazzino prodotti

Feature `codex/product-stock`, completata con un solo agente nel worktree dedicato. Nessun database persistente modificato.

- V4 aggiunge `products.stock INT NOT NULL DEFAULT 0` e il vincolo `stock >= 0`; V1/V2/V3 non vengono modificate.
- Scelta iniziale sicura: tutti i prodotti esistenti, nuovi e demo partono da zero. Non sono quantità stimate: l'amministratore deve rilevare e impostare le giacenze prima delle vendite.
- In `/admin` la tabella mostra le scorte; il modulo consente interi da 0 a 2147483647. Zero indica esaurito. Prima di impostare una giacenza assoluta, aggiorna i dati e riapri il prodotto. Il salvataggio di campi diversi non reinvia una quantità rimasta invariata nel modulo, evitando di ripristinare scorte obsolete dopo un acquisto.
- Catalogo, dettaglio e carrello mostrano disponibilità e limiti. Il carrello non riserva né consuma scorte; altri ordini possono ridurre la disponibilità prima della conferma.
- L'aggiunta al carrello legge scorte aggiornate in READ_COMMITTED e serializza le modifiche sul cliente. Non trattiene lock dell'inventario, così carrelli indipendenti non si bloccano a vicenda.
- Il checkout acquisisce direttamente lock PESSIMISTIC_WRITE sui prodotti in ordine crescente di ID, rilegge e controlla tutte le quantità aggregate. Riduzione, ordine, righe e svuotamento carrello sono nella stessa transazione: ogni errore annulla tutto.
- Il replay idempotente precede l'accesso alle scorte e non le riduce una seconda volta, anche se il prodotto è ormai esaurito. Firma dei preventivi, prezzi, indirizzi, sessioni e CSRF restano attivi.

## Prova manuale

1. Apri questo worktree in IntelliJ con JDK17+ e le variabili `dev` già descritte in `CHECKOUT_V2_DEVELOPMENT.md` (il worktree non copia `.env`).
2. Prima dell'avvio sul database persistente autorizza V4 e prepara un backup: Flyway la applicherà automaticamente. L'agente non ha avviato `dev`.
3. Accedi come amministratore, apri `/admin` e imposta per un prodotto una giacenza verificata, ad esempio 2 per una prova controllata. Prova valori negativi e frazionari: devono essere rifiutati.
4. In `/prodotti` e nel dettaglio controlla la disponibilità; nel carrello non superare il limite. Conferma un ordine e verifica la nuova giacenza admin.
5. Imposta zero e verifica stato Esaurito e pulsanti disabilitati. Per l'ultima unità usa due account/carrelli separati: soltanto un checkout deve riuscire.

Test dedicati: `StockCheckoutTests`, `StockAdminHttpTests`, `StockMigrationTests`; su Docker/MySQL temporaneo `StockCheckoutMySqlTests` e regressioni `CheckoutMySqlIntegrationTests`, `CheckoutV2MySqlSchemaTests`. Frontend: `node --test src/test/js/*.test.cjs`. Nessuna nuova infrastruttura, prenotazione temporanea, variante o integrazione di pagamento.

Verifica completata in 3 cicli, con riesecuzioni mirate: 156 casi H2/regressione più 7 di rendering/dettaglio, 62 casi su MySQL8.4.3 e 63 casi JavaScript distinti superati. Corretta la contesa superflua nel carrello e l'aspettativa JDBC del test del vincolo MySQL3819. Nessun test o controllo di sicurezza disabilitato; verifica visuale manuale da browser non eseguita.
