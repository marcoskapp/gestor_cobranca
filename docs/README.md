# Documentação — Gestor de Cobrança

Toda a documentação do projeto fica nesta pasta, separada pelo público e pela finalidade. Os diagramas usam [Mermaid](https://mermaid.js.org/) e são renderizados pelo GitHub. O escopo funcional de referência (`escopo_funcional_crm_cobranca.docx`) fica no projeto do Claude.

```
docs/
├── negocio/       o QUE o produto faz e POR QUÊ — leitura do responsável pelo produto e da operação
├── arquitetura/   COMO a solução está estruturada — visão geral, topologia, stack, AWS e decisões (ADRs)
├── api/           contratos publicados (OpenAPI da API pública, versionados)
└── tecnico/       COMO implementar e operar — convenções, segurança, logs/métricas, desenho de cada módulo e guias de operação
```

**Onde colocar um documento novo**
- Regra de negócio, fluxo operacional, layout de arquivo para clientes, decisão de produto → `negocio/`.
- Decisão estrutural difícil de reverter (tecnologia, padrão transversal) → ADR em `arquitetura/adr/` (numeração sequencial).
- Desenho de um módulo (agregados, estados, eventos, permissões, migrations) → `tecnico/desenho/<modulo>.md`, com link para o documento de negócio correspondente.
- Evento de negócio, métrica ou campo de log novo → catálogo em `tecnico/logs-e-metricas.md`.
- Procedimento de operação (ativação de ambiente, implantação, rotinas, recuperação, sandbox de demonstração) → `tecnico/operacao/`.
- Contrato OpenAPI publicado da API pública → `api/public-api/<versão>/openapi.json` (gerado e conferido por teste; ver `tecnico/operacao/api-publica-publicacao.md`).

## Negócio

| Documento | Conteúdo |
| --- | --- |
| [assistente-ia.md](negocio/assistente-ia.md) | Assistente de negociação com IA: limites, canais, alçadas, modos de operação e implantação |
| [workflow-juridico.md](negocio/workflow-juridico.md) | Garantias da dívida e etapas do workflow jurídico (extrajudicial e judicial) |
| [ux-identidade-visual.md](negocio/ux-identidade-visual.md) | Identidade visual, navegação e onboarding por perfil |
| [layout-importacao.md](negocio/layout-importacao.md) | Layout canônico de importação de carteiras (CSV, XLSX e API) e a Importação PDF inteligente (§9) |
| [layout-extrato-conciliacao.md](negocio/layout-extrato-conciliacao.md) | Layout do extrato/arquivo de retorno (CSV) para a conciliação de pagamentos |
| [layout-demonstrativo-prestacao-contas.md](negocio/layout-demonstrativo-prestacao-contas.md) | Demonstrativo de prestação de contas ao credor: o que entra, cálculo do líquido, arquivo conciliável (CSV/XLSX) e ciclo |
| [indicadores.md](negocio/indicadores.md) | Indicadores dos painéis e relatórios: definição, fórmula e denominador de cada um (contrato com a operação) |
| [portal-autonegociacao.md](negocio/portal-autonegociacao.md) | Portal de autonegociação do devedor: acesso, condições, perfil `PORTAL`, atendimento e indicadores |
| [score-propensao.md](negocio/score-propensao.md) | Score de propensão: finalidade e limites de uso, dados permitidos, modelos, explicação e LGPD (telas em `tecnico/desenho/scoring.md` §12) |
| [perfis-de-acesso.md](negocio/perfis-de-acesso.md) | Perfis de acesso padrão por tipo de usuário e como se mantêm atualizados |
| [templates-cobranca.md](negocio/templates-cobranca.md) | Modelos iniciais de mensagens de cobrança (WhatsApp, e-mail e SMS) e templates exigidos pelo portal — para revisão do jurídico |
| [api-publica.md](negocio/api-publica.md) | API pública de integração dos credores: conexão por token, escopos, segurança, consumo e faturamento, versões e política de compatibilidade |
| [decisoes-autonomas.md](negocio/decisoes-autonomas.md) | Decisões de produto tomadas sem consulta, para revisão |

Regras de negócio que só existem no desenho técnico, por enquanto: remuneração variável dos negociadores ([tecnico/desenho/remuneracao-variavel.md](tecnico/desenho/remuneracao-variavel.md)) e negociação e alçadas ([tecnico/desenho/negociacao-e-alcadas.md](tecnico/desenho/negociacao-e-alcadas.md)).

## Arquitetura

| Documento | Conteúdo |
| --- | --- |
| [visao-geral.md](arquitetura/visao-geral.md) | Módulos, grafo de dependências, camadas, multi-tenancy, eventos, faixas de migrations |
| [topologia-logica.md](arquitetura/topologia-logica.md) | Diagrama da topologia lógica: camadas, módulos, dados, integrações e fluxos de log |
| [stack-tecnologica.md](arquitetura/stack-tecnologica.md) | Tecnologias adotadas, versão e finalidade |
| [infraestrutura-aws.md](arquitetura/infraestrutura-aws.md) | Estrutura na AWS, fases de crescimento e estimativa de custo |
| [adr/](arquitetura/adr/README.md) | Registros de decisões de arquitetura |

## Técnico

| Documento | Conteúdo |
| --- | --- |
| [convencoes.md](tecnico/convencoes.md) | Convenções de código, pacotes, persistência, testes |
| [seguranca.md](tecnico/seguranca.md) | Controles de segurança, LGPD, segregação de funções |
| [logs-e-metricas.md](tecnico/logs-e-metricas.md) | Fluxos de log (técnico, negócio, acesso), catálogo de eventos e métricas |
| [operacao/api-publica-publicacao.md](tecnico/operacao/api-publica-publicacao.md) | API pública: fluxo de mudança do contrato, publicação de release com diff (openapi-diff), nova versão e depreciação, segredo do hash dos tokens |
| [operacao/ativacao-aws.md](tecnico/operacao/ativacao-aws.md) | Guia operacional de ativação na AWS: pré-requisitos, Terraform, segredos, banco, implantação, primeiro acesso, integrações, operação e checklists de segurança e go-live |
| [operacao/sandbox.md](tecnico/operacao/sandbox.md) | Sandbox de demonstração com dados fictícios subindo com um comando: scripts, URLs, usuários e senhas por perfil, o que vem carregado, roteiro de demonstração e proteções contra ativação em produção |
| [desenho/](tecnico/desenho) | Desenho de cada módulo: `collection`, `importacao` (conferência da importação), `importacao-pdf` (Importação PDF inteligente), `comunicacao`, `campanhas`, `telefonia`, `negociacao-e-alcadas`, `pagamentos`, `prestacao-de-contas`, `juridico`, `assistente`, `chat-suporte`, `remuneracao-variavel`, `scoring`, `analytics`, `portal`, `pendencias` (Minhas pendências), `ux`, `api-publica` (API pública de integração) |

## Caminhos antigos

Migrations já publicadas não são editadas (o Flyway valida o checksum), então comentários nelas podem citar os caminhos anteriores à reorganização de 23/10/2026:

| Antigo | Atual |
| --- | --- |
| `docs/ARCHITECTURE.md` | `docs/arquitetura/visao-geral.md` |
| `docs/CONVENTIONS.md` | `docs/tecnico/convencoes.md` |
| `docs/SECURITY.md` | `docs/tecnico/seguranca.md` |
| `docs/LOGGING.md` | `docs/tecnico/logs-e-metricas.md` |
| `docs/design/*` | `docs/tecnico/desenho/*` |
| `docs/adr/*` | `docs/arquitetura/adr/*` |
| `documentação/*` | `docs/negocio/*` ou `docs/arquitetura/*` |
