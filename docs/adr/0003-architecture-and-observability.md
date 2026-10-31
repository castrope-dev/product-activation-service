# ADR 0003 — Camadas simples e observabilidade

Status: aceito.

`api` contém DTOs, validação e a fábrica de Problem Details; `exceptions.handler` traduz exceções para HTTP; `application` contém casos de uso, worker e contrato do provedor; `domain` controla transições; `infrastructure` implementa HTTP, segurança e instrumentação. Constructor injection e a porta ActivationProvider permitem substituir a integração nos testes. Não foram criados repositórios duplicados nem uma interface para cada classe.

A entidade usa anotações JPA para reduzir mapeamento desnecessário, mas não depende de Spring, HTTP nem DTOs. A aplicação depende de Spring Data/TransactionTemplate deliberadamente. Isso é uma arquitetura em camadas com inversão da dependência do provedor, não uma implementação hexagonal estritamente independente de frameworks. ArchUnit protege as dependências escolhidas.

Os resultados do provedor usam sealed interface e switch exaustivo. DTOs usam records. As operações de estado expressam regras, sem setters públicos. O banco tem migrations Flyway e Hibernate apenas valida o schema.

Logs JSON incluem correlationId persistido e propagado no HTTP. Micrometer registra latência de API, contadores/latência do provedor e quantidade de solicitações por estado. O gauge de estado atualiza em intervalos, evitando queries em cada scrape; o valor é global ao banco e não deve ser somado entre réplicas.

Micrometer Tracing com bridge OpenTelemetry exporta spans HTTP por OTLP. A correlação funcional atravessa a fila pelo correlationId; o contexto pai do span HTTP original não é persistido. Em produção com necessidade de um único trace incluindo espera, persistir traceparent/tracestate e criar span de consumidor com link ao produtor, respeitando amostragem. Não colocar customerId, externalId ou UUIDs como tags de métricas: isso causaria alta cardinalidade.
