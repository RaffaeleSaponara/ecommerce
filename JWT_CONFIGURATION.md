# Autenticazione: sessioni Spring Security

Il frontend Thymeleaf usa `POST /login` (parametri `username`, contenente
l'email, e `password`) e il cookie di sessione `JSESSIONID`. Non richiede JWT.
Il filtro `JwtAuthenticationFilter` non era registrato nella baseline `jwt fix`.

L'applicazione non emette né accetta JWT per autenticare richieste e non richiede
`JWT_SECRET_BASE64`. Gli endpoint legacy `/auth/login` e `/auth/logout` sono stati
rimossi: usare `/login` e `POST /logout`. `GET /auth/check` continua a restituire
un booleano ricavato dall'autenticazione Spring Security.

`JwtUtil`, `JwtAuthenticationFilter`, i test JWT e le dipendenze JJWT restano
nel repository per conservare i test di regressione del fix precedente.
`JwtUtil` non è un componente Spring. Non registrare il filtro o il generatore
senza una nuova decisione sulla strategia di autenticazione.
I test JWT costruiscono esplicitamente la utility con una chiave temporanea.
La chiave storica presente nella cronologia Git resta da considerare esposta
e non deve essere riutilizzata.

CSRF è attivo anche su login, registrazione e logout. I form Thymeleaf con
`th:action` includono il campo nascosto; le Fetch mutative leggono il token
e il nome dell'header dai meta tag della pagina corrente. Ricaricare la pagina
dopo login/logout, che ruotano lo stato di sicurezza. Il checkout richiede POST.

I cookie di sessione hanno `HttpOnly` e `SameSite=Lax`. Prima della pubblicazione
configurare HTTPS e `server.servlet.session.cookie.secure=true` nell'ambiente
di produzione. Più istanze richiederanno una strategia per condividere le sessioni
o instradare ogni sessione alla stessa istanza.

Per dettagli, test e limiti vedere [SECURITY_REVIEW.md](SECURITY_REVIEW.md).
