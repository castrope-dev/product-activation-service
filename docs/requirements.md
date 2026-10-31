# Matriz de atendimento ao enunciado

Comparei o código, os testes e a documentação com o HTML “Desafio Java Pleno/Sênior · Candidato” fornecido para esta análise. Não identifiquei requisito obrigatório ausente na inspeção estática. Diferencio abaixo implementação, descrição permitida pelo enunciado e pendências. Resultados anteriores de execução estão no [relatório](code-review.md).

| Requisito | Atendimento |
| --- | --- |
| Java 17/21, Spring Boot/MVC/Data JPA/SQL | Java 21, Boot 3.5, PostgreSQL; build configurado |
| Records, sealed e pattern matching | DTOs records; resultados do provedor sealed + switch |
| Build com um comando | Gradle Wrapper, `./gradlew clean build` |
| Migrações | Flyway V1; Hibernate validate |
| Compose aplicação/banco/mock | Executado em ambiente Docker isolado |
| CI | GitHub Actions com build/testes, PostgreSQL obrigatório e relatórios JaCoCo; aprovação por ruleset |
| POST, campos e GET por UUID | Implementados e testados |
| 201/Location, limites, UTC e DTOs | Implementados e testados/documentados |
| Listagem, filtros, paginação e índices | Implementados; justificativa no README |
| Transições e proteção concorrente | Métodos de domínio, @Version, locks e fencing por tentativa |
| Unicidade inclusive concorrência | UNIQUE global + recuperação após rollback; teste PostgreSQL presente |
| Semântica de replay/conflito | 200 mesmo conteúdo/proprietário, 409 divergência, 201 vencedor |
| Integração 2xx/400/500/timeout | Adaptador HTTP e testes com servidor real local |
| 429/503/Retry-After | Implementado, teste de 429 e formatos de header |
| Assíncrono, commit antes do HTTP, recuperação de queda | Fila na tabela e lease; ADR 0001 |
| Timeouts, idempotência remota, tentativas/backoff/jitter | Implementados; limitações de exactly-once explícitas |
| Circuit breaker e reconciliação | Lease implementado; circuit breaker e reconciliação remota descritos |
| Erros, integração e inesperados sem detalhes internos | Problem Details; failureCode no processamento assíncrono |
| Segurança OAuth/JWT/scopes/chaves/credenciais | Resource Server implementado; fluxo Client Credentials e rotação documentados |
| 401/403 e isolamento/BOLA | Testes MockMvc; filtros por proprietário |
| Tamanho de corpo e taxa | Filtro com limites; 413/429 testados |
| Testes unitários/integrados/timeout/arquitetura | JUnit, Mockito, H2, servidor HTTP real e ArchUnit |
| Concorrência Testcontainers/banco real | Três testes PostgreSQL presentes; execução obrigatória na CI |
| SOLID/Clean Code/padrões proporcionais | Constructor injection, porta/adaptador do provedor, transições explícitas e camadas |
| Histórico Git | Consolidei o histórico; deixa de demonstrar commits pequenos por etapa |
| Actuator/probes/correlação/sem segredos | Implementados |
| Prometheus/JSON/OpenTelemetry | Implementados; ligação do contexto através da fila e collector descritos |
| Análise de lentidão/causa comum/validação pós-deploy | README, seção de análise |
| Docker multi-stage/non-root/env/heap/health/graceful | Imagem construída; execução e health checks validados no Compose |
| Microsserviços/cloud/eventos/CD | README e ADRs |
| README completo, pendências, melhorias, uso de IA e ADRs | Documentados |

Nenhum teste pulado é apresentado como validado. O mock não simula durabilidade de idempotência nem é um IdP de produção.

## Classificação conferida no enunciado

