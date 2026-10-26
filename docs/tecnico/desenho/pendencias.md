# Desenho — Minhas pendências (work inbox)

Pedido do responsável: *"apresentar ao usuário logado o que ele pode fazer (itens pendentes), como por exemplo a aprovação de pagamentos… um dashboard operacional linkável, que leva o usuário até a ação que pode ser executada"*. Padrão de mercado adotado: **work inbox** ("Minhas pendências") — contagens por tipo de pendência, com destaque de prazo e link direto para a tela de ação já filtrada.

## 1. Visão geral

```
 Angular (aba)                                       Backend
 ┌──────────────────────────┐   GET /api/v1/me/pending-work   ┌─────────────────────────────────────────┐
 │ PendingWorkStore (única) │ ──────────────────────────────► │ pendingwork: PendingWorkService          │
 │  ├ indicador (barra sup.)│   60 s, pausa com aba oculta    │  ├ fontes que se aplicam às permissões   │
 │  ├ "Suas pendências"     │ ◄────────────────────────────── │  ├ executor limitado (4) + tempo máx 2 s │
 │  └ /pendencias           │   itens + indisponíveis          │  └ observação diária (log de negócio)    │
 └──────────────────────────┘                                  └──────┬──────────────────────────────────┘
                                                                      │ List<PendingWorkSource> (injeção)
              shared.pendingwork.PendingWorkSource ◄── implementado por payment, negotiation, ingestion,
                                                       settlement, incentive, compliance, collection,
                                                       communication, legal, assistant, scoring
```

- **Contrato no kernel** (`shared.pendingwork`): `PendingWorkSource` (`id`, `label`, `appliesTo(actor)`, `pendingFor(request)`), `PendingWorkItem` (tipo estável, rótulo, orientação, contagens, rota e filtro), `PendingWorkCount`, `PendingWorkSeverity`, `PendingWorkRequest` (mesmo "agora" e mesma data de negócio para todas as fontes) e `PendingWorkRows` (leitura padronizada de uma linha de contagem JDBC). É linguagem publicada, como os eventos de integração.
- **Fonte em cada módulo dono**: `application/service/<Modulo>PendingWork` (regra de visibilidade e destino) + porta `application/port/out/<Modulo>PendingWorkQueries` + adaptador `infrastructure/persistence/Jdbc<Modulo>PendingWorkQueries` (contagem indexada, RLS da transação). Quando a tela de destino já tem a regra em Java (fila de aprovação de propostas por alçada), a fonte reutiliza o mesmo componente (`ProposalApprovalQueue`).
- **Agregador** (`pendingwork`, dependências: só `shared`): não conhece os módulos; recebe as implementações por injeção. Uma fonte nova não altera o agregador nem o grafo.

## 2. Itens (fontes implementadas)

