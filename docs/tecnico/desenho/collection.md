# Desenho — módulo `collection` (operação de cobrança)

Escopo: M03, F06 (caso de cobrança), F07 (filas, atribuição e distribuição), F21 (contestações), RB02 (elegibilidade).
Schema `collection`, migrations V0600–V0699 (`V0600` tabelas, `V0601` permissões e catálogo padrão de tabulações).
Dependências: `shared`, `organization :: usecases`, `identity :: usecases`, `debt :: usecases`, `compliance :: usecases`.
Os demais fatos chegam por eventos de integração (`shared.events.*`).

## 1. Caso de cobrança

`CollectionCase` agrega os títulos em cobrança de **um devedor numa carteira** (e, portanto, num credor). Há no máximo um caso
ativo por (devedor, carteira) — índice único parcial `uk_case_active`. O caso guarda um retrato da posição dos títulos
(`CaseBalance`: saldo em aberto, saldo elegível, títulos em aberto/suspensos/contestados, vencimento mais antigo), a
elegibilidade com os motivos, a fila, o dono do atendimento e indicadores de trabalho (interações, contatos efetivos,
última interação, próxima ação).

### 1.1 Abertura, atualização e encerramento automáticos

| Gatilho | Reação |
| --- | --- |
| `PortfolioLoaded` (fim de carga) | job `collection.portfolio_sync`: percorre os devedores com títulos gravados pelo lote (`DebtTitleUseCases#debtorIdsByImportBatch`, paginação por chave, 200 por execução) e sincroniza cada um |
| `TitleStatusChanged` (status, saldo, contestação, transferência) | sincroniza os casos do devedor no credor do título |
| `POST /cases/{id}/refresh` | sincronização sob demanda |

Sincronizar (`CaseSynchronizer#syncDebtor`), sob lock consultivo por (tenant, devedor):

1. lê os títulos do devedor no credor (`DebtTitleUseCases#listByDebtor`) e agrupa por carteira;
2. carteira com título `OPEN` com saldo e sem caso ativo → **abre** o caso (`case.opened`);
3. carteira com caso ativo e títulos em cobrança (`OPEN`/`SUSPENDED` com saldo) → **atualiza** a posição;
4. caso ativo sem títulos em cobrança → **encerra** com o motivo pelo destino dos títulos: todos quitados `SETTLED`,
   devolvidos `RETURNED`, cancelados `CANCELLED`; combinação ou transferência de carteira `NO_OPEN_TITLES`;
5. reavalia elegibilidade e fila de cada caso tocado.

A sincronização é idempotente pelo estado: a ordem de chegada dos eventos não importa, cada consumo lê a posição atual.

### 1.2 Estados e transições

```
             ┌────────────── estágios de tratamento ───────────────┐
  abertura → OPEN ──contato efetivo──► IN_NEGOTIATION ◄──┐
              │                          │   ▲  │         │ promessa cumprida/quebrada/cancelada
              │                          │   │  └──promessa──► PROMISE
              └────────── acordo formalizado (negotiation) ──► AGREEMENT ──quebra/cancelamento──┘
  retenções (guardam o estágio e o restauram na saída; prioridade LEGAL > SUSPENDED > DISPUTED):
     DISPUTED (contestação bloqueante) · SUSPENDED (manual) · LEGAL (jurídico)
  qualquer estado ativo ──► CLOSED (final; título novo na carteira abre outro caso)
```

