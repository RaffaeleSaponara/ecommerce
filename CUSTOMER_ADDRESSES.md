# Rubrica e checkout Italia

Feature `codex/customer-addresses`, sviluppata in worktree separato; nessun database persistente modificato.

- `/user`: crea, modifica, elimina indirizzi e scegli il predefinito. Il primo è predefinito; eliminandolo viene promosso il primo rimasto. Ogni account vede soltanto la propria rubrica.
- `/cart`: seleziona spedizione e, se diversa, fatturazione. Puoi salvare un nuovo indirizzo nella sezione dedicata. Paese fisso Italia, CAP di 5 cifre, provincia in sigla di 2 lettere; non viene verificata la corrispondenza geografica CAP/comune/provincia tramite servizi esterni.
- I nuovi ordini richiedono la spedizione; fatturazione omessa equivale alla spedizione. Preventivi, conferma prezzi e calcoli economici restano Checkout V2.
- Ogni ordine salva due snapshot indipendenti dalla rubrica, visibili nei dettagli dello storico. Gli ordini precedenti senza snapshot rimangono leggibili.
- Il retry conserva chiave, token e ID indirizzi originali; gli indirizzi possono essere modificati/eliminati dopo la conferma senza alterare ordine o replay. La stessa chiave con ID diversi è rifiutata. Restano compatibili i retry degli ordini legacy già confermati.
- Flyway V3 aggiunge `customer_addresses` e colonne nullable di snapshot in `orders`. V1 e V2 rimangono invariate; nessun dato esistente viene cancellato.

## Verifica da IntelliJ e browser

1. Apri questo worktree come progetto Maven con JDK17+ (21 già utilizzato nel progetto).
2. Prima di avviare `dev` su un database persistente, autorizza V3 e prepara un backup: Flyway la applicherà automaticamente. L'agente non ha eseguito questo avvio.
3. Usa la configurazione `dev` e le variabili descritte in `CHECKOUT_V2_DEVELOPMENT.md`; il worktree non copia `.env`. Apri `http://127.0.0.1:9085`.
4. Accedi, apri `/user`, salva due indirizzi italiani e cambia il predefinito. Prova CAP non valido e campi obbligatori vuoti.
5. Aggiungi un prodotto al carrello, seleziona spedizione e fatturazione uguale o diversa; prova anche a creare un indirizzo dal carrello. Verifica prezzi, conferma e crea l'ordine.
6. In `/user` apri gli indirizzi dell'ordine; modifica/elimina poi gli indirizzi in rubrica e verifica che lo storico conservi gli originali. Controlla anche un ordine precedente senza indirizzi.
7. Con un secondo account verifica che la rubrica sia separata; richieste con ID dell'altro cliente devono restituire404, senza creare ordini.

I test automatici usano H2 in memoria e MySQL8.4.3 in container temporanei già previsti dal progetto; sessioni/CSRF, prezzi, idempotenza e transazioni restano attivi. Nessun costo di spedizione, pagamento o corriere viene aggiunto.

Verifica eseguita: 279 casi H2/regressione (due aspettative del DTO aggiornate a includere snapshot nullable e rieseguite), 59 casi MySQL, 56 casi JavaScript, senza errori o skip rimasti. Backend: 1 ciclo; frontend: 2 cicli; integrazione: 2 cicli. Nessuna prova visuale manuale in browser eseguita.

Per i test nuovi: `./mvnw -B '-Dtest=AddressBookHttpTests,AddressCheckoutTests,AddressMigrationTests' test`; con Docker attivo, `./mvnw -B '-Dtest=AddressCheckoutMySqlTests,CheckoutMySqlIntegrationTests,CheckoutV2MySqlSchemaTests' test`; frontend `node --test src/test/js/*.test.cjs`. Su Windows usare `.\mvnw.cmd`.
