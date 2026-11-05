# Gestor de Cobrança — instruções para o Claude Code

CRM SaaS multi-tenant para assessorias de cobrança. O responsável pelo projeto é o PO e aprova decisões e PRs.
Escopo funcional, decisões e desenho estão em `docs/` — comece por [docs/README.md](docs/README.md).
Responda e documente sempre em **português do Brasil**.

## Regras do projeto (obrigatórias)

- **Stack**: backend Java 21 + Spring Boot (monólito modular com Spring Modulith), frontend Angular + Bootstrap 5.3,
  PostgreSQL 16 (AWS RDS), S3, CloudWatch, EC2 Linux com balanceamento de carga. Outras linguagens ou ferramentas só
  com aprovação do responsável (já aprovados: SQL/Flyway, Terraform, Docker/Compose, Bash, Node no script de auditoria
  da interface).
- **Arquitetura**: camadas bem definidas, alta coesão, baixo acoplamento, packages por módulo
  ([docs/tecnico/convencoes.md](docs/tecnico/convencoes.md)). `ArchitectureTest` e `ModularityTest` precisam passar.
- **Sem workarounds**: sempre a solução adequada. Se a solução certa depender de algo fora do alcance, pare e registre.
- **Segurança e rastreabilidade em primeiro lugar** (dados sensíveis): escopo por credor em todo caso de uso, RLS por
  tenant, auditoria (`AuditEntry`) de toda alteração relevante, log de acesso a dado sensível, minimização de dados
  pessoais (nunca em logs, eventos ou métricas).
- **Logs técnicos separados dos logs de negócio** ([docs/tecnico/logs-e-metricas.md](docs/tecnico/logs-e-metricas.md)):
  técnicos para manutenção e métricas de desempenho; de negócio (`BusinessEventLogger`) para inteligência da operação.
  Evento ou métrica nova → documentar na mesma mudança.
- **Itens que dependem de aprovação** (serviço AWS extra, provedor externo, custo novo) viram **issue no GitHub** do
  projeto, não código.
- **Decisões tomadas sem consulta** entram em [docs/negocio/decisoes-autonomas.md](docs/negocio/decisoes-autonomas.md),
  numeradas em sequência (veja o último número no arquivo; nunca reutilize), com alternativas e motivo. Ao terminar,
  resuma ao responsável as decisões que pedem revisão.
- **Repositório público por ora**: nunca versionar dados reais (de clientes, devedores ou empresas), segredos, chaves
  ou arquivos de exemplo enviados pelo responsável. Fixtures e sandbox usam só dados fictícios.
- **Menus e telas sem permissão ficam ocultos** (nunca exibir e responder "acesso negado").
- **Campos monetários** sempre com máscara (`appMoney`), sem o usuário digitar separadores.

## Fluxo de git

- **Atual**: commits direto na `master`, até o responsável avisar que passa a valer branch + PR.
- **Depois**: branches `feature/*` a partir da `master` e pull request com aprovação do responsável.
- **Todo commit passa antes pelo subagente `revisor-de-codigo`** ([.claude/agents/revisor-de-codigo.md](.claude/agents/revisor-de-codigo.md)),
  inclusive quando o pedido é só "faça o commit" ou o commit não passa pela skill `concluir-tarefa`. Alvo: o que vai
  ser commitado (`git diff --cached`/`git diff HEAD` e arquivos novos). Tratamento do relatório como na etapa 7 da
  skill: Crítica/Alta confirmada no que a mudança alterou se corrige antes de commitar (ou se para e avisa o
  responsável); Média/Baixa vira sugestão no resumo. Subagente indisponível → não commitar e avisar.
- Mensagens de commit em português, descrevendo o quê e o porquê. Nunca `push --force` na `master`.
- Não comente sobre commits "Unverified" no GitHub.

## Estrutura

```
backend/    Java 21 + Spring Boot — br.com.gestorcobranca.<modulo>/{domain,application,infrastructure,web}
frontend/   Angular — src/app/features/<modulo>, src/app/core (shell, onboarding, manual), src/app/shared
infra/      Terraform (AWS), Docker Compose (local e sandbox) e imagem da aplicação
scripts/    banco, deploy, operação e sandbox de demonstração (scripts/sandbox/*.sh)
docs/       arquitetura/, negocio/ (regras, decisões, perfis), tecnico/ (convenções, desenho por módulo, logs, segurança)
```

## Comandos

```bash
# backend
cd backend
mvn test                      # unitários + regras de arquitetura/modularidade
mvn verify                    # + integração (Testcontainers com PostgreSQL 16; exige Docker)
mvn verify -Dit.test='CollectionFlowIT' -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false   # um IT

# frontend (Node 22.22.3+)
cd frontend
npm ci
npm run build                 # roda antes icons/bootstrap/money/hints/knowledge:check
npm test                      # testes unitários (uma execução)
npm run knowledge             # regenera a base de conhecimento do chat de suporte após mudar docs/manual

# sandbox de demonstração (Docker): app em http://localhost:58080, Mailpit em http://localhost:58025
./scripts/sandbox/up.sh       # sobe e popula com dados fictícios
./scripts/sandbox/reset.sh --yes
./scripts/sandbox/verify.sh   # checagens ponta a ponta
./scripts/sandbox/audit.sh    # auditoria da interface perfil a perfil (Playwright)
./scripts/sandbox/down.sh
```

Senha dos usuários do sandbox e demais detalhes: [README.md](README.md) (seção "Sandbox de demonstração").

## Convenções que mais pegam

- **Migrations Flyway** em `backend/src/main/resources/db/migration/<modulo>/`, na **faixa do módulo**
  ([docs/arquitetura/visao-geral.md](docs/arquitetura/visao-geral.md) §8). `out-of-order: true` está ligado; nunca
  altere uma migration já publicada. Tabelas novas com RLS (`shared.enable_tenant_rls`) e `check` para enums.
- **Mudança de contrato de caso de uso** (records em `application/port/in`): ajuste todos os chamadores e testes;
  módulos só conversam por `port/in` (`@NamedInterface("usecases")`) e por eventos de `shared.events`.
- **API pública** (`/public-api/v1`): contrato em `docs/api/public-api/v1/openapi.json` com teste de snapshot;
  mudança exige changelog. Tudo continua na versão 1.0 até o responsável liberar o versionamento.
- **Tela nova ou alterada**: manual rápido e tour (`frontend/src/app/core/onboarding`), novidade com `version` nova em
  `content/screens.ts` (os testes de `content.spec.ts` conferem) e `npm run knowledge`.
- **Testes**: regra nova → teste de domínio; fluxo entre módulos → IT contra PostgreSQL real
  (`backend/src/test/java/.../<modulo>/*IT.java`, base `IntegrationTest`).
- **Documentação** do módulo (`docs/tecnico/desenho/<modulo>.md`) atualizada na mesma mudança.

## Antes de concluir uma tarefa

1. `mvn test` (e os ITs dos módulos tocados) e `npm run build && npm test` quando houver frontend.
2. Telas alteradas: confira no sandbox (`audit.sh` cobre acesso por perfil, alinhamento e rolagem).
3. Docs, decisões e base de conhecimento atualizadas.
4. Revisão pelo subagente `revisor-de-codigo` antes do commit (obrigatória; veja "Fluxo de git").
5. Resumo curto ao responsável: o que mudou, decisões a revisar, como testar.
