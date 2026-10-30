# Istruzioni per gli agenti — 3DL

Progetto Java 17+, Spring Boot, MySQL e frontend HTML/CSS/JavaScript esistente.

## Orchestrazione e ambito

- Default: Single-Agent Loop, con implementazione, test mirati e correzione.
- Usa Multi-Agent solo se esistono attività indipendenti con un vantaggio concreto dal parallelismo; massimo 2 subagenti, oltre al coordinatore.
- Prima di delegare, definisci contratto condiviso, responsabilità e file distinti. Il coordinatore integra e verifica; evita contesa sulle risorse di test.
- Massimo 3 cicli implementazione/test/correzione per attività (per subagente se delegata). Se restano problemi, riportali insieme ai test eseguiti e ai limiti, senza nasconderli.
- Mantieni le modifiche minime e pertinenti: niente overengineering, refactoring, infrastruttura o framework non richiesti.

## Git

- Mantieni master stabile: usa branch dedicati per nuove funzionalità (prefisso predefinito `codex/`); worktree isolati quando utili.
- Controlla lo stato iniziale; preserva modifiche e stage esistenti e non alterare altri branch o worktree senza richiesta.
- Nessun commit, merge, push o deploy automatico: eseguili solo su richiesta esplicita dell'utente.

## Verifica e sicurezza

- Esegui i test pertinenti alla modifica; verifica le regressioni su carrello, checkout, storico ordini, admin e sicurezza quando coinvolti.
- Segui `TESTING.md`: Maven Wrapper (`.\mvnw.cmd -B '-Dtest=NomeTests' test`, `./mvnw` su Unix) e `node --test src/test/js/*.test.cjs` per il frontend.
- Preferisci test isolati H2 in memoria; usa i test MySQL temporanei esistenti quando necessari. Evita verifiche infrastrutturali pesanti senza motivo.
- Non modificare database persistenti per le verifiche; non disabilitare test, asserzioni, protezioni o controlli di sicurezza per ottenere esiti positivi. Dichiara eventuali verifiche non eseguibili.
- Preserva le regole di `CHECKOUT_V2_DEVELOPMENT.md`, `SECURITY_REVIEW.md` e `JWT_CONFIGURATION.md`: niente segreti versionati, override per aggirare le protezioni o modifiche di schema non necessarie.
