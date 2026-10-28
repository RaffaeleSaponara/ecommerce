# Sviluppo locale 3DL

3DL usa Spring Boot, MySQL e Checkout V2. Non gestisce pagamenti reali.
Non servono launcher, approvazioni NTFS, UUID server o account separati di migrazione.

## Docker locale già predisposto

Dal progetto:

```powershell
docker compose up -d mysql
docker compose stop mysql
```

Container **3dl-mysql-dev**, MySQL 8.4.3 su **127.0.0.1:3307**, database
**3dl_checkout_dev**, account **3dl_dev**. Il volume **3dl_mysql_dev_data**
conserva i dati tra arresti e riavvii. Stop non elimina il volume.

Le credenziali casuali e la chiave HMAC sono nel file locale **.env**, escluso da
Git; .env.example contiene soltanto nomi di variabili senza segreti.
Non rigenerare le credenziali di un volume già inizializzato: modificare .env
non cambia automaticamente gli account MySQL esistenti.

In IntelliJ selezionare **3DL dev (Docker)** e premere Run. La configurazione
locale .idea/runConfigurations/3DL_Docker_dev.xml è già pronta ed esclusa da Git.
Se non compare subito, ricaricare il progetto. L'applicazione è raggiungibile su
http://127.0.0.1:9085. Al primo avvio Flyway crea V1 e vengono inseriti tre prodotti
demo. Registrazione, login e checkout si usano normalmente dal browser.

Su una nuova copia del progetto creare .env da .env.example, valorizzare le tre
chiavi segrete e usare DEV_DB_PORT=3307 nella configurazione applicativa.
Il profilo dev carica automaticamente .env dalla working directory del progetto.
Basta avviare EcommerceApplication con profilo dev. Le variabili d'ambiente esplicite hanno precedenza.

## Alternativa: MySQL installato sul computer

Servono JDK 17+ (verificato con JDK 21) e MySQL 8.4.3 locale.
Il database dedicato è **3dl_checkout_dev**, distinto dal vecchio ecommerce.
Prima di crearlo verificare manualmente che il nome sia libero: non riutilizzare,
svuotare o cancellare un database esistente senza verificarlo e autorizzarlo.

Sul proprio MySQL, dopo tale verifica, creare il database vuoto e un account
limitato a quel database. Esempio da eseguire manualmente con un amministratore,
sostituendo il segnaposto password:

```sql
CREATE DATABASE `3dl_checkout_dev` CHARACTER SET utf8mb4;
CREATE USER '3dl_dev'@'localhost' IDENTIFIED BY '<scegli-una-password-locale>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
    ON `3dl_checkout_dev`.* TO '3dl_dev'@'localhost';
```

Questi comandi non sono eseguiti dall'applicazione. Se database o account esistono
già, fermarsi e verificarli; non aggiungere IF NOT EXISTS per nascondere il caso.
L'applicazione rifiuta l'account root. Non usare un account con grant globali.

Generare una chiave HMAC locale di almeno 32 byte; con PowerShell (anche Windows PowerShell):

```powershell
$keyBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($keyBytes); [Convert]::ToBase64String($keyBytes) } finally { $rng.Dispose() }
```

Conservare il valore nelle variabili d'ambiente della configurazione locale,
mai nei sorgenti o nei file versionati. Cambiarlo invalida i preventivi precedenti.

## IntelliJ

1. Ricaricare il progetto Maven e selezionare JDK 21.
2. Creare una configurazione Spring Boot/Application per
   `com.TreDL.ecommerce.EcommerceApplication`, working directory del progetto.
3. Impostare profilo attivo `dev` (oppure program argument
   `--spring.profiles.active=dev`).
4. Impostare le variabili d'ambiente:
   - `DEV_DB_PASSWORD`: password dell'account dedicato;
   - `CHECKOUT_QUOTE_SECRET_BASE64`: chiave generata;
   - facoltative: `DEV_DB_USERNAME` (default `3dl_dev`),
     `DEV_DB_PORT` (default `3306`), `DEV_DEMO_DATA=true` per tre prodotti demo.
5. Avviare e aprire **http://127.0.0.1:9085**.

Usare una configurazione dell'applicazione, non il runner JUnit:
il normale avvio usa solo classi e risorse applicative.
Fermare l'applicazione con il pulsante Stop di IntelliJ.

Da terminale, con le stesse variabili già impostate:

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

Oppure, dopo il package:

```powershell
java -jar target/ecommerce-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

## Schema e protezioni essenziali

- Nessun datasource implicito nel profilo default. Solo `dev` consente l'avvio locale.
- L'URL dev è limitato a localhost/127.0.0.1 e al nome esatto `3dl_checkout_dev`.
  Override di connessione JPA, pool, JNDI o Flyway separati vengono rifiutati.
- Flyway applica V1 nel database vuoto; agli avvii successivi valida la history.
  Un database non vuoto senza history viene rifiutato: nessun baseline automatico.
- Flyway non crea il database; clean è disabilitato. Non usare clean, repair o
  eliminazioni di history per superare un errore: verificare la causa.
- Hibernate esegue soltanto `validate`, senza create/update/create-drop.
- V1 non è modificata. Non è prevista migrazione dei dati legacy.
- Demo opzionale: solo con profilo dev e DEV_DEMO_DATA=true, soltanto se il catalogo
  è vuoto; non sostituisce prodotti esistenti.
- HTTP locale su 127.0.0.1:9085, cookie HttpOnly e SameSite=Lax; Secure=false
  esclusivamente in dev per il browser HTTP locale. Sessioni e CSRF restano attivi.
- Il segreto HMAC è obbligatorio; prezzi, snapshot, transazioni e idempotenza
  conservano il contratto Checkout V2.

Flyway 10.20.1 segnala che MySQL 8.4 supera la versione ufficialmente testata.
Le verifiche del progetto su MySQL 8.4.3 sono mantenute; dipendenze non aggiornate.

Il vecchio profilo checkout-release e i runner checkout-browser/checkout-release
sono rimossi. I report delle fasi precedenti sono documenti storici: per l'avvio
attuale seguire questo documento e TESTING.md. Non è una configurazione di deploy.
