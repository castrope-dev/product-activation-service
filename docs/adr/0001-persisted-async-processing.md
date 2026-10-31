# ADR 0001 — Processamento assíncrono persistido

Status: aceito.

O provedor pode levar segundos e ficar indisponível. A API registra PENDING e responde 201 após o commit. A própria tabela funciona como fila durável: não existe intervalo entre gravar a solicitação e publicar um trabalho em memória que poderia se perder.

Workers selecionam uma linha elegível com `FOR UPDATE SKIP LOCKED`, registram PROCESSING, número da tentativa e lease, e fazem commit **antes** do HTTP. A resposta é aplicada em outra transação. `@Version` impede sobrescrita concorrente, e o número da tentativa impede que um worker antigo finalize uma tentativa retomada por outro.

Falhas temporárias permanecem PROCESSING com próximo horário persistido; um lease vencido permite recuperação após queda. O polling faz uma chamada por vez por instância, um bulkhead simples que limita a pressão no provedor. Para maior vazão, medir primeiro; depois configurar um executor limitado ou migrar para broker + transactional outbox.

Entrega é **at-least-once**. A mesma chave de idempotência (UUID da solicitação) acompanha todas as tentativas. Exactly-once depende do contrato de deduplicação do provedor; o serviço sozinho não consegue garantir ausência de efeito remoto duplicado após timeout. FAILED após timeout significa esgotamento local, não prova que o produto não foi ativado. Não se deve criar outra solicitação automaticamente para resolver esse caso; reconciliar pelo identificador original.

O lease padrão de 30s supera os timeouts de conexão/leitura (2s/5s); a configuração é validada na inicialização. Pausas longas de JVM e partições ainda podem causar chamadas sobrepostas, cobertas pela chave estável no provedor. A retenção de idempotência do provedor deve exceder toda a janela de tentativas e reconciliação.

Circuit breaker não foi adicionado: com um worker por instância, timeout e backoff já limitam a carga inicial. Se muitas solicitações acumuladas mantiverem o provedor sob pressão, adicionar Resilience4j com janela de falhas, cooldown e poucas probes em HALF_OPEN. Circuito aberto deve reagendar sem consumir tentativa de HTTP; jamais marcar COMPLETED por fallback.