| De | Para | Quem | Validação |
| --- | --- | --- | --- |
| OPEN | IN_NEGOTIATION | interação com tabulação `effectiveContact` | caso ativo |
| qualquer estágio | PROMISE | interação com tabulação `promise` | dados da promessa válidos |
| PROMISE | IN_NEGOTIATION | promessa cumprida, quebrada ou cancelada | sem outra promessa pendente |
| estágio | AGREEMENT | `AgreementStatusChanged` FORMALIZED/IN_PAYMENT | — (promessa pendente é cancelada) |
| AGREEMENT | IN_NEGOTIATION | `AgreementStatusChanged` BROKEN/CANCELLED | estágio corrente é AGREEMENT |
| ativo | DISPUTED | contestação bloqueante aberta | retenção maior (LEGAL, SUSPENDED) prevalece |
| DISPUTED | estágio guardado | última contestação bloqueante encerrada | — |
| ativo | SUSPENDED / LEGAL | `CASE_MANAGE` com motivo | retenção de prioridade maior prevalece |
| SUSPENDED / LEGAL | estágio guardado (ou DISPUTED se houver contestação bloqueante) | `CASE_MANAGE` com motivo | o caso está naquela retenção (`case.not_suspended`, `case.not_legal`) |
| ativo | CLOSED | sincronização (sem títulos) ou `CASE_MANAGE` (manual, com motivo) | tarefas em aberto são canceladas |

Durante uma retenção, mudanças de estágio (ex.: acordo formalizado) atualizam somente o estágio guardado
(`resume_status`). Toda mudança de status é auditada, registra `case.status_changed`/`case.closed` e publica
`CaseStatusChanged`.

## 2. Elegibilidade (RB02)

`EligibilityPolicy` (domínio puro) decide; `EligibilityEvaluator` reúne os fatos nos módulos de origem. O caso é elegível
quando, ao mesmo tempo:

| Regra | Fonte | Motivo de inelegibilidade |
| --- | --- | --- |
| caso ativo e fora de retenção | próprio caso | `CASE_CLOSED`, `CASE_SUSPENDED`, `CASE_LEGAL` |
| sem contestação bloqueante | contestações do módulo | `DISPUTE_OPEN` |
| carteira ativa e na vigência | `PortfolioUseCases#get().operable` | `PORTFOLIO_NOT_OPERABLE` |
| título vencido em aberto | posição do caso | `NO_OPEN_TITLES` (só suspensos), `TITLES_DISPUTED` (todos bloqueados) |
| devedor não anonimizado | `DebtorUseCases#get().status` | `DEBTOR_ANONYMIZED` |
| ao menos um canal sem restrição | `ContactGate#evaluate` por canal (PHONE, WHATSAPP, EMAIL, SMS), sem registrar tentativa | `CONTACT_RESTRICTED` |

Um canal é **restrito** quando a negação do porteiro decorre de restrição (`RESTRICTED_DEBTOR`, `RESTRICTED_CHANNEL`,
`DEBTOR_ANONYMIZED`) ou não tem fim previsto (`nextAllowedAt` nulo — ex.: política sem dias permitidos). Janela de horário,
feriado e limites de frequência **não** tornam o caso inelegível: são verificados pelo porteiro a cada contato
(`ContactGate#authorizeAndRecord`, responsabilidade de quem executa o contato — communication, telephony, campaign).

Os motivos e os canais contatáveis ficam persistidos no caso (`eligibility_reasons`, `contactable_channels`,
`eligibility_evaluated_at`) e aparecem na ficha e nas listagens. Recalcula ao consumir:

| Evento | Alcance |
| --- | --- |
| `ContactRestrictionChanged` | casos ativos do devedor (no credor, se a restrição for de um credor) — síncrono no consumo |
| `ContactPolicyPublished` | casos ativos do credor (ou de todos, política da assessoria) — job `collection.case_sweep` a partir de `effectiveFrom` |
| `PortfolioStatusChanged` | casos ativos da carteira — job `collection.case_sweep` |
| `TitleStatusChanged`, contestação, mudança de status | o próprio caso |

Mudança de elegibilidade publica `CaseStatusChanged` (campo `eligible`) e registra `case.eligibility_changed`.

## 3. Filas, atribuição e dono do atendimento

### 3.1 Filas

