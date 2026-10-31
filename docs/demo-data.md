# Carga inicial de demonstração

A migration `db/demo/V2__seed_demo_activation_requests.sql` insere **100 solicitações**. V1 permanece intacta, preservando o checksum de bancos existentes. Flyway aplica V2 apenas uma vez: reiniciar não duplica nem restaura registros já processados.

O perfil `demo` acrescenta a localização de seeds às migrations normais. O Compose ativa esse perfil por padrão. Em banco local existente, execute `docker compose up --build -d` para aplicar V2. São 100 novos registros, não um limite de 100 no banco. O prefixo `SEED-` e os UUIDs da carga são reservados; colisões preexistentes interrompem a migration, sem ocultar inconsistências.

No host: `./gradlew bootRun --args='--spring.profiles.active=demo'` (Windows: `./gradlew.bat`).

| externalId | Quantidade | Estado inicial | Cenário |
| --- | --- | --- | --- |
| SEED-001 a SEED-020 | 20 | PENDING | Produtos válidos, 400, 500, 429, 503 e timeout |
| SEED-021 a SEED-060 | 40 | COMPLETED | Sucesso direto e após retry; 1 a 3 tentativas |
| SEED-061 a SEED-080 | 20 | FAILED | 400 permanente; esgotamento por 500, 429, 503 ou timeout |
| SEED-081 a SEED-085 | 5 | PROCESSING | Lease vencido: retomada após queda |
| SEED-086 a SEED-090 | 5 | PROCESSING | Retry por 429 agendado para 10 minutos após a carga |
| SEED-091 a SEED-095 | 5 | PROCESSING | Lease vigente por 30 segundos |
| SEED-096 a SEED-100 | 5 | PROCESSING | Lease vencido e 5 tentativas: falha sem reenvio |

Quatro consumidores (`demo`, `sistema-a`, `sistema-b`, `sistema-c`) possuem **25 registros cada**. Há dez customerIds (`CUST-001` a `CUST-010`) e dez produtos. Consumidores intercalados permitem testar isolamento e filtros. Datas são relativas à execução da migration, com criação distribuída pelos 100 dias anteriores: leases e retries continuam úteis em qualquer data.

O UUID de `SEED-001` é `10000000-0000-4000-8000-000000000001`; os demais seguem até `...000100`. O correlationId segue `seed-001` etc.

**O worker altera a distribuição imediatamente.** Para inspecionar o snapshot, antes de subir no PowerShell:

```powershell
$env:WORKER_ENABLED = 'false'
docker compose up --build -d
```

Para iniciar o processamento depois:

```powershell
$env:WORKER_ENABLED = 'true'
docker compose up -d app
```

Retries futuros aguardam seu horário. O processamento é gradual, limitado a uma chamada por instância. Falhas históricas inseridas não representam chamadas realmente realizadas ao mock; métricas de transições contam apenas processamento real.

## Roteiro de exploração

1. Gere token com `consumerId: demo` em `/dev/token`, conforme README. Liste com `size=10`: inicialmente há 25 registros visíveis, em três páginas. Combine filtros de estado e customerId.
2. Consulte SEED-001 pelo UUID com esse token: 200. Com token de `sistema-a`: 404. A listagem também deve isolar os registros.
3. Reenvie `{"externalId":"SEED-001","customerId":"CUST-001","productCode":"INTERNET-500"}` como `demo`: 200 com o recurso existente. Mude produto ou consumidor: 409. Replay não cria novo trabalho.
4. Ligue o worker. Produtos válidos concluem; ERROR-400 falha sem retry; ERROR-500/429/503 reagendam; TIMEOUT recupera usando a mesma chave de idempotência. Consulte attempts/failureCode, logs e métricas.
5. SEED-081 a 085 são retomados; SEED-096 a 100 falham com ATTEMPTS_EXHAUSTED sem nova chamada HTTP. SEED-086 a 090 não devem ser selecionados antes do horário previsto.

Dados inválidos e externalIds duplicados não são inseridos: esses cenários são verificados pela API e testes concorrentes, preservando a integridade do banco. 401/403 são testados sem token ou com escopo insuficiente.

## Isolamento e reinicialização

Sem perfil `demo`, somente migrations de schema são aplicadas. Para um **banco novo** sem seed via Compose, em shell POSIX: `SPRING_PROFILES_ACTIVE='' docker compose up --build -d`. Com essa opção, executo meu smoke test manual sem misturar a fila de testes com a carga de demonstração. A CI não sobe Compose. Os testes comuns continuam sem seed; `DemoMigrationTest` verifica migrations, contagens, consistência e não repetição em H2 próprio.

Mantenha o perfil demo em bancos que receberam V2: retirar sua localização deixa uma migration aplicada ausente da configuração Flyway. Produção deve usar banco separado, sem esse perfil. Desabilitar o perfil não remove dados já inseridos.

Para recriar exatamente o snapshot, use um banco de demonstração novo. `docker compose down -v` apaga **todos os dados locais** do volume; a próxima subida recria schema e seed. Não alterar migrations aplicadas nem apagar manualmente o histórico Flyway.
