# Documentação — Gestor de Cobrança

Toda a documentação do projeto fica nesta pasta, separada pelo público e pela finalidade. Os diagramas usam [Mermaid](https://mermaid.js.org/) e são renderizados pelo GitHub. O escopo funcional de referência (`escopo_funcional_crm_cobranca.docx`) fica no projeto do Claude.

```
docs/
├── negocio/       o QUE o produto faz e POR QUÊ — leitura do responsável pelo produto e da operação
├── arquitetura/   COMO a solução está estruturada — visão geral, topologia, stack, AWS e decisões (ADRs)
└── tecnico/       COMO implementar — convenções, segurança, logs/métricas e desenho detalhado de cada módulo
```

**Onde colocar um documento novo**
- Regra de negócio, fluxo operacional, layout de arquivo para clientes, decisão de produto → `negocio/`.
- Decisão estrutural difícil de reverter (tecnologia, padrão transversal) → ADR em `arquitetura/adr/` (numeração sequencial).
- Desenho de um módulo (agregados, estados, eventos, permissões, migrations) → `tecnico/desenho/<modulo>.md`, com link para o documento de negócio correspondente.
- Evento de negócio, métrica ou campo de log novo → catálogo em `tecnico/logs-e-metricas.md`.

## Negócio

| Documento | Conteúdo |
| --- | --- |
| [assistente-ia.md](negocio/assistente-ia.md) | Assistente de negociação com IA: limites, canais, alçadas, modos de operação e implantação |
| [workflow-juridico.md](negocio/workflow-juridico.md) | Garantias da dívida e etapas do workflow jurídico (extrajudicial e judicial) |
| [ux-identidade-visual.md](negocio/ux-identidade-visual.md) | Identidade visual, navegação e onboarding por perfil |
| [layout-importacao.md](negocio/layout-importacao.md) | Layout canônico de importação de carteiras (CSV, XLSX e API) |
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
| [desenho/](tecnico/desenho) | Desenho de cada módulo: `collection`, `comunicacao`, `campanhas`, `telefonia`, `negociacao-e-alcadas`, `juridico`, `assistente`, `remuneracao-variavel`, `ux` |

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