`CollectionQueue`: critérios opcionais (credor, carteira, faixa de atraso, faixa de saldo, faixa de score), prioridade,
ordem de entrega (`OLDEST_OVERDUE`, `HIGHEST_BALANCE`, `LOWEST_BALANCE`, `LEAST_RECENTLY_WORKED`, `HIGHEST_SCORE`),
estratégia (`PULL`, `ROUND_ROBIN`, `LEAST_LOADED`), capacidade por operador e equipes que a atendem (vazio = qualquer
operador). O caso pertence à **primeira fila ativa por prioridade** (empate pelo código) cujos critérios satisfaz
(`QueueRouter`). Criar/alterar/ativar/desativar fila reprocessa o roteamento dos casos ativos (`collection.case_sweep`).

### 3.2 Próximo caso

`POST /next-case[?queueId=]` (`CASE_WORK`):

1. caso do próprio operador com próxima ação vencida (retorno combinado/atrasado), o mais antigo primeiro;
2. senão, nas filas atendidas pelas equipes do operador (ou na informada), em ordem de prioridade, o primeiro caso
   **disponível**: ativo e trabalhável (OPEN, IN_NEGOTIATION, PROMISE), elegível, sem operador, dono `QUEUE` (não está
   com o assistente) e sem próxima ação futura; retornos da fila já vencidos vêm antes da ordem da fila;
3. seleção com `SELECT … FOR UPDATE SKIP LOCKED` (`JdbcCaseSelector`): pedidos simultâneos nunca recebem o mesmo caso
   e não esperam uns pelos outros; a capacidade por operador da fila é respeitada (`queue.capacity_reached` com fila
   informada).

### 3.3 Distribuição, redistribuição e atribuição manual

* `POST /queues/{id}/distribute` (`CASE_ASSIGN`): bloqueia até `limit` casos disponíveis (mesma seleção) e planeja com
  `CaseDistributor` — rodízio que continua após o último contemplado (`round_robin_cursor`) ou menor carga —, respeitando
  o escopo de credores e a capacidade de cada membro (operadores ativos com `CASE_WORK` das equipes da fila).
* `POST /redistributions` (`CASE_ASSIGN`): casos ativos de um operador (no escopo de quem executa) para outros operadores
  por menor carga, ou de volta às filas; casos sem destino possível voltam à fila.
* `POST /cases/{id}/assign` (`CASE_ASSIGN`): a operador ativo com `CASE_WORK` e acesso ao credor, ou a uma equipe.

### 3.4 Dono do atendimento (compatível com `comunicacao.md` §2 e `assistente.md`)

| Dono | Significado no caso | Como chega |
| --- | --- | --- |
| `NONE` | sem fila e sem atendimento | abertura sem fila correspondente; encerramento |
| `QUEUE` | aguardando um humano na fila/equipe | roteamento; devolução; atribuição à equipe |
| `USER` | operador responsável | próximo caso; distribuição; atribuição; operador que registra interação num caso sem operador; `take-over` |
| `ASSISTANT` | o assistente conduz | `POST /cases/{id}/assistant` (o responsável ou `CASE_ASSIGN`); conversas devolvidas ao assistente (communication) |

Transferência do assistente para humanos: `CaseWorkUseCases#release` aceita caso com dono `ASSISTANT` (o próprio
assistente, sistema ou `CASE_ASSIGN`), devolvendo-o à fila (`RELEASE`). As conversas do módulo communication seguem o
dono do caso (ver `comunicacao.md` §3).

Quem pode trabalhar o caso: o responsável; qualquer operador quando o caso está sem operador (passa a ser o
responsável — modo `INTERACTION` ou `TAKE_OVER` se estava com o assistente); quem tem `CASE_ASSIGN`; atores de
sistema/integração. Caso de outro operador → `409 case.assigned_to_other`.

### 3.5 Histórico de atribuição com vigência

