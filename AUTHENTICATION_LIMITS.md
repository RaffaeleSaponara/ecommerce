# Limiti autenticazione — singola istanza

- Login: al quinto fallimento per email normalizzata in 15 minuti, blocco di
  15 minuti. Per IP: 30 fallimenti in 15 minuti, blocco di 15 minuti.
- Le richieste in corso prenotano posti nelle soglie; il conteggio e il rilascio
  sono atomici. Nessun lock viene mantenuto durante BCrypt o accessi al DB.
  Il lookup utente standard collega il contatore all'email realmente salvata
  prima di BCrypt: anche eventuali alias equivalenti per la collation del DB
  condividono il limite, senza query aggiuntive. Il contesto del tentativo è
  locale al thread e viene sempre rimosso al termine della richiesta.
- Successo: azzera i fallimenti dell'account non bloccato, non quelli dell'IP.
  Le richieste respinte non prolungano il blocco. Le risposte limitate rimandano
  al form con messaggio generico, senza indicare quale limite sia intervenuto.
- Registrazione: 10 richieste POST con CSRF valido per IP in una finestra mobile
  di 15 minuti, contando anche richieste non valide; contatore separato dal login.
- IP: `getRemoteAddr()`, nessuna lettura di X-Forwarded-For. Non configurare
  forwarded-header/proxy senza una catena di proxy attendibili. Dietro NAT utenti
  diversi condividono la soglia IP. Il limite non elimina attacchi distribuiti
  mirati a bloccare un account.
- Memoria: massimo 10.000 chiavi account (hash), 5.000 IP login e 5.000 IP signup.
  Pulizia opportunistica al massimo ogni minuto e alla saturazione; nessun record
  scaduto necessita un nuovo tentativo sullo stesso account. A capacità esaurita
  si respingono temporaneamente nuove chiavi, senza espellere contatori attivi.
- I contatori si azzerano al riavvio e non sono condivisi tra istanze. Nessuna
  persistenza, migrazione o infrastruttura aggiuntiva. Solo POST /login autentica:
  HTTP Basic, JWT e gli endpoint legacy /auth/login non sono abilitati.