| Tipo | Quem vê (permissão) | Regra (visibilidade, segregação) | Prazo / severidade | Destino |
| --- | --- | --- | --- | --- |
| `payment.external_review` | `PAYMENT_REVIEW` | pagamento externo `INFORMED` no escopo; **quem informou não vê** | sem prazo; idade desde `informed_at` | `/pagamentos/aprovacoes` |
| `payment.divergences` | `RECONCILIATION_MANAGE` | divergência `OPEN` no escopo (sem credor: só com todos os credores) | idade | `/pagamentos/divergencias?situacao=OPEN` |
| `negotiation.proposal_approval` | `NEGOTIATION_APPROVE` | fila de aprovação: escopo, **alçada do aprovador para o credor**, exclui as próprias | validade da proposta | `/negociacao/aprovacoes` (1 item: a proposta) |
| `negotiation.proposal_acceptance` | `AGREEMENT_FORMALIZE` | propostas **do próprio usuário** aprovadas aguardando aceite | validade | `/negociacao/propostas?situacao=APPROVED&minhas=true` |
| `negotiation.agreement_formalization` | `AGREEMENT_FORMALIZE` | propostas do próprio usuário aceitas aguardando formalização | validade | `…?situacao=ACCEPTED&minhas=true` |
| `ingestion.review_approval` | `IMPORT_APPROVE` | conferência `AWAITING_APPROVAL` no escopo; **nem quem criou nem quem pediu a efetivação** | idade desde o pedido | `/importacao` (1 item: a conferência) |
| `ingestion.review_alerts` | `IMPORT_EXECUTE` | conferências abertas **do usuário** com alertas de valor sem decisão | idade | idem |
| `settlement.statement_review` | `SETTLEMENT_CLOSE` | demonstrativo `CALCULATED` no escopo | idade | `/prestacao-de-contas/demonstrativos?situacao=CALCULATED` |
| `settlement.statement_approval` | `SETTLEMENT_APPROVE` | `REVIEWED`; **não calculou nem pediu ajuste aplicado**; sem ajuste pendente | idade | `…?situacao=REVIEWED` |
| `settlement.adjustment_approval` | `SETTLEMENT_APPROVE` | ajuste `PENDING_SECOND_APPROVAL` pedido por outra pessoa | idade | demonstrativos (1 item: o demonstrativo) |
| `incentive.statement_approval` | `COMMISSION_STATEMENT_APPROVE` | extrato `CALCULATED/ADJUSTED`; não é o beneficiário, não fechou o período, não ajustou nem resolveu contestação; sem contestação aberta nem ajuste pendente | idade | `/remuneracao/extratos` |
| `incentive.statement_disputes` | `COMMISSION_STATEMENT_MANAGE` | contestação `OPEN` aberta por outra pessoa | idade | `…?situacao=DISPUTED` |
| `incentive.adjustment_approval` | `COMMISSION_STATEMENT_MANAGE` | ajuste pendente pedido por outra pessoa, fora do próprio extrato | idade | extratos |
| `compliance.data_subject_requests` | `DATA_SUBJECT_REQUEST_MANAGE` + todos os credores | solicitação aberta com prazo legal até o fim da janela | prazo legal (`due_at`) | `/compliance/solicitacoes-titulares[?vencidas=true]` |
| `collection.callbacks_due` | `CASE_WORK` ou `TASK_MANAGE` | retornos **atribuídos ao usuário** pendentes até o fim do dia e perdidos dos últimos 30 dias | horário do retorno | `/cobranca/agenda?periodo=late|today` (1 item: o caso) |
| `collection.disputes_due` | `DISPUTE_MANAGE` | contestação aberta/em análise no escopo com resposta até o fim da janela | prazo de resposta | `/cobranca/contestacoes[?vencidas=true]` |
| `communication.my_conversations` | `INBOX_VIEW` | conversas **do usuário** aguardando resposta | vencida = além do limite do SLA de primeira resposta do canal | `/conversas?aba=mine&aguardando=true` |
| `communication.queue_conversations` | `INBOX_VIEW` | conversas na fila (dono fila/nenhum) aguardando, no escopo | idem | `/conversas?aba=queue&aguardando=true` |
| `communication.template_approval` | `TEMPLATE_MANAGE` | rascunhos aguardando decisão do provedor ou aprovados sem publicar (template da assessoria: só com todos os credores) | idade | `/conversas/templates?situacao=DRAFT` |
| `legal.deadlines_due` | `LEGAL_MANAGE` | prazos pendentes até o fim da janela sob responsabilidade do usuário (do prazo ou, sem ele, do caso); escritório parceiro: só casos do seu escritório | `due_date` | `/juridico` (1 item: o caso) |
| `assistant.turn_reviews` | `ASSISTANT_REVIEW` | turnos sorteados para revisão sem avaliação, no escopo | idade | `/assistente/revisao` |
| `scoring.challenger_decision` | `SCORING_MANAGE` + todos os credores | desafiante validado; **quem treinou não vê** | idade | `/score/modelos/:id` |

Severidade: `OVERDUE` (algum item vencido), `DUE_TODAY` (algum vence hoje), `NORMAL`. A **janela "perto do prazo"** (`app.pending-work.due-soon-days`, 3 dias corridos) vale para as fontes que só listam o que vence em breve (solicitações de titulares, contestações, prazos jurídicos). Aprovações sem prazo são `NORMAL` e mostram a idade do item mais antigo. Com **um único item** e id conhecido, o link abre o próprio item.

### 2.1 Candidatas não implementadas

| Candidata | Motivo |
| --- | --- |
| Tabulação obrigatória pendente na telefonia | A estação já abre a tabulação no pós-atendimento e o job `TabulationDeadlineJob` tabula automaticamente em 15 min: com atualização de 60 s o item seria ruído e duplicaria o fluxo da estação. |
| Rascunhos do assistente no modo copiloto | Aparecem dentro da conversa do operador, que já está em `communication.my_conversations`. |
| Autorização do credor para indicação ao jurídico (`LEGAL_AUTHORIZE`) | Não estava entre as candidatas; a fila existe no painel jurídico. Próximo candidato natural. |
| Pedidos do portal que fogem da autonegociação | Viram conversa na fila humana (`HandoffRequested`) e entram em `communication.queue_conversations`. |
| Fila por equipe nas conversas | O `communication` não conhece a composição das equipes (depende só de `collection`); a fila mostrada é a do escopo de credores, igual à aba "Fila" da caixa de entrada. |