`case_assignment` guarda períodos `[valid_from, valid_to)`: cada mudança de dono (tipo, operador, equipe ou fila) encerra o
período vigente e abre outro com o modo (`OPENED`, `ROUTING`, `NEXT_CASE`, `MANUAL`, `DISTRIBUTION`, `REDISTRIBUTION`,
`RELEASE`, `INTERACTION`, `ASSISTANT_HANDOFF`, `TAKE_OVER`, `CLOSED`), o dono anterior e o motivo. Índice único garante um
único período vigente por caso; o banco só permite gravar `valid_to` uma vez (privilégio de coluna + gatilho
`case_assignment_close_only`). "Quem era o responsável no instante T" é consulta direta
(`CaseAssignmentRepository#findAt`). Mudança de responsável humano ou equipe publica `CaseAssignmentChanged` e
registra `case.assigned`.

## 4. Interações e tabulações

`Interaction` é append-only (gatilho e privilégio): canal, sentido (`OUTBOUND`/`INBOUND`), tabulação (código e flag de
contato efetivo copiados no registro), observação criptografada (AES-GCM, `note_encrypted`), contato usado, início/fim e
duração, operador, tipo de ator e origem (`MANUAL`, `TELEPHONY`, `COMMUNICATION`, `ASSISTANT`, `SYSTEM`). Integrações
informam `externalRef`: reenvio devolve a interação já registrada (`uk_interaction_external`).

Tabulações por assessoria, com flags: `effectiveContact` (CPC), `promise` (exige valor e data), `callback` (exige data e
hora do retorno), `requiresNote`. Promessa e retorno são exclusivos entre si; promessa pressupõe contato efetivo. Catálogo
padrão (14 tabulações) semeado no provisionamento (`TenantProvisioned`) e pela `V0601` nas assessorias existentes;
`MESSAGE_SENT` e `MESSAGE_RECEIVED` (não efetivas, registradas automaticamente pelo communication com origem
`COMMUNICATION`) chegam às existentes pela `V1001`.

Registrar uma interação conclui, na mesma transação, os retornos em aberto do caso cujo horário já chegou (com a
tolerância antes do horário) ou a tarefa indicada em `completesTaskId`.

## 5. Retornos (callbacks) e tarefas

Data e hora informadas e exibidas no fuso `TimeConfig.BUSINESS_ZONE` (`LocalDateTime` na API), gravadas em UTC. A
tolerância da assessoria (`callbackToleranceMinutes`, padrão 30) é fixada no agendamento (`tolerance_until`).

| Situação | Quando | Evento (`CallbackStatusChanged.status`) |
| --- | --- | --- |
| agendado / reagendado | interação com tabulação `callback`, `POST /cases/{id}/tasks`, `/reschedule` | `SCHEDULED` |
| cumprido | concluído até `tolerance_until` | `KEPT` (`callback.kept`) |
| atrasado | job `collection.callback-missed` (ShedLock, a cada 5 min) após a tolerância | `MISSED` (`callback.missed`) |
| cumprido com atraso | concluído depois de atrasado | `COMPLETED_LATE` |
| cancelado | manual ou encerramento do caso | `CANCELLED` |

A próxima ação do caso (`next_action_at`) é a tarefa em aberto mais próxima; atrasada, fica no passado e o caso vem
primeiro no "próximo caso" do responsável. Sem tarefa e com promessa pendente, é o dia seguinte ao fim da tolerância da
promessa (até lá o caso aguarda o pagamento fora da fila).

## 6. Promessa de pagamento simples

`PaymentPromise` (`PENDING → KEPT | BROKEN | CANCELLED`): valor (≤ saldo em aberto) e data (até `promiseMaxDays`, padrão
30), registrada por interação com tabulação `promise`; uma pendente por caso (nova substitui a anterior, acordo
formalizado também a cancela). `breaks_after = data + promiseToleranceDays` (padrão 2), fixado no registro.

* **Cumprimento**: `PaymentConfirmed` **sem acordo** (`agreementId` nulo — pagamento de acordo pertence ao acordo) do
  mesmo devedor e credor, na carteira do caso (a do evento ou, sem ela, a dos títulos alocados), feito a partir do registro
  da promessa; idempotente por pagamento; cumprida quando o valor confirmado acumulado alcança o prometido.
