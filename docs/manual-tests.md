# Guia de testes da API

## Preparação

Execute na raiz do repositório. Docker com Compose é obrigatório para o ambiente completo.
Os exemplos principais usam **Bash + curl + jq** (Linux, macOS, WSL ou Git Bash). Não cole os blocos Bash no PowerShell; há um exemplo nativo ao final.

Em meus testes, uso um projeto Compose separado para não misturar os dados com minha demonstração:

```bash
export COMPOSE_PROJECT_NAME=activation-tests
export SPRING_PROFILES_ACTIVE=default
docker compose up --build -d --wait --wait-timeout 180
docker compose ps
curl -i http://localhost:8080/actuator/health/readiness
```

Resultado: três serviços saudáveis; readiness HTTP 200 com `status=UP`.
As portas 8080, 8081 e 5432 precisam estar livres. O perfil `default` não insere as 100 solicitações demo.
O emissor `/dev/token` é um mock local sem autenticação, não um servidor OAuth de produção.

## Tokens e identificadores

```bash
API=http://localhost:8080
MOCK=http://localhost:8081
RUN="manual-$(date +%s)-$$"
TOKEN=$(curl -fsS "$MOCK/dev/token" -H 'Content-Type: application/json' \
  -d '{"consumerId":"manual-a"}' | jq -er .access_token)
TOKEN_B=$(curl -fsS "$MOCK/dev/token" -H 'Content-Type: application/json' \
  -d '{"consumerId":"manual-b"}' | jq -er .access_token)
READ_TOKEN=$(curl -fsS "$MOCK/dev/token" -H 'Content-Type: application/json' \
  -d '{"consumerId":"manual-a","scope":"requests.read"}' | jq -er .access_token)
WRITE_TOKEN=$(curl -fsS "$MOCK/dev/token" -H 'Content-Type: application/json' \
  -d '{"consumerId":"manual-a","scope":"requests.write"}' | jq -er .access_token)
PAYLOAD=$(jq -nc --arg id "$RUN-ok" \
  '{externalId:$id,customerId:"CUST-MANUAL",productCode:"INTERNET-500"}')
```

Não imprima os tokens nem os versione. Expiram em uma hora. Após reiniciar o mock, gere novos tokens e reinicie a aplicação para renovar seu cache de chaves.

## Criação, consulta e sucesso

```bash
curl -sS -D /tmp/activation-headers -o /tmp/activation-created.json \
  "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN" \
  -H "X-Correlation-ID: $RUN" -H 'Content-Type: application/json' -d "$PAYLOAD"
cat /tmp/activation-headers
cat /tmp/activation-created.json | jq .
ID=$(jq -er .id /tmp/activation-created.json)
curl -i "$API/api/v1/requests/$ID" -H "Authorization: Bearer $TOKEN"
```

POST: **201**, `Location: /api/v1/requests/<uuid>`, estado `PENDING`, datas UTC.
GET: **200**. O estado pode já ter avançado. A API aceita o pedido antes da ativação externa.

Para aguardar um estado terminal com prazo finito:

```bash
await_terminal() {
  local id="$1" result state
  for n in $(seq 1 60); do
    result=$(curl -fsS "$API/api/v1/requests/$id" -H "Authorization: Bearer $TOKEN") || return 1
    state=$(printf '%s' "$result" | jq -r .status)
    if [ "$state" = COMPLETED ] || [ "$state" = FAILED ]; then
      printf '%s\n' "$result" | jq .
      return 0
    fi
    sleep 1
  done
  printf 'Prazo excedido para %s\n' "$id" >&2
  return 1
}
await_terminal "$ID"
```

Esperado: `COMPLETED`, normalmente com `attempts=1`.

## Idempotência e conflito

```bash
curl -i "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$PAYLOAD"
curl -i "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$(printf '%s' "$PAYLOAD" | jq '.productCode="OTHER"')"
curl -i "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN_B" \
  -H 'Content-Type: application/json' -d "$PAYLOAD"
```

Esperados, respectivamente: **200** com o mesmo UUID; **409** por conteúdo diferente; **409** por proprietário diferente. O replay não inicia outro trabalho.

## Isolamento, listagem e paginação

```bash
curl -i "$API/api/v1/requests/$ID" -H "Authorization: Bearer $TOKEN_B"
curl -sS "$API/api/v1/requests?customerId=CUST-MANUAL&page=0&size=10" \
  -H "Authorization: Bearer $TOKEN_B" | jq .
curl -sS "$API/api/v1/requests?status=COMPLETED&customerId=CUST-MANUAL&page=0&size=10" \
  -H "Authorization: Bearer $TOKEN" | jq .
curl -i "$API/api/v1/requests/00000000-0000-0000-0000-000000000000" \
  -H "Authorization: Bearer $TOKEN"
```