## 3. Desempenho e resiliência

- Cada fonte roda em **transação somente leitura própria** (`TenantExecution.callAsReadOnly`, com o mesmo ator), com `statement_timeout` local igual a `app.pending-work.timeout` (2 s): consulta lenta é interrompida no banco, não só abandonada.
- **Executor limitado** (`pendingWorkExecutor`, 4 threads por instância, fila 500, recusa explícita): a tela inicial de muitos usuários não esgota o pool de conexões. Fonte recusada volta como `BUSY`.
- O agregador espera no máximo o tempo limite pelo conjunto; fonte que falha (`ERROR`), estoura o tempo (`TIMEOUT`, tarefa cancelada) ou é recusada (`BUSY`) vai para `unavailable` e as demais seguem na resposta.
- Contagens por índice existente (fila de revisão de pagamento, divergências abertas, fila de propostas, agenda, contestações abertas, conversas por dono, prazos jurídicos, revisão do assistente) ou novo: `V1090` (rascunhos de template) e `V1890` (extratos, contestações e ajustes da remuneração).
- **Sem cache no servidor**: a interface faz uma única consulta por aba (indicador, tela inicial e lista compartilham a `PendingWorkStore`) a cada 60 s, pausada com a aba oculta; as contagens são baratas. Cache por usuário fica como evolução se `pendingwork.request.duration` pedir.

## 4. Segurança e LGPD

- `GET /api/v1/me/pending-work` com `ACTOR_USER`; nunca há id de usuário na URL. `Cache-Control: no-store`.
- Só contagens, datas e ids técnicos (o id do único item, para o link). Nenhum nome, documento, contato ou valor. `PendingWorkItem` valida o formato do tipo, da rota (relativa, sem query) e dos filtros (só códigos e ids).
- Cada fonte aplica a mesma regra da tela de destino: permissão, escopo por credor (`AccessScope` + RLS) e segregação de funções.

## 5. Logs e métricas

- Negócio: `pending_work.observed` uma vez por usuário, tipo e dia (`pendingwork.daily_observation`, `insert … on conflict do nothing`; retenção 30 dias pelo job `pendingwork.retention`). Atributos em `logs-e-metricas.md` §3.11.
- Técnico: WARN por fonte com falha ou tempo excedido (`source`, `error`/`timeoutMs`). Métricas `pendingwork.*` em `logs-e-metricas.md` §5.

## 6. Interface

- **Barra superior**: `app-pending-work-indicator` (ícone `inbox`, antes da troca de tema) com selo do total (vermelho com algum vencido), rótulo acessível com total, vencidos e de hoje; menu `ngbDropdown` com as 6 primeiras pendências e "Ver todas as pendências".
- **Tela inicial**: seção "Suas pendências" no topo (até 6 cartões, borda e selo por severidade com ícone e texto), estado vazio positivo "Nada pendente", aviso das áreas indisponíveis, erro com "Tentar novamente".
- **Lista completa** `/pendencias` (também no menu): agrupada por área, na ordem de urgência.
- **Atualização**: 60 s enquanto houver consumidor e a aba estiver visível; ao voltar à aba, recarrega se os dados tiverem mais de 60 s; ao abrir o menu ou a tela inicial, se tiverem mais de 15 s. Mudança de total ou de vencidos é anunciada por `LiveAnnouncer` polido, uma vez por mudança, nunca na primeira carga. SSE não foi usado: a infraestrutura existente é específica da caixa de entrada (LISTEN/NOTIFY de conversas).
- **Telas de destino** que passaram a aceitar o filtro pela URL: agenda (`periodo`), caixa de entrada (`aba`, `aguardando`), propostas (`situacao`, `minhas` → `mine=true` na API), templates (`situacao`), solicitações de titulares e contestações (`vencidas`).

## 7. Testes

- `PendingWorkItemTest`, `PendingWorkServiceTest` (falha e tempo limite isolados, permissões, descarte de zerados, ordenação, dia de negócio, faixas de idade).
- `PendingWorkApiIT` (PostgreSQL real): segregação do pagamento externo e do demonstrativo, escopo por credor, todas as fontes reais executando sob RLS sem indisponibilidade, ausência de dado pessoal, observação diária única, 401/403.
- Frontend: `pending-work.format`, `PendingWorkStore` (compartilhamento, pausa com aba oculta, anúncio, erro, saída da sessão), indicador, seção da tela inicial e lista completa, filtros de destino.