* **Estorno**: `PaymentReversed` retira o pagamento; promessa cumprida volta a `PENDING` (e o caso a `PROMISE`, se ativo).
* **Quebra**: job diário `collection.daily` (00:10 no fuso de operação) quebra as pendentes com `breaks_after` no passado.

Publica `PromiseStatusChanged` (registrada, cumprida, quebrada, cancelada, reaberta).

## 7. Contestações (F21)

`Dispute` sobre um ou mais títulos em cobrança do caso (vazio = todos), com tipo, origem, descrição criptografada,
valor contestado, prazo de resposta (informado ou `disputeResponseDays`, padrão 10) e `blocksCollection` (padrão sim).

* Abrir: `DebtLedgerPort#markDisputed` (títulos fora de réguas e negociações), caso em `DISPUTED`, elegibilidade
  `DISPUTE_OPEN` — **suspensão de contato** até o encerramento; `dispute.opened`.
* Evidências: PDF, imagem, áudio, texto ou e-mail até 20 MB, no máximo 20 por contestação, em `FileStorage`
  (`EVIDENCE`) com SHA-256; append-only; download registra `FILE_DOWNLOADED`; arquivo de transação desfeita é removido.
* Análise (`UNDER_REVIEW`), resolução (`UPHELD`, `REJECTED`, `PARTIALLY_UPHELD`) ou desistência (`WITHDRAWN`) com parecer:
  `DebtLedgerPort#clearDispute`, retirada da retenção (se não houver outra bloqueante) e reavaliação; `dispute.resolved`.
  O efeito financeiro de uma contestação procedente (cancelar/ajustar o título) é ação explícita no módulo debt.
* Prazo: o job diário marca `overdue` (WARN + `collection.disputes.overdue` para alarme).

Publica `DisputeStatusChanged` em cada mudança.

## 8. Ficha do caso

`GET /cases/{id}/sheet`: caso (posição, elegibilidade e motivos, dono, próxima ação), devedor e contatos **mascarados**
(`DebtorSheetQueries`), títulos da carteira, restrições de contato vigentes que alcançam o credor
(`ContactRestrictionUseCases#search`), contestações abertas, promessa pendente, tarefas em aberto, últimas 10 interações e
demais casos do devedor no escopo.

## 9. Eventos de integração publicados

| Evento | Quando | Campos relevantes |
| --- | --- | --- |
| `CaseStatusChanged` | abertura, mudança de status, mudança de elegibilidade | construtor completo: `assigneeId`, `teamId`, `eligibleBalance` (saldo elegível se elegível, senão zero) |
| `CaseAssignmentChanged` | responsável humano ou equipe mudou | `previousAssigneeId`, `assigneeId`, `teamId`, `eligibleBalance` |
| `CaseBalanceChanged` (**novo**, 23/10/2026) | só o saldo elegível atribuível mudou (pagamento, carga, baixa, contestação), sem mudança de status, elegibilidade ou responsável | `assigneeId`, `teamId`, `eligible`, `previousEligibleBalance`, `eligibleBalance` |
| `InteractionRecorded` | interação registrada | construtor completo: `direction`, `portfolioId`; `occurredAt` = início da interação |
| `CallbackStatusChanged` (**novo**) | retorno agendado/cumprido/atrasado/cumprido com atraso/cancelado | `assigneeId`, `teamId`, `dueAt`, `toleranceUntil`, `status`, `completedBy` |
| `PromiseStatusChanged` (**novo**) | promessa registrada/cumprida/quebrada/cancelada/reaberta | `operatorId`, `amount`, `paidAmount`, `dueDate`, `breaksAfter`, `previousStatus`, `status` |
| `DisputeStatusChanged` | contestação aberta, em análise, resolvida, retirada | `titleIds`, `status`, `blocksCollection` |