Esperados: **404** para o recurso de outro consumidor; lista vazia para `manual-b` em base nova; lista com seus próprios registros para `manual-a`; **404** para UUID inexistente. Ordenação: `createdAt` e `id` decrescentes.

## Autenticação e escopos

```bash
curl -i "$API/api/v1/requests"
curl -i "$API/api/v1/requests" -H 'Authorization: Bearer invalid-token'
curl -i "$API/api/v1/requests" -H "Authorization: Bearer $WRITE_TOKEN"
curl -i "$API/api/v1/requests" -H "Authorization: Bearer $READ_TOKEN" \
  -H 'Content-Type: application/json' -d "$PAYLOAD"
curl -i "$API/actuator/prometheus" -H "Authorization: Bearer $READ_TOKEN"
```

Esperados: **401**, **401**, **403**, **403**, **403**. Respostas de erro usam `application/problem+json`; 401 inclui `WWW-Authenticate: Bearer`.
Tokens com assinatura, emissor, audiência ou expiração inválidos são exercitados por `JwtValidationTest` com JWTs assinados. O mock não permite personalizar essas claims pelo endpoint.

## Validação de entrada

```bash
for body in '{}' \
  '{"externalId":"com espaço","customerId":"C","productCode":"P"}' \
  '{"externalId":"A","externalId":"B","customerId":"C","productCode":"P"}' \
  '{"externalId":"A","customerId":"C","productCode":"P"}{}' \
  '{json-invalido'; do
  curl -i "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$body"
done
curl -i "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc '{externalId:("X" * 101),customerId:"C",productCode:"P"}')"
for query in 'page=-1' 'size=0' 'size=101' 'status=UNKNOWN' 'page=2147483647&size=100'; do
  curl -i "$API/api/v1/requests?$query" -H "Authorization: Bearer $TOKEN"
done
curl -i "$API/api/v1/requests/not-a-uuid" -H "Authorization: Bearer $TOKEN"
```

Todos devem retornar **400**, sem stack trace, SQL ou credenciais. Limites de texto: externalId/customerId 100; productCode 64. O offset `page * size` deve caber em um inteiro de 32 bits.

## Falhas e retries do provedor

```bash
for product in ERROR-400 ERROR-500 ERROR-429 ERROR-503 TIMEOUT; do
  body=$(jq -nc --arg id "$RUN-$product" --arg product "$product" \
    '{externalId:$id,customerId:"CUST-MANUAL",productCode:$product}')
  result=$(curl -fsS "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$body")
  printf '%s\n' "$result" | jq .
  await_terminal "$(printf '%s' "$result" | jq -r .id)" || break
done
```

| Produto do mock | Resultado esperado |
| --- | --- |
| `ERROR-400` | `FAILED`, uma tentativa, `PROVIDER_400` |
| `ERROR-500` | `FAILED`, cinco tentativas, `PROVIDER_500` |
| `ERROR-429` | `FAILED`, cinco tentativas, `PROVIDER_429`; respeita Retry-After de 2s |
| `ERROR-503` | `FAILED`, cinco tentativas, `PROVIDER_503`; respeita Retry-After de 2s |
| `TIMEOUT` | `COMPLETED` após retry; mock efetiva e demora 8s na primeira chamada |

O POST continua sendo **201** nesses cenários. A falha externa ocorre depois, refletida no GET. Durante retry o estado fica `PROCESSING`; uma conclusão pode preservar o failureCode anterior como histórico. O mock deduplica somente em memória.

Se receber 429 da própria API durante muitos testes, espere a próxima janela de minuto e repita com um novo `RUN`. Uma falha de transporte em `curl` não é um status HTTP.

## Concorrência de criação

```bash
RESULTS=$(mktemp -d)
CONCURRENT=$(jq -nc --arg id "$RUN-concurrent" \
  '{externalId:$id,customerId:"CUST-MANUAL",productCode:"INTERNET-500"}')
for n in $(seq 1 12); do
  curl -sS -o "$RESULTS/$n.json" -w '%{http_code}\n' \
    "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$CONCURRENT" > "$RESULTS/$n.status" &
done
wait
cat "$RESULTS"/*.status | sort | uniq -c
jq -r .id "$RESULTS"/*.json | sort -u
```