| Categoria | Itens | Evidência que inspecionei |
| --- | --- | --- |
| Obrigatórios: tecnologias | Java 17/21, Spring Boot, MVC, JPA/Hibernate, build Maven ou Gradle e SQL | build.gradle.kts, application.yml e migrations |
| Obrigatórios: API e domínio | POST validado; GET existente/inexistente; campos exigidos e quatro estados | RequestController, CreateRequest, RequestView, ActivationRequest e RequestApiTest |
| Obrigatório: duplicidade | Unicidade inclusive em concorrência e justificativa | UNIQUE na migration, RequestService, PostgresConcurrencyTest e README |
| Obrigatório: integração | Chamada de ativação; tratamento de 2xx, 400, 500 e timeout | HttpActivationProvider, ActivationWorker, testes correspondentes e tabela no README |
| Obrigatório: erros | Entrada inválida, ausência, duplicidade, integração e inesperados sem detalhes internos | ApiExceptionHandler, ApiProblemFactory e failureCode |
| Obrigatório: segurança | Implementar ou descrever Spring Security/JWT/OAuth | SecurityConfiguration e README com Client Credentials, chaves, escopos e rotação |
| Obrigatórios: testes mínimos | Criação válida/inválida, duplicidade, consulta existente/inexistente, integração com sucesso/erro | RequestApiTest, HttpActivationProviderTest e ActivationWorkerTest |
| Critério de qualidade | Organização, responsabilidades, exceções e manutenção | Camadas, injeção por construtor, regras de domínio e ArchUnit |
| Obrigatórios: operação e entrega | Análise de lentidão, Dockerfile, execução, decisões, produção, pendências e melhorias | README, Dockerfile, fontes e Gradle Wrapper |
| Diferenciais implementados | Records/sealed/switch; Boot 3; comando único; Flyway; Compose; CI; 201/Location; validação documentada; UTC/DTOs; listagem/índices; transições/@Version; replay/conflito | Código, configuração e testes referenciados na matriz acima |
| Diferenciais implementados | Assíncrono durável; HTTP fora da transação; timeouts; chave remota; backoff/jitter/limite; Retry-After; recuperação de lease | Worker, adaptador HTTP e testes PostgreSQL |
| Diferenciais implementados | Problem Details/correlação; Resource Server/escopos; 401/403; isolamento; limites; testes de domínio, timeout, arquitetura e banco real | Filtros, segurança e suítes de teste |
| Diferenciais implementados ou descritos | Probes, métricas, JSON, spans OTLP; Docker multi-stage/non-root/env/heap/health/shutdown; estratégia de testes; hipótese comum e validação pós-deploy; cloud; ADRs; uso de IA | README, ADRs e configuração; continuidade do trace pela fila permanece pendente |
| Diferenciais descritos, permitidos pelo enunciado | Circuit breaker, reconciliação remota, eventos por outbox e CD até deploy | README e ADRs; não os apresento como implementação pronta |
| Diferencial não demonstrado | Histórico de commits pequenos com intenção por etapa | O histórico local contém um commit consolidado |

## Limites da verificação

Localizei JDK 21 e tentei executar o build com JAVA_HOME configurado, mas o download da distribuição Gradle foi bloqueado por restrição de rede. Não executei novamente os testes nem validei Docker/Compose nesta análise. A presença dos testes e os resultados anteriores não certificam uma execução atual. A proteção remota da branch também depende da configuração no GitHub, não apenas do modelo versionado.

## Evoluções que priorizarei

1. Validarei build, testes PostgreSQL e smoke test em clone limpo com Java 21, Docker e acesso às dependências.
2. Integrarei IdP e provedor reais, com deduplicação durável e reconciliação auditada de resultados ambíguos.
3. Automatizarei publicação de imagem, verificação de dependências, deploy gradual e rollback; ensaiarei restauração de backup.
4. Medirei idade da fila, p95/p99 e saturação antes de ampliar concorrência; adicionarei circuit breaker conforme a pressão no provedor.
5. Implementarei outbox quando houver consumidores de eventos, contexto de tracing pela fila e quotas por cliente no gateway.