**Incentive.** `eligibleBalance` em `CaseStatusChanged`, `CaseAssignmentChanged` e `CaseBalanceChanged` é o saldo elegível
**atribuível** (zero quando o caso está inelegível ou encerrado) — o denominador da taxa de recuperação do negociador.
Correção de 23/10/2026 (bug no produtor): mudanças só de saldo não publicavam evento, e o incentive ficava com o saldo do
último status/atribuição; `CaseRecorder.commit` passou a publicar `CaseBalanceChanged` nesse caso (teste:
`CollectionIncentiveIndicatorsIT`). Os indicadores "retornos cumpridos" (`effort.callbacksKeptRate`) e "promessas
cumpridas" (`conversion.promisesKeptRate`) consomem `CallbackStatusChanged` e `PromiseStatusChanged` desde 23/10/2026;
fórmulas e datas de referência em `remuneracao-variavel.md` §10.6. A reatribuição de retorno (`reassign`) não publica
evento (pendência registrada lá).

## 10. Eventos consumidos

`TitleStatusChanged`, `PortfolioLoaded`, `PortfolioStatusChanged`, `ContactRestrictionChanged`, `ContactPolicyPublished`,
`PaymentConfirmed`, `PaymentReversed`, `AgreementStatusChanged`, `TenantProvisioned` — consumidores
`collection.<reacao>` idempotentes (`shared.consumed_event`), com lock consultivo por devedor antes de carregar o caso (os
comandos de usuário obtêm o mesmo lock), o que evita abertura duplicada e colisões de lock otimista entre consumo e
comando.

**Uso pelo negotiation** (`negociacao-e-alcadas.md` §10): a elegibilidade para negociar usa `CaseUseCases#findActive` (caso fora de
suspensão/jurídico/encerramento) e `DisputeUseCases#listByCase` (contestação impeditiva aberta ou em análise cobre o título); o
acordo guarda o `caseId` e a equipe do caso. `DebtorAnonymized` (novo, publicado pelo debt) ainda não é consumido aqui
(observações já são criptografadas; apagá-las na anonimização é pendência do módulo).

## 11. Segurança, escopo e auditoria

* Permissões: `CASE_VIEW`, `CASE_WORK`, `CASE_ASSIGN`, `CASE_MANAGE` (**nova**: suspender, jurídico, encerrar),
  `QUEUE_MANAGE` (filas e parâmetros), `TABULATION_MANAGE` (**nova**), `INTERACTION_REGISTER`, `TASK_MANAGE`,
  `DISPUTE_MANAGE`. Perfis padrão: operador (trabalho), supervisor (+ atribuição, gestão de casos, filas, contestações),
  gestor (+ tabulações). `V0601` concede as novas aos perfis existentes.
* Escopo por credor em todos os casos de uso (`AccessScope`: 404 leitura, 403 comando, listagens filtradas); filas e
  tabulações/parâmetros sem credor exigem acesso a todos os credores.
* RLS forçado em todas as tabelas (filhas via linha pai); interações, evidências e histórico de atribuição imutáveis;
  casos, tarefas, promessas, contestações, filas e tabulações sem `DELETE`.
* Auditoria (antes/depois) de toda mudança de caso, atribuição, fila, tabulação, parâmetro, tarefa, promessa e contestação.

## 12. Logs e métricas

Eventos de negócio da linha "Operação" de `docs/tecnico/logs-e-metricas.md` §3.3, sempre com credor, carteira, devedor, caso,
`daysOverdue`, `overdueBand` e `valueBand`; métricas `collection.*` em `docs/tecnico/logs-e-metricas.md` §5. Sem dado pessoal (observações e
descrições só no banco, criptografadas).

## 13. API REST (`/api/v1/collection`)