Esperado: um **201**, onze **200**, um único UUID. Execute antes do teste de limite de taxa. Resultados 429 indicam interferência da quota e não comprovam idempotência.
Concorrência entre workers, recuperação de lease, proteção contra resultado antigo e ausência de transação durante HTTP são verificadas pelos testes automatizados; curls isolados não demonstram essas garantias.

## Corpo grande, chunked e limite de taxa

```bash
jq -nc '{externalId:("X" * 9000),customerId:"C",productCode:"P"}' > /tmp/activation-large.json
curl -i "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' --data-binary @/tmp/activation-large.json
curl --http1.1 -i "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN" \
  -H 'Transfer-Encoding: chunked' -H 'Content-Type: application/json' \
  --data-binary @/tmp/activation-large.json
```

Ambos: **413**. Teste a taxa por último, em ambiente local sem tráfego concorrente:

```bash
for n in $(seq 1 130); do
  curl -sS -o /dev/null -w '%{http_code}\n' "$API/api/v1/requests" \
    -H "Authorization: Bearer $TOKEN"
done | sort | uniq -c
curl -i "$API/api/v1/requests" -H "Authorization: Bearer $TOKEN"
```

Espera-se observar **429** e `Retry-After: 60` ao exceder 120 chamadas/IP/instância dentro da mesma janela de minuto. Se o loop cruzar a virada de minuto, repita. A quota não é global entre réplicas.

## Observabilidade

```bash
curl -i "$API/actuator/health/liveness"
curl -i "$API/actuator/health/readiness"
curl -sS "$API/actuator/prometheus" -H "Authorization: Bearer $TOKEN" \
  | grep -E 'activation_(transitions|provider|requests)'
docker compose logs --since 10m app
```

Busque `request_created`, `request_replayed`, `activation_started`, `provider_call_finished`, `activation_retry_planned` e `activation_processed`. Correlacione por `requestId` ou `correlationId`. Contadores de transição são publicados após commit; gauges de estado são atualizados periodicamente. Não há collector OTLP no Compose básico.

## Testes automatizados

```bash
./gradlew clean build
./gradlew test --tests '*PostgresConcurrencyTest' --rerun-tasks
./gradlew test --tests '*JwtValidationTest' --rerun-tasks
docker compose exec -T provider python - < dev/provider/test_server.py
```

No PowerShell use `.\gradlew.bat`. Java 21 e Docker devem estar disponíveis. Confira que os testes PostgreSQL não foram pulados. Relatórios: `build/reports/tests/test/index.html` e `build/reports/jacoco/test/html/index.html`.
Para os testes Python no PowerShell, use `Get-Content -Raw dev/provider/test_server.py | docker compose exec -T provider python -`.

## Exemplo nativo de PowerShell com curl.exe

Use `curl.exe`, pois `curl` pode ser um alias. Enviar JSON por arquivo evita problemas de aspas no Windows PowerShell 5.1.

```powershell
$env:COMPOSE_PROJECT_NAME = 'activation-tests'
$env:SPRING_PROFILES_ACTIVE = 'default'
docker compose up --build -d --wait --wait-timeout 180
$utf8 = New-Object System.Text.UTF8Encoding($false)
$tokenFile = Join-Path $env:TEMP 'activation-token-request.json'
$bodyFile = Join-Path $env:TEMP 'activation-request.json'
[IO.File]::WriteAllText($tokenFile, '{"consumerId":"manual-powershell"}', $utf8)
$token = (curl.exe -fsS http://localhost:8081/dev/token -H 'Content-Type: application/json' --data-binary "@$tokenFile" | ConvertFrom-Json).access_token
$body = @{ externalId = "ps-$([Guid]::NewGuid())"; customerId = 'CUST-PS'; productCode = 'INTERNET-500' } | ConvertTo-Json -Compress
[IO.File]::WriteAllText($bodyFile, $body, $utf8)
$created = curl.exe -fsS http://localhost:8080/api/v1/requests -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary "@$bodyFile" | ConvertFrom-Json
curl.exe -i "http://localhost:8080/api/v1/requests/$($created.id)" -H "Authorization: Bearer $token"
curl.exe -i http://localhost:8080/api/v1/requests -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary "@$bodyFile"
```

A última chamada retorna 200 por replay. Para falhas externas, crie outro externalId e altere productCode conforme a tabela. Para filtros, use URL entre aspas, especialmente com `&`.

## Encerramento

```bash
docker compose down
```

Isso preserva o volume de testes. Só se desejar apagar os dados do projeto isolado `activation-tests`, execute `docker compose down -v`. Nunca use essa opção em um ambiente com dados que queira manter.
