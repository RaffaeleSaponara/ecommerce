# Contenuti legali e schede prodotto — solo bozze

I dati comuni sono in `SellerInfo`, configurabili tramite `app.seller.name`,
`address`, `email`, `privacy-contact`, `returns-address`. I valori assenti
rimangono segnaposto. Nessun dato è stato dedotto dal marchio o da WhatsApp.
Le tariffe delle pagine e dei nuovi snapshot provengono da ShippingService.
Ogni revisione contrattuale richiede una nuova versione DRAFT-TEST; gli ordini
precedenti e le email già accodate mantengono la copia originale.

V16 aggiunge schede prodotto strutturate e dichiarazioni di recesso.
L'admin gestisce le schede da /admin indicando l'ID del prodotto. Campi assenti
o segnaposto sono segnalati; solo una scheda completa e confermata è mostrata
al pubblico. La conferma editoriale non è una certificazione di sicurezza.
Servono dati reali su fabbricante, materiali, misure, identificazione,
avvertenze e istruzioni basate sui prodotti effettivi. Nessuna deroga al
recesso è dedotta dalla sola produzione artigianale.

Il D.Lgs. 209/2025 introduce l'art. 54-bis del Codice del consumo: funzione
online, conferma esplicita e ricevuta durevole con contenuto/data/ora.
L'art. 4 prevede l'applicazione dal 19 giugno 2026 ai contratti successivi.
Fonti ufficiali:
- https://www.gazzettaufficiale.it/atto/serie_generale/caricaArticoloDefault/originario?atto.codiceRedazionale=26G00002&atto.dataPubblicazioneGazzetta=2026-01-08&atto.tipoProvvedimento=DECRETO+LEGISLATIVO
- https://www.gazzettaufficiale.it/atto/serie_generale/caricaArticolo?art.versione=1&art.idGruppo=0&art.flagTipoArticolo=0&art.codiceRedazionale=26G00002&art.idArticolo=4&art.idSottoArticolo=1&art.idSottoArticolo1=10&art.dataPubblicazioneGazzetta=2026-01-08&art.progressivo=0

/recesso usa l'account già necessario all'acquisto: il cliente conferma nome,
ordine e email dell'account. Registra un recesso dall'intero contratto per ordine,
deduplica i retry e accoda la ricevuta nella stessa transazione. La copia è
scaricabile immediatamente; le email usano il worker V15 e i suoi limiti SMTP.
Le richieste sono consultabili dall'admin in /admin/recessi. Non vengono
annullati ordini, eseguiti rimborsi o reintegrate scorte. Non esiste un rifiuto
automatico basato sulla data dell'ordine: mancano data di consegna e dati
affidabili sulle eventuali eccezioni. La verifica manuale non condiziona
la registrazione e la ricevuta non è un'autorizzazione preventiva al recesso.

Prima del lancio servono contatti reali, tempi di consegna, istruzioni e costi
dei resi, criteri di personalizzazione, caratteristiche/sicurezza dei prodotti,
fornitori privacy, conservazione e momento di conclusione del contratto.
La documentazione rimane BOZZA NON DEFINITIVA/noindex; non è stata abilitata
la pubblicazione definitiva né l'accettazione di credenziali Stripe live.
