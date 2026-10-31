# Revisão de código e segurança

## Escopo e referência do desafio

Revisei controller/DTOs, domínio, idempotência, worker, integração HTTP, segurança JWT, limites de entrada, erros, logs, migrações, testes, Docker e CI. Confrontei a [matriz](requirements.md) com o código e com o HTML do desafio fornecido para esta análise. Distingo a inspeção atual dos resultados de execução registrados anteriormente.

## Problemas corrigidos

| Problema | Efeito anterior | Correção e evidência |
| --- | --- | --- |
| JWT sem audiência | NullPointerException no validador, em vez de rejeição de autenticação | Validação de audiência ausente; teste com JWT assinado |
| Offset de paginação maior que o suportado pelo JPA | Resposta 500 para entrada inválida | Verificação de `page * size` com long; resposta 400; teste de API |
| JSON com documentos adicionais ou chaves repetidas | Entrada ambígua aceita | Jackson com trailing tokens rejeitados e detecção de duplicatas; teste de API |
| Contador de transições antes do commit | Métrica podia aumentar mesmo com falha de commit | Incremento após conclusão da transação; teste simula falha de commit |
| Falha por esgotamento após queda sem contador | Transição para FAILED não era contabilizada | Persistência e contador também nesse caminho, após commit |
| Mock ignorava corpo HTTP chunked | Produtos ERROR e TIMEOUT podiam ser interpretados como ativação normal | Leitura limitada de chunked e Content-Length; testes Python e cenários HTTP completos |
| Limites de admissão configurados com valores inválidos | Comportamento incorreto e possível overflow de `bodyLimit + 1` | Validação da configuração no construtor |
| Mensagem de validação potencialmente nula | Construção de Problem Details poderia falhar | Mensagem padrão quando ausente |

Os testes também verificam que um resultado de worker antigo é ignorado e que o limite de corpo funciona sem Content-Length. Os logs registram criação/replay/conflito, tentativas, duração do provedor, decisões de retry e resultado persistido. Tipos e frames de exceção são registrados sem mensagens que possam conter SQL, payloads ou credenciais.

## Organização

- Entradas em `api.dto.request`; saídas em `api.dto.response`; resultados de aplicação em `application.result`.
- Exceções em `exceptions`; tradução para HTTP em `exceptions.handler.ApiExceptionHandler`.
- Construção compartilhada de Problem Details em `api.error.ApiProblemFactory`, sem dependência dos filtros no handler.
- Tipos auxiliares de leitura do corpo em arquivos próprios; formatação aplicada pelo IntelliJ.
- Comentários explicativos removidos dos fontes Java/Python. Migrações SQL já versionadas e avisos/licenças dos wrappers foram preservados: remover texto de migrações altera seu checksum e pode impedir atualização de bancos existentes.
- Sonar removido por minha decisão; CI exige build e testes PostgreSQL e disponibiliza JaCoCo. Actions fixadas por commit e runner Ubuntu 24.04.

## Dependências e avisos de segurança

Foram consultadas 116 coordenadas Maven do runtime na [OSV](https://osv.dev/) por nome e versão. A consulta inicial encontrou 21 IDs de advisories distintos. Atualizações por propriedades do BOM, mantendo as famílias compatíveis com Boot 3.5, reduziram o resultado a dois IDs no Spring MVC:

| Dependência | Versão usada após revisão |
| --- | --- |
| Jackson BOM | 2.21.7 |
| Tomcat | 10.1.59 |
| PostgreSQL JDBC | 42.7.12 |
| OpenTelemetry | 1.67.0 |
| Log4j API/BOM | 2.25.5 |

O mock foi atualizado para PyJWT 2.15.1 e cryptography 50.0.2. A consulta dessas duas versões na OSV não retornou advisories. Isso não inclui uma varredura das bibliotecas nativas das imagens, de todas as dependências transitivas Python ou de plugins de build.

### Avisos residuais e aplicabilidade

- [GHSA-j9f9-w8pj-32f8](https://github.com/advisories/GHSA-j9f9-w8pj-32f8): renderização de fragmentos em SSE. Não há endpoints SSE nem renderização de fragmentos nesta API.
- [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg): limitação de caminhos em XsltView. O projeto responde JSON/Problem Details e não configura XsltView.
- A revisão adicional dos avisos oficiais encontrou a versão Spring Security 6.5.11 nas faixas de [DPoP replay](https://spring.io/security/cve-2026-41707/), [AES/CBC](https://spring.io/security/cve-2026-47842/) e [WebAuthn](https://spring.io/security/cve-2026-47841/). Esses recursos não são usados. Há também o aviso de [Sort em consultas nativas](https://spring.io/security/cve-2026-47834/) para Spring Data JPA 3.5.13; a consulta nativa deste projeto não recebe Sort/Pageable e a ordenação da listagem é definida internamente.

As condições descritas nesses avisos não foram identificadas no fluxo implementado. As dependências continuam em faixas reportadas como afetadas: a próxima atualização de plataforma deve migrar para uma linha suportada com correções públicas ou usar suporte que disponibilize os patches correspondentes. Esta entrega não realiza uma migração de Spring Boot 3 para Boot 4. Ausência de condições conhecidas não é garantia de ausência de vulnerabilidades.

## Atendimento e diferenciais

Implementados: endpoints, validação, status, datas UTC, DTOs, listagem/paginação, unicidade concorrente, isolamento por proprietário, OAuth Resource Server/JWT, retries limitados com jitter/Retry-After, recuperação de lease, migrações, testes de banco real, observabilidade, Docker e CI.

Documentados como evolução, sem serem apresentados como implementados: circuit breaker, reconciliação remota, outbox de eventos, quota distribuída, continuidade do trace original através da fila e CD com canary. A deduplicação remota durável depende do contrato do provedor. O mock mantém suas chaves somente em memória.

Consolidei o histórico em um commit de entrega; portanto, não demonstro o diferencial de commits pequenos por etapa.

## Validação registrada anteriormente

O build Gradle executou 27 testes Java, incluindo três testes PostgreSQL/Testcontainers, sem falhas ou skips. A imagem Docker foi construída e os três serviços foram iniciados com health checks em um projeto Compose isolado. O [roteiro manual](manual-tests.md) fornece comandos para reproduzir os cenários e seus resultados esperados.

Também passaram quatro testes Python do leitor do mock e os cenários HTTP de criação, replay, conflito, isolamento, autenticação, validação e 12 criações simultâneas com um vencedor. No Compose: ERROR-400 falhou em uma tentativa; ERROR-500/429/503 falharam após cinco; TIMEOUT concluiu na segunda tentativa. Health checks e métricas responderam conforme o contrato. Um corpo chunked maior que 8KiB retornou 413; o teste de taxa observou 429 com Retry-After. A sintaxe dos blocos Bash do guia foi conferida com `bash -n`.

As verificações não substituem teste de carga, pentest, análise completa de dependências ou configuração real de IdP, TLS e secret manager. A regra de proteção no GitHub precisa exigir o nome atual `Build e testes`; o arquivo JSON local é apenas o modelo da regra.
