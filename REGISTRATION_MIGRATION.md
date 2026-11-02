# Prima di V7

V7 non riscrive email, password o account. Aggiunge una colonna derivata e un
indice univoco. Le nuove email sono salvate con trim e minuscole; il login
accetta anche email storiche con maiuscole/spazi esterni senza modificarle.

Prima di avviare l'app su un database esistente, eseguire in sola lettura:

```sql
SELECT LOWER(TRIM(email)) AS normalized_email, COUNT(*) AS accounts,
       GROUP_CONCAT(id ORDER BY id) AS account_ids
FROM customers WHERE email IS NOT NULL
GROUP BY LOWER(TRIM(email)) HAVING COUNT(*) > 1;
SELECT id, username, email FROM customers
WHERE email IS NULL OR TRIM(email) = '';
```

Se ci sono duplicati, fermarsi: identificare il titolare di ciascun account e
decidere manualmente email distinte e verificate, mantenendo gli ID e gli ordini.
Non eliminare, fondere o scegliere un account automaticamente. Un errore di V7
non autorizza modifiche ai dati. MySQL non rende atomico tutto il DDL: se V7
fallisce dopo l'aggiunta della colonna, verificare lo schema; un operatore dovrà
rimuovere esclusivamente la colonna derivata incompleta e riparare la history
Flyway solo dopo aver verificato la causa e risolto esplicitamente i duplicati.
Nessuna di queste operazioni viene eseguita dall'applicazione o dai test.

NULL singoli o multipli restano consentiti per compatibilità storica; la nuova
registrazione li rifiuta. I confronti seguono anche la collation MySQL della
colonna (possono essere più restrittivi del solo confronto maiuscole/minuscole).
