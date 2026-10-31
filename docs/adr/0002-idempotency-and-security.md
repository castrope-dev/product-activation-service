# ADR 0002 — Unicidade global e isolamento por consumidor

Status: aceito.

`externalId` é globalmente único, como pede o enunciado. O índice UNIQUE é a autoridade, inclusive entre instâncias. A leitura anterior à inserção é somente uma otimização. Em colisão, a transação de inserção é desfeita e uma nova leitura identifica o vencedor.

Mesmo consumidor + mesmo customerId/productCode retornam 200 e o registro atual. Conteúdo diferente ou outro consumidor retornam 409 sem revelar dados do registro. Uma criação inédita retorna 201 e Location. Não se normalizam letras nem espaços: o contrato aceita somente `[A-Za-z0-9._:-]+`, e identificadores são case-sensitive.

O JWT deve ter `sub` estável e exclusivo por consumidor, emitido pelo único issuer configurado. Todo GET, incluindo listagem, filtra esse proprietário; acessar UUID de outro consumidor retorna 404. Na integração com um IdP cujo `sub` represente usuário e não cliente, adaptar explicitamente para um claim de cliente validado, sem aceitar identidade enviada no corpo.

Spring Security Resource Server valida assinatura RS256 por JWKS, issuer, audience, exp, nbf quando presente e subject. Os escopos `requests.write` e `requests.read` são independentes. O fluxo de produção é OAuth 2.0 Client Credentials; o mock local emite tokens sem autenticação apenas para demonstração.

Há limite real de corpo, inclusive sem Content-Length, e quota por IP por instância. Não se confia em X-Forwarded-For. Em produção, ingress confiável deve fazer quotas distribuídas por cliente autenticado, limites de conexão/leitura e proteção contra slow uploads. A quota local tem mapa limitado e não substitui gateway/WAF.
