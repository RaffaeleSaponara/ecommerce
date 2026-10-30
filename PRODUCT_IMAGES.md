# Immagini e dettaglio prodotto

Branch `codex/product-images-detail`; nessuna modifica al database dev eseguita durante lo sviluppo.

- Dettaglio pubblico: `/prodotti/{id}`, collegato alle schede dello shop; galleria e aggiunta al carrello con CSRF.
- Admin: `/admin` → seleziona o salva un prodotto → sezione immagini; carica un file per volta, scegli la principale o rimuovi.
- JPEG/PNG, massimo 5 MiB, 4096 pixel per lato, 16 MP e 10 immagini per prodotto. Il server decodifica e riscrive PNG con nomi UUID; non usa i nomi dei file client.
- File persistenti in `${user.home}/.3dl/product-images`, esterni al progetto/JAR. In IntelliJ si può impostare `PRODUCT_IMAGES_DIRECTORY` con un percorso assoluto dedicato. Backup: conservare insieme database e questa directory; `mvn clean` non elimina le immagini.
- Nel database V2 crea solo `product_images`; `products.image` resta il riferimento della principale per compatibilità. Le immagini statiche precedenti restano utilizzabili fino al primo upload; rimuovendo l'ultima caricata compare il segnaposto.

## Verifica manuale da IntelliJ

1. Apri il worktree feature, importa `pom.xml` e usa JDK 17+ (preferibile il 21 già configurato).
2. **Prima di avviare il profilo dev su un database persistente, autorizza l'applicazione di V2 e prepara un backup.** Flyway la applica automaticamente all'avvio; questo avvio non è stato eseguito dall'agente.
3. Configura il profilo `dev` e le variabili già descritte in `CHECKOUT_V2_DEVELOPMENT.md`. Il worktree non copia `.env`: usa variabili d'ambiente o un file locale non versionato. Imposta facoltativamente `PRODUCT_IMAGES_DIRECTORY`.
4. Avvia l'applicazione su `http://127.0.0.1:9085`; accedi con un account ADMIN esistente, apri `/admin` e modifica un prodotto. Salva prima un eventuale prodotto nuovo.
5. Carica due immagini, verifica anteprime, cambia la principale e controlla shop, dettaglio e carrello. Rimuovi la principale e poi l'ultima; verifica promozione automatica e segnaposto. Riavvia per controllare la persistenza.
6. Prova file falsi/non immagine e file troppo grandi; un utente normale non deve poter modificare immagini. Verifica anche ricerca, checkout e storico ordini.

I test automatici usano directory temporanee, H2 in memoria e un container MySQL 8.4.3 con storage temporaneo e porte locali dinamiche, senza usare il database persistente.