| Recurso | Métodos |
| --- | --- |
| `/cases` | `GET` (filtros: credor, carteira, devedor, status, fila, `assignee=me|id`, dono, elegível) |
| `/cases/{id}` · `/cases/{id}/sheet` · `/cases/{id}/assignments` | `GET` |
| `/cases/{id}/suspend` · `/resume` · `/legal` · `/legal-return` · `/close` · `/refresh` | `POST` |
| `/cases/{id}/assign` · `/release` · `/take-over` · `/assistant` | `POST` |
| `/next-case` (200 caso / 204 vazio) · `/my-cases` | `POST` · `GET` |
| `/queues` · `/queues/{id}` · `/queues/{id}/summary` · `/queues/{id}/activate|deactivate` · `/queues/reroute` · `/queues/{id}/distribute` | CRUD + ações |
| `/redistributions` | `POST` |
| `/tabulations` · `/tabulations/{id}` · `/activate|deactivate` | CRUD + ações |
| `/cases/{id}/interactions` | `GET`, `POST` (201; 200 se reenvio de integração) |
| `/cases/{id}/tasks` · `/agenda` · `/tasks/{id}/complete|cancel|reschedule|reassign` | `GET`, `POST` |
| `/cases/{id}/promises` · `/promises/{id}` · `/promises/{id}/cancel` | `GET`, `POST` |
| `/cases/{id}/disputes` · `/disputes` · `/disputes/{id}` · `/review|resolve|withdraw` · `/evidences` · `/evidences/{eid}` | `GET`, `POST` (multipart) |
| `/settings` | `GET`, `PUT` |

## 14. Decisões

| # | Decisão | Alternativa | Motivo |
| --- | --- | --- | --- |
| 1 | Caso por (devedor, carteira) | por (devedor, credor) | carteira define vigência, regras contratuais e filas; credor fica implícito |
| 2 | Retenções guardam o estágio (`resume_status`) com prioridade LEGAL > SUSPENDED > DISPUTED | pilha de retenções | simples, determinístico; ao sair do jurídico uma suspensão anterior não é restaurada (documentado) |
| 3 | Elegibilidade sem janela/limites; canais restritos via `ContactGate#evaluate` | porta nova no compliance só de restrições | reutiliza o porteiro existente sem mudar o compliance; regras temporais pertencem ao momento do contato |
| 4 | Registro de interação não chama `authorizeAndRecord` | autorizar no registro | o registro documenta um fato já ocorrido; a autorização é de quem executa o contato (telefonia, comunicação, UI antes de ligar) |
| 5 | Lote de carga processado por job com paginação por chave (nova consulta somente-leitura no debt) | sincronizar no consumo do evento | cargas grandes sem transação longa, retomável |
| 6 | Lock consultivo por devedor em consumidores e comandos | só lock otimista | eventos do mesmo devedor chegam em paralelo; evita casos duplicados e falhas de versão |
| 7 | "Próximo caso" com `FOR UPDATE SKIP LOCKED` em SQL nativo | lock otimista com novas tentativas | sem disputa nem espera entre operadores |
| 8 | Histórico de atribuição com vigência, só `valid_to` atualizável | eventos de mudança sem vigência | consulta direta do responsável no instante (crédito por carteira) mantendo imutabilidade |
| 9 | Promessa cumprida por pagamento sem acordo na carteira do caso | exigir id da promessa no pagamento | o pagamento não conhece a promessa; o vínculo natural é devedor/credor/carteira |
| 10 | Retorno atrasado continua com o operador (próxima ação no passado) | devolver à fila | preserva o relacionamento; o supervisor redistribui se necessário |
| 11 | Parâmetros por assessoria com padrões em `app.collection.defaults`, fixados nos registros | só configuração global | operação varia por assessoria; compromissos assumidos não mudam com a configuração |
| 12 | Permissões novas `CASE_MANAGE` e `TABULATION_MANAGE`; parâmetros sob `QUEUE_MANAGE` | reutilizar `CASE_ASSIGN` | separação entre distribuir e encerrar/suspender casos |
| 13 | Eventos novos `CallbackStatusChanged` e `PromiseStatusChanged` com status | um evento por situação | contrato menor, mesmo padrão de `DisputeStatusChanged` |
| 14 | Incentive não passa a consumir neste módulo | alterar o incentive | exige migration fora da faixa do collection; contrato e fórmulas documentados (§9) |
