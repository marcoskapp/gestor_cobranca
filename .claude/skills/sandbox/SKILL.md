---
name: sandbox
description: Sobe, reseta, verifica, audita e derruba o sandbox de demonstração do Gestor de Cobrança (Docker, dados fictícios), e confere telas alteradas perfil a perfil. Use quando o pedido for subir/parar/resetar o sandbox, ver uma mudança funcionando na tela, rodar verify.sh ou audit.sh, pegar tokens da API pública de demonstração ou os PDFs fictícios da Importação PDF.
---

# Sandbox de demonstração

Ambiente completo no Docker (projeto Compose `gestor-cobranca-sandbox`, `infra/docker/sandbox/compose.yml`) com
dados **100% fictícios**: 2 assessorias (`aurora`, `boreal`) × 3 credores × 4 carteiras e um usuário por perfil.
Guia completo: `docs/tecnico/operacao/sandbox.md` e a seção "Sandbox de demonstração" do `README.md`.

## Endereços e acesso

| O quê | Endereço |
| --- | --- |
| Aplicação | http://localhost:58080 |
| Mailpit | http://localhost:58025 |
| PostgreSQL | `127.0.0.1:55432` |
| Prontidão | `curl -s http://localhost:58080/actuator/health/readiness` → `"status":"UP"` só após a carga |

- Senha de todos os usuários: `Sandbox@2026!` (ou `SANDBOX_DEMO_PASSWORD`).
- Login: campo **Assessoria** = `aurora` ou `boreal`; e-mail `<usuário>@<assessoria>.exemplo.test`.
- Usuários: `admin`, `gestor`, `supervisor`, `operador`, `operador2` (só o 1º credor), `financeiro`, `auditor`,
  `juridico`, `advogado` (só o 1º credor), `analista`, `estrategia`, `qualidade`, `compliance`, `integracao`.
- Plataforma: assessoria `plataforma`, `admin@plataforma.exemplo.test`.
- 5 senhas erradas bloqueiam o usuário; 20 logins/min por IP. Não force tentativas.

## Escolha do comando

| Situação | Comando | Observações |
| --- | --- | --- |
| Subir pela primeira vez ou após mudar código | `./scripts/sandbox/up.sh` | reconstrói a imagem; 1º build 10–20 min, carga 2–3 min. Rode em background e espere o quadro "Sandbox do Gestor de Cobrança no ar" |
| Voltar sem mudança de código | `./scripts/sandbox/up.sh --no-build` | reaproveita a imagem e os dados |
| Recriar do zero (mudou migration, carga ou dados quebrados) | `./scripts/sandbox/reset.sh --yes` | **apaga os volumes do sandbox** (banco, tokens, arquivos). Não toca o ambiente de desenvolvimento. Confirme com o usuário se houver dados de demonstração que ele queira manter |
| Checagem ponta a ponta pela API | `./scripts/sandbox/verify.sh` | requer `jq`; para na primeira divergência |
| Auditoria da interface por perfil | `./scripts/sandbox/audit.sh [aurora/operador ...]` | relatório em `./sandbox-audit/relatorio.md`; saída 0 = ok, 1 = problemas, 2 = não rodou |
| Tokens da API pública | `./scripts/sandbox/tokens.sh` (`--raw` para scripts) | segredos fictícios, mas **não** cole em commits, issues ou docs |
| PDFs fictícios da Importação PDF | `./scripts/sandbox/pdfs.sh` | copia para `./sandbox-pdfs/` (fora do git) |
| Parar mantendo os dados | `./scripts/sandbox/down.sh` | |
| Expor na internet | `./scripts/sandbox/tunnel.sh` / `--stop` | **só com pedido explícito do usuário**: qualquer pessoa com o endereço chega ao login e a senha é pública. Derrube ao fim |

Pré-requisitos: Docker em execução (`docker info`), Compose v2, `jq`, portas 58080/58025/55432 livres.

## Conferir uma tela alterada

1. Código mudou → `./scripts/sandbox/up.sh` (com build). Migration ou carga mudou → `reset.sh --yes`.
2. `./scripts/sandbox/verify.sh` para garantir que nada quebrou na API.
3. `./scripts/sandbox/audit.sh <perfis afetados>` — cobre 403, avisos "Acesso negado", cartões desalinhados e
   rolagem além da área de conteúdo. Leia `sandbox-audit/relatorio.md` e trate cada problema (sem contorno).
4. Para um fluxo específico, dirija o navegador com o Playwright da imagem da auditoria (`--network host`) ou com o
   Node local, logando com o perfil adequado. Em 1280×1000 o popup "Primeira vez por aqui?" pode cobrir botões:
   feche-o antes de interagir.
5. Lembre a regra do projeto: menus e telas sem permissão ficam **ocultos** — se um perfil vê um item e recebe 403,
   é defeito.

## Regras

- Nunca use dados reais no sandbox nem tente subir o perfil `sandbox` junto com `prod`.
- `sandbox-audit/` e `sandbox-pdfs/` estão no `.gitignore`; não versione o conteúdo.
- Falhou um script → leia a mensagem `[ERRO]`, corrija a causa (script, código ou instrução) e registre em
  `docs/tecnico/claude-code.md` §4 se for problema do ambiente local.
- Ao terminar, informe ao usuário o que foi executado, o resultado e se o sandbox ficou no ar ou parado.
