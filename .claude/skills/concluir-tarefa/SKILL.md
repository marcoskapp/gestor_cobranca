---
name: concluir-tarefa
description: Checklist de conclusão de uma tarefa no Gestor de Cobrança — testes do backend e frontend, ITs dos módulos tocados, sandbox, documentação, decisões autônomas, base de conhecimento, revisão pelo subagente revisor-de-codigo, commit e resumo ao responsável (PO). Use ao terminar uma mudança, antes de commitar, ou quando o usuário pedir para "fechar", "concluir" ou "finalizar" a tarefa.
---

# Concluir uma tarefa

Siga na ordem. Não pule etapas nem marque como feito o que não rodou: se algo falhar ou não puder rodar (ex.: Docker
parado), diga isso no resumo com a saída relevante. **Sem workarounds** — teste falhando se corrige na causa, nunca
com `@Disabled`, `-DskipTests` ou afrouxando a asserção.

## 1. Levantar o que mudou

```bash
git status
git diff --stat HEAD
```

Classifique os arquivos: módulos do backend (`backend/src/main/java/br/com/gestorcobranca/<modulo>/`), migrations,
`application/port/in`, API pública, frontend (telas, `content/screens.ts`, manual), docs, infra/scripts.

## 2. Backend (se houve mudança em `backend/`)

```bash
cd backend && mvn test
```

Inclui `ArchitectureTest` e `ModularityTest` — precisam passar.

ITs dos módulos tocados (exige Docker), um por vez ou separados por vírgula:

```bash
cd backend && mvn verify -Dit.test='<Classe1>IT,<Classe2>IT' -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false
```

Os ITs ficam em `backend/src/test/java/br/com/gestorcobranca/<modulo>/*IT.java`. Mudou migration → inclua
`MigrationOrderIT` e `MigrationRunnerIT`. Mudança ampla ou entre vários módulos → `mvn verify` completo (~7 min).

Confira também:
- **Migrations**: na pasta e na faixa do módulo (`docs/arquitetura/visao-geral.md` §8), nenhuma migration publicada
  alterada, tabela nova com `shared.enable_tenant_rls` e `check` nos enums.
- **Contrato de `port/in` alterado**: todos os chamadores e testes ajustados; módulos só conversam por `port/in` e
  eventos de `shared.events`.
- **API pública** (`/public-api/v1`): `docs/api/public-api/v1/openapi.json` e changelog atualizados; teste de
  snapshot passando. Versão continua 1.0.
- **Regra nova** tem teste de domínio; **fluxo entre módulos** tem IT contra PostgreSQL real.
- **Segurança**: escopo por credor no caso de uso, `AuditEntry` em alteração relevante, log de acesso a dado
  sensível, nenhum dado pessoal em logs, eventos ou métricas.
- **Logs/métricas**: evento de negócio (`BusinessEventLogger`) ou métrica nova documentado em
  `docs/tecnico/logs-e-metricas.md`.

## 3. Frontend (se houve mudança em `frontend/`)

```bash
cd frontend && npm run build && npm test
```

O `prebuild` roda `icons/bootstrap/money/hints/knowledge:check`. Se `knowledge:check` falhar, rode `npm run knowledge`
e inclua o `backend/src/main/resources/helpdesk/knowledge-base.json` regenerado.

Tela nova ou alterada:
- manual rápido e tour em `frontend/src/app/core/onboarding`;
- novidade com `version` nova em `content/screens.ts` (`content.spec.ts` confere);
- `npm run knowledge` após mexer no manual;
- campos monetários com `appMoney`; menus/telas sem permissão **ocultos**.

## 4. Sandbox (se alguma tela mudou)

Use a skill `sandbox`: subir com build (`./scripts/sandbox/up.sh`; `reset.sh --yes` se mudou migration ou carga),
`./scripts/sandbox/verify.sh` e `./scripts/sandbox/audit.sh` com os perfis afetados.

## 5. Documentação e decisões

- `docs/tecnico/desenho/<modulo>.md` do módulo tocado atualizado na mesma mudança.
- Decisão tomada sem consulta ao responsável → nova entrada em `docs/negocio/decisoes-autonomas.md`, com número
  **sequencial** — o maior número existente sai de
  `grep -oE '^\| *[0-9]+ ' docs/negocio/decisoes-autonomas.md | tr -dc '0-9\n' | sort -n | tail -1`; nunca
  reutilize. Linha da tabela da seção do assunto: `| nº | módulo [**[revisar]**] | decisão | alternativas | motivo |`.
  Assunto novo → seção `## <assunto> (<data>, <branch>; decisões N–M)`.
- Item que depende de aprovação (serviço AWS extra, provedor externo, custo novo) → issue no GitHub
  (`gh issue create`), não código.
- Regra do projeto mudou → `CLAUDE.md` atualizado.

## 6. Higiene antes do commit

- Nenhum dado real, segredo, chave, `.env`, `*.tfvars` ou arquivo de exemplo enviado pelo responsável no diff
  (repositório público). Fixtures só com dados fictícios (`exemplo.test`, documentos gerados).
- Nada de `sandbox-audit/`, `sandbox-pdfs/` ou artefatos de build.

## 7. Revisão de código (sempre, antes de qualquer commit — regra do `CLAUDE.md`, "Fluxo de git")

Obrigatória antes de commitar, mesmo em mudança pequena. Chame o subagente **`revisor-de-codigo`**
([.claude/agents/revisor-de-codigo.md](../../agents/revisor-de-codigo.md)) pela ferramenta Agent, com
`subagent_type: "revisor-de-codigo"`. No prompt, passe:
- o alvo: as mudanças desta tarefa (`git diff HEAD` mais os arquivos novos, listados por caminho);
- em uma ou duas frases, o objetivo da mudança e os módulos tocados.

Não passe a sua conclusão sobre a mudança nem peça que confirme que "está tudo certo": a revisão vale justamente por
não partir da sua leitura.

Com o relatório em mãos:
- Confira cada issue no código antes de agir. Issue que não se confirma → descarte e diga por quê no resumo.
- **Crítica** ou **Alta** confirmada no que a tarefa alterou → corrija na causa e volte às etapas 2–4 dos trechos
  tocados (testes, build, sandbox). Se não der para corrigir agora (depende de aprovação, foge do escopo), pare,
  registre (issue no GitHub ou decisão autônoma) e avise o responsável antes de commitar.
- **Média** ou **Baixa**, ou achado em código que a tarefa não tocou → não corrija por conta própria; liste no resumo
  ao responsável como sugestão.
- Correção que mudou o diff de forma relevante → rode o revisor de novo, só sobre os arquivos corrigidos.

Se o subagente não estiver disponível ou falhar, não commite: diga isso ao usuário e pergunte como seguir.

## 8. Commit (só se o usuário pedir)

- Só depois da etapa 7 concluída, sem issue Crítica ou Alta pendente no que a tarefa alterou.
- Fluxo atual: commit direto na `master` (até o responsável liberar branch + PR). Nunca `push --force`.
- Mensagem em português, dizendo o quê e o porquê; cite as decisões novas (ex.: "decisão 802").
- Não comente sobre commits "Unverified".

## 9. Resumo ao responsável

Curto, em português:
1. **O que mudou** — por módulo/tela.
2. **Testes** — o que rodou e o resultado (contagens quando houver); o que não rodou e por quê.
3. **Decisões a revisar** — números e uma linha cada.
4. **Revisão de código** — issues do `revisor-de-codigo` corrigidas, descartadas (com motivo) e sugestões pendentes.
5. **Issues abertas** — se houver.
6. **Como testar** — perfil do sandbox, tela e passos.
