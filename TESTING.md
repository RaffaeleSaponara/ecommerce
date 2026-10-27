# T-B — Testing e riproducibilità 3DL

## Baseline e prerequisiti

Baseline verificata prima delle modifiche:
`06110ed2ef4d3d38b4298014ff8c629e45d4d643`, worktree pulito.

Servono un JDK 17 o successivo e il Maven Wrapper incluso nel repository.
Il wrapper seleziona Maven 3.9.9; Spring Boot 3.4.2 gestisce le versioni
di JUnit Jupiter, Mockito, H2 e Surefire. Non occorre installare Maven.
Al primo avvio occorre poter scaricare Maven e le dipendenze dai repository
Maven; le esecuzioni successive possono usare la cache locale.

Non servono MySQL, Docker, un database esterno o credenziali JWT reali.
Eseguire dalla directory del progetto, senza override esterni delle proprietà
Spring dei test (ad esempio `SPRING_DATASOURCE_URL` o `SPRING_CONFIG_LOCATION`).

## Esecuzione

PowerShell / Windows:

```powershell
.\mvnw.cmd -B clean test
```

Linux / macOS:

```sh
./mvnw -B clean test
```

Solo test del carrello, sia unitari sia di integrazione:

```powershell
.\mvnw.cmd -B '-Dtest=CartServiceTests,CartServiceUnitTests' test
```

Verifica dell'indipendenza dall'ordine con seed ripetibile (PowerShell):

```powershell
.\mvnw.cmd -B test '-Dsurefire.runOrder=random' '-Dsurefire.runOrder.random.seed=31027' '-Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random' '-Djunit.jupiter.execution.order.random.seed=31027'
```

Le virgolette singole preservano il carattere `$` nel nome della classe JUnit.
L'esito atteso è `BUILD SUCCESS`. I report XML e testuali sono generati in
`target/surefire-reports/`. Surefire fallisce se non trova test.

## Isolamento e stato persistito

- `src/test/resources/application.properties` attiva il profilo `test` anche
  nel classpath di test di un IDE. Le classi Spring dichiarano inoltre
  `@ActiveProfiles("test")`.
- `application-test.properties` configura esclusivamente H2 in memoria,
  compatibilità MySQL, schema `create-drop` e un nome UUID diverso per ogni
  contesto Spring. Il nome variabile impedisce condivisioni accidentali del
  database; i risultati attesi non dipendono dal nome o dagli ID generati.
- Lo smoke test avvia l'intero contesto e controlla URL JDBC in memoria e
  prodotto database H2. I segreti JWT dei test sono generati localmente
  da `JwtTestSupport`, senza leggere credenziali di produzione.
- `CartServiceTests` usa lo slice JPA con il vero `CartService`, repository e
  mapping Hibernate. La sostituzione automatica del datasource è disattivata
  per usare esattamente la configurazione H2 del profilo.
- La transazione automatica del test è disattivata: le chiamate al servizio
  completano realmente le proprie transazioni. Ogni verifica del contenuto
  apre una nuova transazione e un nuovo persistence context, rilegge il
  carrello dal repository e materializza i prodotti prima di chiudere.
  Non si verifica soltanto il valore restituito o un'entità rimasta in cache.
- Le fixture vengono eliminate dopo ogni invocazione, comprese quelle dei
  test parametrizzati, nell'ordine carrelli, clienti, prodotti. Il database
  appartiene esclusivamente al contesto di test.
- JUnit usa una nuova istanza per metodo e non esegue i test in parallelo.

## Mockito

I test unitari usano `MockitoExtension`, `@Mock`, `@InjectMocks` e
`STRICT_STUBS`: gli stub inutilizzati o incoerenti fanno fallire il test.
Surefire carica `mockito-core` come Java agent all'avvio della JVM, usando
la versione gestita da Spring Boot e il repository Maven locale.
Non dipende dall'aggancio dinamico degli agent su JDK recenti.

Per l'esecuzione da IDE usare il runner Maven per applicare la stessa
configurazione JVM. Un runner JUnit diretto deve ricevere l'equivalente
opzione `-javaagent:<percorso-assoluto-del-jar-mockito-core>`.

## Copertura

| Classe | Invocazioni | Verifiche principali |
| --- | ---: | --- |
| `CartServiceTests` | 15 | Creazione e riuso del carrello; aggiunta di 1, 2, 5 copie; prodotti diversi; rimozione dell'ultima copia; rimozione di una sola copia da 2, 3, 5; argomento detached; prodotto assente; carrello vuoto; svuotamento ripetuto; cliente senza carrello; riaggiunta dopo svuotamento; isolamento tra clienti e conservazione dei prodotti nel catalogo |
| `CartServiceUnitTests` | 8 | Creazione/riuso; inizializzazione e append della lista; lista nulla nella rimozione; svuotamento con carrello assente o lista nulla; svuotamento di tutte le occorrenze e salvataggio |
| `EcommerceApplicationTests` | 1 | Avvio completo e verifica del datasource H2 in memoria |
| `JwtSecurityTests` | 4 | Test JWT preesistenti, mantenuti nella suite |
| **Totale** | **28** | |

Le quantità sono rappresentate nel modello corrente da occorrenze multiple
dello stesso prodotto; le asserzioni verificano le molteplicità persistite
senza dipendere dall'ordine di una relazione JPA priva di `@OrderColumn`.
Non sono aggiunte asserzioni che dichiarino corretto un difetto applicativo
già identificato.

## Risultati e limiti

Esecuzione del 27 ottobre 2026 su Windows, Temurin JDK 21.0.9,
Maven Wrapper 3.9.9, Surefire 3.5.2 e H2 2.3.232:

- `clean test`: **28 eseguiti, 0 fallimenti, 0 errori, 0 saltati**, `BUILD SUCCESS`.
- Seconda esecuzione con ordine casuale di classi e metodi, seed `31027`:
  **28 eseguiti, 0 fallimenti, 0 errori, 0 saltati**, `BUILD SUCCESS`.
  I log locali delle due esecuzioni riuscite sono in
  `target/qa-clean-test.log` e `target/qa-random-order.log` (artefatti generati,
  non versionati e rimossi da un successivo `clean`).
- La prima prova, precedente alla configurazione del Java agent, si è bloccata
  durante l'aggancio dinamico di Mockito, con errore della pipe di attach
  Windows; è stata interrotta. Il caricamento iniziale dell'agent ha risolto
  il problema nella successiva esecuzione completa.
- Nessun difetto funzionale rilevato negli scenari eseguiti del servizio
  carrello. I test non coprono endpoint HTTP, conversione DTO, checkout,
  concorrenza o compatibilità reale con MySQL. La modalità MySQL di H2
  non equivale a una certificazione su MySQL.
- Il warning Spring su `open-in-view` e quello JVM sulla condivisione CDS
  non causano fallimenti; il test JWT senza segreto produce intenzionalmente
  un warning di inizializzazione del contesto.

Non sono stati modificati sorgenti applicativi, template o configurazioni di
produzione. Nessun commit, merge, rebase o push.
