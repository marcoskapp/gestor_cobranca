# Desenvolvimento com o Claude Code no ambiente local

Guia da migração do desenvolvimento assistido para o **Claude Code** na máquina do desenvolvedor, e o roteiro de testes
para confirmar que o ambiente local atende ao projeto. O projeto no claude.ai continua sendo o lugar das conversas de
produto, decisões de PO, apresentações e relatórios.

## 1. O que já está no repositório

| Arquivo | Para quê |
| --- | --- |
| [`CLAUDE.md`](../../CLAUDE.md) | Instruções do projeto lidas pelo Claude Code em toda sessão: regras obrigatórias (stack, arquitetura, segurança, logs, aprovações), fluxo de git, comandos, convenções e o que conferir antes de concluir |
| [`.claude/settings.json`](../../.claude/settings.json) | Permissões compartilhadas: comandos de build/teste liberados sem confirmação; leitura de segredos (`.env`, chaves, `*.tfvars`, `backend/config/dev`), `git push --force` e `terraform apply/destroy` bloqueados |
| `CLAUDE.local.md`, `.claude/settings.local.json` | Preferências pessoais de cada desenvolvedor (ignorados pelo git) |

As regras de `CLAUDE.md` vieram das instruções do projeto no claude.ai e das decisões do responsável ao longo do
desenvolvimento. Mudou uma regra → atualize `CLAUDE.md` na mesma mudança (vale para toda a equipe).

## 2. Pré-requisitos da máquina

| Item | Verificação | Esperado |
| --- | --- | --- |
| Docker Desktop (engine + Compose) | `docker info` e `docker compose version` | engine em execução; Compose v2 |
| Memória para o Docker | Docker Desktop → Settings → Resources | sugestão: 8 GB ou mais (sandbox + testes de integração em paralelo) |
| JDK 21 | `java -version` | 21.x |
| Maven | `mvn -v` | 3.9+ usando o JDK 21 |
| Node | `node -v` | 22.22.3+ |
| jq | `jq --version` | qualquer versão recente (`brew install jq`) |
| Git com acesso ao GitHub | `git fetch` no repositório | sem erro de autenticação |
| Claude Code | instalação pela [documentação oficial](https://docs.claude.com/en/docs/claude-code/overview) | `claude` abre na pasta do repositório |

## 3. Roteiro de testes do ambiente local

Execute na ordem e registre o resultado (tempo e observações) na tabela da §4. Cada etapa diz o que confirma.

1. **Instruções carregadas** — na pasta do repositório, abra `claude` e peça um resumo das regras do projeto. Confirma
   que o `CLAUDE.md` é lido (o comando `/memory` mostra os arquivos carregados) e que `/permissions` lista as regras de
   `.claude/settings.json`.
2. **Backend, unitários** — `cd backend && mvn test`. Confirma JDK, Maven e as regras de arquitetura/modularidade.
3. **Backend, integração** — `cd backend && mvn verify`. Confirma o Testcontainers (baixa `postgres:16-alpine` do
   Docker Hub na primeira vez) e o desempenho da máquina com vários contextos Spring.
4. **Frontend** — `cd frontend && npm ci && npm run build && npm test`. Confirma Node e as checagens de pré-build.
5. **Sandbox** — `./scripts/sandbox/reset.sh --yes`, depois `./scripts/sandbox/verify.sh`. Confirma Docker Compose,
   portas 58080/58025/55432 livres e a carga de dados fictícios.
6. **Auditoria da interface** — `./scripts/sandbox/audit.sh`. Confirma o Playwright (imagem oficial ou Node local) e
   que o Claude Code consegue verificar telas no sandbox, perfil a perfil.
7. **Navegação pelo Claude Code** — peça ao Claude Code para abrir uma tela do sandbox (ex.: estação de cobrança com o
   usuário operador), tabular um caso com "Informado falecimento" e conferir o bloqueio. Confirma o ciclo
   "alterar → subir → ver na tela" sem intermediário.
8. **Tarefa de ponta a ponta** — uma mudança pequena real (ex.: texto de uma tela), com testes, commit e push. Confirma
   o fluxo de git e as permissões.
9. **AWS (quando houver homologação)** — `aws sts get-caller-identity` e `terraform plan` em `infra/terraform`.
   Somente leitura: `terraform apply` segue bloqueado nas permissões e só roda com o responsável.

## 4. Registro dos resultados

| # | Etapa | Resultado | Tempo | Observações |
| --- | --- | --- | --- | --- |
| 1 | Instruções carregadas | | | |
| 2 | `mvn test` | | | |
| 3 | `mvn verify` | ok (05/11/2026) | ~6min40s | 1191 unitários + 341 de integração, sem falhas, após duas correções. (1) Testes desatualizados desde a `V0603`: os inserts SQL de `AnalyticsTestSupport` e `CollectionIsolationIT` não passavam `follow_up`; e o `SandboxApiDocsOpenIT` tentava gravar logs em `/var/lib/gestor-cobranca/logs`, que só existe na imagem Docker (agora usa `./target`). (2) Falha intermitente nos ITs de telefonia: o relógio da VM do Docker ficava 0,3–0,5 s atrás do Mac e o job imediato, com `run_at` pela hora da JVM, ficava "no futuro" para o banco; corrigido na fila (decisão 801, `JobQueueIT`) |
| 4 | Frontend build + testes | | | |
| 5 | Sandbox reset + verify | | | |
| 6 | `audit.sh` | | | |
| 7 | Navegação pelo Claude Code | ok (05/11/2026) | ~10 min | Playwright da imagem da auditoria (`--network host`), `aurora/operador`: a tabulação "Informado falecimento" mostra o aviso da ação automática, o registro cria a restrição `DECEASED` ativa em todos os credores, ligada à interação, e a estação passa a mostrar "Todos os canais com restrição de contato" (4 canais restritos, caso inelegível). O popup "Primeira vez por aqui?" cobre o botão de registrar em 1280×1000 e precisa ser fechado antes |
| 8 | Tarefa de ponta a ponta | | | |
| 9 | AWS (somente leitura) | | | |

Critério para adotar o ambiente local: etapas 1 a 8 sem erro, com tempos aceitáveis para o dia a dia. Falha em alguma
etapa → registrar aqui e corrigir o script ou a instrução (sem contorno), antes de mudar o fluxo de trabalho.

## 5. O que muda no fluxo

- **Onde o trabalho roda**: na máquina do desenvolvedor, com o Docker, a IDE e (depois) as credenciais da AWS dele. A
  sessão depende da máquina ligada; tarefas longas com a máquina desligada podem usar as sessões do Claude Code na
  nuvem, com as mesmas limitações de rede do ambiente atual.
- **Git**: continua direto na `master` até o responsável liberar branch + PR (`CLAUDE.md` §Fluxo de git). Com a equipe
  de homologação, o fluxo passa a ser PR com aprovação do responsável.
- **Material do claude.ai**: o escopo funcional e a planilha de OPEX ficam no projeto do claude.ai; o que o
  desenvolvimento precisa consultar está em `docs/`. Decisão de produto tomada no claude.ai → registrar em
  `docs/negocio/decisoes-autonomas.md` (ou no desenho do módulo) para o Claude Code enxergar.
