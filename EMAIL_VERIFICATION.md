# Verifica email

V9 marca verificati tutti gli account già presenti, inclusi gli admin, senza
cambiare email, password o ruoli. Il default SQL per futuri inserimenti è false;
RegistrationService imposta esplicitamente false, come il default del modello
Java. Le fixture che rappresentano account storici indicano true esplicitamente.

Il token è casuale da 256 bit, hash SHA-256 nel DB, valido 24 ore e monouso.
Una riga per cliente, aggiornata sotto lock; il reinvio sostituisce il link.
GET apre la conferma, POST con CSRF effettua la verifica senza autenticare.
Le pagine non caricano risorse esterne e riusano no-referrer, no-store e CSP.
SMTP riusa app.mail.* e spring.mail.*, fuori dalla transazione. Se l'invio fallisce
l'account resta non verificato e può richiedere un reinvio, senza bypass del login.

Registrazione completata e conflitto email danno lo stesso redirect/messaggio
generico, per non enumerare indirizzi. Reinvio identico anche per utenti assenti,
già verificati, limiti o errori SMTP. Il login mantiene il suo messaggio generico.
Il recupero password non verifica l'email: un account non verificato resta bloccato.

Limiti dedicati: 3 invii/email e 10 richieste/IP ogni 15 minuti, 30 conferme/IP.
Stessa implementazione bounded del recupero, istanze separate; IP reale della
richiesta, nessuna fiducia automatica negli header. Memoria locale, reset al riavvio.
La latenza SMTP sincrona non garantisce indistinguibilità temporale; nessuna coda
persistente o retry automatico. Un crash dopo la registrazione richiede reinvio.

Manuale: docker compose --profile dev-mail up -d mailpit; avvia dev, registra un
nuovo utente, apri il messaggio su http://127.0.0.1:8025 e conferma con il pulsante.
Prima della conferma il login fallisce; dopo riesce. Admin e utenti storici restano
accessibili. Nessuna migrazione applicata a DB persistenti durante i test.
