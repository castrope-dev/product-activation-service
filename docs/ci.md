# CI e aprovação de pull requests

Fluxo: **build/testes → relatórios JaCoCo → revisão humana obrigatória para merge**.

A CI executa em PRs, pushes na main e acionamento manual. O check `Build e testes` exige build e testes aprovados. Os relatórios são publicados mesmo após falha, se tiverem sido gerados. Não há build de imagem, Compose nem smoke test no workflow. Docker continua necessário apenas para os testes PostgreSQL via Testcontainers. Compose e `dev/smoke.py` ficam disponíveis para uso manual.

A CI não exige tokens ou variáveis de serviços externos de análise. PRs de forks também executam build, testes e geração de relatórios.

## Aprovação final e bloqueio do merge

A aprovação humana é uma **regra do repositório**, não uma etapa de deploy nem um job que declara o PR aprovado. O YAML sozinho não bloqueia merges.

Depois de publicar o repositório, abra Settings → Rules → Rulesets e importe `.github/rulesets/main.json` (New ruleset → Import a ruleset). Revise e mantenha enforcement Active. A regra define:

- Branch alvo `main`; exigir PR e **uma aprovação de outro revisor**.
- Invalidar aprovações após novos commits e exigir aprovação do último push por outra pessoa.
- Exigir resolução das conversas.
- Exigir o check **`Build e testes`** e branch atualizada com a base.
- Impedir exclusão/force push, sem bypass de revisão/CI.

Se uma regra remota já exigia o nome antigo do check, atualize-a para **`Build e testes`** ao publicar esta alteração. Alterar o JSON local não atualiza regras já importadas no GitHub.

O JSON é um modelo: estar versionado não ativa a regra. A configuração remota permanece pendente até existir acesso ao repositório GitHub. Com CI e revisão aprovadas, o GitHub libera o merge, que continua sendo uma ação explícita. A revisão pode ocorrer durante a CI; ambos precisam estar aprovados para concluir.

Se eu for o único colaborador, adicionarei outro revisor: não posso aprovar meu próprio PR. A disponibilidade de regras em repositórios privados depende do plano GitHub. Não uso merge queue; caso adotada, adicionar evento `merge_group` ao workflow.

## Relatórios e execução local

O Summary da execução em Actions exibe cobertura por linhas, branches, instruções, métodos etc. Em Artifacts, baixe `verification-reports`, disponível por 14 dias. Dentro do ZIP, abra `reports/jacoco/test/html/index.html` para cobertura, `reports/tests/test/index.html` para testes; os XMLs também estão disponíveis.

`./gradlew clean build` executa o build, os testes e gera os relatórios JaCoCo. Para executar os testes PostgreSQL localmente, mantenha o Docker disponível. Na CI, a execução desses testes é obrigatória: testes ausentes ou pulados fazem o check falhar.

A imagem do runner é ubuntu-24.04. As actions estão fixadas por SHA: checkout v6, setup-java v5, setup-gradle v5 e upload-artifact v6. Ao atualizar, confira o commit na origem oficial e mantenha esta relação atualizada.
