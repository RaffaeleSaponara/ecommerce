# Configurazione JWT

Prima di avviare l'applicazione, impostare `JWT_SECRET_BASE64` con una chiave
casuale di almeno 32 byte codificata in Base64. La stessa chiave deve essere
disponibile a tutte le istanze dell'applicazione e mantenuta tra i riavvii.
L'avvio fallisce se la configurazione manca, non è Base64 o è troppo corta.

Esempio PowerShell, per generare una nuova chiave nella sessione corrente senza
stamparla o scriverla su disco (richiede .NET moderno, ad esempio PowerShell 7):

```powershell
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
mvn spring-boot:run
```

In produzione conservare la chiave nel gestore dei segreti dell'ambiente e
iniettarla come variabile d'ambiente. Non salvarla in file versionati, log o
comandi contenenti il valore letterale. Il profilo `dev` richiede la stessa
configurazione. I test generano autonomamente una chiave temporanea.

La chiave precedentemente incorporata nel codice va considerata esposta perché
è presente nella cronologia Git. Generare e distribuire una nuova chiave senza
riutilizzarla. La rotazione invalida i token precedenti: gli utenti dovranno
effettuare nuovamente il login. I cookie non validi vengono ignorati dal filtro.
