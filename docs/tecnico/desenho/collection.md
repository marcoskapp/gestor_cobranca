# Desenho — módulo `collection` (operação de cobrança)

Escopo: M03, F06 (caso de cobrança), F07 (filas, atribuição e distribuição), F21 (contestações), RB02 (elegibilidade).
Schema `collection`, migrations V0600–V0699 (`V0600` tabelas, `V0601` permissões e catálogo padrão de tabulações,
`V0603` ação automática da tabulação).
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
| empresa fora de recuperação judicial, extrajudicial ou falência | `InsolvencyUseCases#isUnderInsolvency` (debt) | `DEBTOR_INSOLVENCY` |
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
| `DebtorInsolvencyChanged` | casos ativos do devedor em todos os credores — síncrono no consumo (`collection.debtor-insolvency`), relendo o estado no debt sob o lock do devedor |
| `TitleStatusChanged`, contestação, mudança de status | o próprio caso |

Mudança de elegibilidade publica `CaseStatusChanged` (campo `eligible`) e registra `case.eligibility_changed`.

## 3. Filas, atribuição e dono do atendimento

### 3.1 Filas

`CollectionQueue`: critérios opcionais (credor, carteira, faixa de atraso, faixa de saldo, faixa de score, **só devedores
com marcador de vulnerabilidade** — `vulnerable_only`, [conformidade.md](conformidade.md) §1.3 —, **só devedores com
contato novo** recebido na carga ainda sem tentativa — `new_contact_only`, [contatos-recebidos.md](contatos-recebidos.md)
§4 —, **tipo de pessoa** do devedor — `person_type` `INDIVIDUAL`/`COMPANY`, [cobranca-b2b.md](cobranca-b2b.md) §9),
prioridade,
ordem de entrega (`OLDEST_OVERDUE`, `HIGHEST_BALANCE`, `LOWEST_BALANCE`, `LEAST_RECENTLY_WORKED`, `HIGHEST_SCORE`),
estratégia (`PULL`, `ROUND_ROBIN`, `LEAST_LOADED`), capacidade por operador e equipes que a atendem (vazio = qualquer
operador). O caso pertence à **primeira fila ativa por prioridade** (empate pelo código) cujos critérios satisfaz
(`QueueRouter`). Criar/alterar/ativar/desativar fila reprocessa o roteamento dos casos ativos (`collection.case_sweep`).

O caso guarda os atributos do devedor usados nesses critérios (`V0610`): `debtor_person_type` (lido na abertura por
`DebtorSegmentationQueries` do debt; os casos existentes foram preenchidos pela migration) e `new_contact_until` (fim da
novidade dos contatos, lido na abertura e mantido pelo evento `DebtorContactNoveltyChanged`). O critério de contato novo
compara `new_contact_until` com o instante do roteamento: janela vencida já não atende, mesmo antes da varredura diária do
debt republicar a novidade. Tipo de pessoa exigido só seleciona casos com o tipo conhecido.

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
  por menor carga, ou de volta às filas; casos sem destino possível voltam à fila. Destino aceito: ativo, com
  `CASE_WORK`, diferente da origem e com acesso a ao menos um credor do escopo de quem redistribui
  (`CaseWorkers#canReceiveRedistribution`; fora disso `422 case.assignee_invalid`). A origem pode ser qualquer usuário
  (inclusive inativo — carteira de quem saiu); só os casos no escopo de quem executa são movidos.
* `POST /cases/{id}/assign` (`CASE_ASSIGN`): a operador ativo com `CASE_WORK` e acesso ao credor
  (`CaseWorkers#canWork`, a mesma regra de `CaseAssigneeUseCases#isCaseWorker`), ou a uma equipe.

### 3.3.1 Usuários atribuíveis (lista = gravação)

`GET /assignable-users?purpose=…` (`CaseAssigneeUseCases`, `CaseAssigneeService`) alimenta os campos de responsável da
cobrança no lugar do cadastro de usuários (`/users`, que exige `USER_VIEW` e traz e-mail). Devolve só `id`, `name` e
`active`, paginado (até 50 por página, até 50 `ids` na resolução do que já está escolhido), busca por nome sem
diferenciar maiúsculas e acentos (`NameSearch`), e cada consulta registra `USER_DIRECTORY_QUERIED` no log de acesso
(`resource=collection_assignee`, `purpose`, `caseId`/`creditorId` quando houver, `returned` — nunca o texto buscado).
A elegibilidade é **a mesma função** que o serviço aplica ao gravar, com a mesma permissão e o mesmo escopo:

| `purpose` | Quem aparece | Regra única (lista e gravação) | Permissão / escopo |
| --- | --- | --- | --- |
| `CASE_WORKER` (+ `caseId`) | atribuir caso; responsável da tarefa (agendar e `reassign`) | ativo, `CASE_WORK` e acesso ao credor do caso — `CaseWorkers#canWork` em `CaseWorkService.assign` e `TaskService.requireAssignee` | `CASE_ASSIGN`; caso visível (404 fora do escopo) |
| `CASE_REDISTRIBUTION_SOURCE` | "de" da redistribuição de casos | quem tem casos ativos atribuídos nos credores do escopo de quem consulta, **ativo ou não** | `CASE_ASSIGN` |
| `CASE_REDISTRIBUTION_TARGET` (+ `fromUserId` opcional) | "para" da redistribuição de casos | `CaseWorkers#canReceiveRedistribution` | `CASE_ASSIGN` |
| `DISPUTE_POOL_MEMBER` (+ `creditorId`; vazio = grupo geral) | incluir analista no grupo de distribuição | `DisputeAssignments#isEligibleForPool` (ativo, `DISPUTE_WORK`/`DISPUTE_MANAGE`, acesso ao credor do grupo) e ainda fora do grupo (`409 dispute_pool.duplicated` na gravação) | `DISPUTE_MANAGE`; credor no escopo, grupo geral exige todos os credores |
| `DISPUTE_REDISTRIBUTION_SOURCE` | "de" da redistribuição de contestações | responsáveis por contestações abertas nos credores do escopo, **ativos ou não** | `DISPUTE_MANAGE` |
| `DISPUTE_REDISTRIBUTION_TARGET` (+ `fromUserId` opcional) | "para" da redistribuição de contestações | `DisputeAssignments#isEligibleForAll`: elegível em todos os credores das contestações abertas da origem (no escopo), diferente da origem; `DisputeAssignments.redistribute` confere antes de mover (`422 dispute.assignee_invalid`) | `DISPUTE_MANAGE` |

Inativos só aparecem como origem de redistribuição (rótulo "(inativo)" na tela). Na tela, o "para" é recalculado quando
o "de" muda e o analista do grupo quando o credor muda (a escolha anterior é limpa). A atribuição de conversa
(communication) usa `CaseAssigneeUseCases#isCaseWorker` na sua regra (`comunicacao.md` §3).

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

**Início, fim e duração.** `startedAt` e `endedAt` vêm do cliente (na estação, o cronômetro do atendimento); sem
`startedAt`, vale o instante do registro. O servidor recusa início no futuro (`interaction.started_in_future`, folga de
2 minutos), início com mais de 7 dias (`interaction.started_too_old`), fim antes do início (`interaction.period_invalid`)
e fim no futuro (`interaction.ended_in_future`, mesma folga — sem ela, um relógio adiantado inflaria a duração).
Essas regras ficam no `InteractionService` (dependem do relógio do servidor), não na entidade.
`duration_seconds` é derivada de `endedAt − startedAt` e hoje só aparece no histórico do caso; **nenhum indicador a
consome**: o TMA da produtividade e a remuneração variável usam o tempo de conversação das chamadas da telefonia
(`CallCompleted.durationSeconds`/`talk_seconds`), e a view `analytics.src_interaction` não expõe a duração. A causa da
duração subestimada (revisão de produto de 2026-11, J1) estava na estação, que recriava o início ao voltar de outra tela; ela
passou a guardar o atendimento por caso (§8.2).

### 4.1 Ação automática da tabulação (decisões 776–780)

O fato relatado no atendimento produz o efeito devido **no próprio registro**, sem depender de um segundo passo manual.
A tabulação tem `followUp` (`TabulationFollowUp`) e, para contestação, um tipo pré-selecionado (`disputeType`, nulo = o
operador escolhe). `TabulationFollowUps` valida os dados antes de gravar a interação e executa a ação depois de gravar a
interação e o caso, **na mesma transação**: se a ação falha, nada é registrado.

| Ação | Catálogo padrão | Efeito | Já em vigor (`ALREADY_IN_EFFECT`) |
| --- | --- | --- | --- |
| `OPEN_DISPUTE` | `ALREADY_PAID` (tipo `ALREADY_PAID`), `DISPUTE` (operador escolhe o tipo) | contestação de origem `OPERATOR`, bloqueante, com os títulos em cobrança do caso (ou os informados em `followUp.titleIds`); a observação vira a descrição; o caso vai a `DISPUTED` (§7); prazos pelas regras de prazo (§7.2) e responsável pela distribuição configurada (§7.1, decisão 800) | já há contestação aberta ou em análise no caso: aponta a existente. Sem títulos em cobrança → `NO_TITLES` |
| `REPORT_DECEASED` | `DECEASED` | restrição de contato `DECEASED` do devedor em **todos os canais e credores** (compliance, `registerDeceasedReport`), origem `OPERATOR`, motivo fixo "aguarda comprovação"; elegibilidade do caso reavaliada no ato, os demais casos do devedor pelo `ContactRestrictionChanged` | restrição `DECEASED` vigente do devedor no mesmo alcance |
| `INVALIDATE_CONTACT` | `WRONG_CONTACT` | o contato usado na interação (`contactId`, obrigatório) passa a `INVALID` no debt com motivo fixo; discador e envios deixam de usá-lo (a elegibilidade do caso é por restrição de canal e não muda) | contato já inválido |

Regras da tabulação (domínio e `ck_tabulation_*`): ação exige observação e não se combina com promessa ou retorno;
`OPEN_DISPUTE` exige contato efetivo; `disputeType` só com `OPEN_DISPUTE`. Detalhes da ação (`followUp` no registro) só
com tabulação que tem ação; `OPEN_DISPUTE` sem tipo informado nem padrão → `interaction.dispute_type_required`.

A observação do operador fica só na interação (criptografada) e na descrição da contestação (também criptografada): o
motivo da restrição e da invalidação é um texto fixo, sem copiar o relato (em geral dado de terceiro, como quem atendeu).

Rastreio nos dois sentidos: a interação guarda a ação vigente no registro (`interaction.follow_up`, como o código e as
flags); a contestação guarda `origin_interaction_id` e a restrição, `compliance.contact_restriction.origin_interaction_id`
(`V0501`). Cada execução registra a auditoria `interaction.follow_up_executed` (ação, resultado, registro criado ou
existente), o evento de negócio `interaction.follow_up` e a métrica `collection.tabulation_follow_ups{action,outcome}`.
O `RecordResult` devolve `followUp` (ação, resultado, tipo e id do registro); a telefonia aceita `disputeType` na
tabulação do operador (`POST /telephony/calls/{id}/tabulation`) e repassa o contato da chamada.

Reenvio de integração com a mesma `externalRef` devolve a interação já registrada sem executar a ação de novo. As
integrações automáticas (telefonia sem operador, comunicação, assistente) usam tabulações sem ação; o assistente
transfere ao humano os sinais de contestação e falecimento (`assistente.md`).

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

**Agenda** (`GET /agenda`): cada tarefa traz `debtorId` (do caso) e `debtorName` — este só para quem pode ver devedores
(`CASE_VIEW` ou `DEBTOR_VIEW`; nome em uma consulta em lote `DebtorUseCases#names`, que omite devedores fora do escopo). O
escopo de credores entra na própria consulta (não é filtrado depois da página), então totais e paginação ficam corretos
para quem consulta a agenda de outro operador. Nas demais respostas `debtorName` é nulo (a ficha já traz o devedor).

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
  O efeito financeiro de uma contestação procedente (cancelar/ajustar o título) é ação explícita no módulo debt
  (ajuste sugerido, §7.5).
* Prazo: o job diário marca `overdue` (WARN + `collection.disputes.overdue` para alarme). O prazo de resposta e o
  interno vêm das regras de prazo (§7.2) quando não informados.

Publica `DisputeStatusChanged` em cada mudança.

**Contestação do portal (I14).** `DisputeIntakeUseCases#openFromPortal` (job `portal.dispute_intake`, ator de sistema)
abre, por caso ativo do devedor nas carteiras do pedido, a contestação com origem `DEBTOR`, bloqueante, descrição com o
protocolo e o relato, e **`opened_at` e base dos prazos = instante do pedido no portal**. Idempotente por
`dispute.origin_reference = portal_request:<id>` (índice único por caso); caso com contestação em aberto não ganha outra.
Resultado `OPENED`/`ALREADY_OPEN`/`NO_CASE`/`NO_TITLES` (métrica `collection.disputes.portal_intake`). Ver
[conformidade.md](conformidade.md) §2.

**Views.** `DisputeView.titles` traz os dados mínimos de cada título contestado (número, parcela, vencimento, valor original
e saldo), lidos no debt uma vez por devedor (`DebtTitleUseCases#listByDebtor`, com o escopo do ator; na ficha, dos títulos
já carregados). `EvidenceView.createdByName` é o nome de quem anexou (diretório do identity, só a própria assessoria).

### 7.1 Responsável, fila e distribuição

Regras de negócio em [negocio/contestacoes.md](../../negocio/contestacoes.md). Colunas `assignee_id`/`assigned_at` em
`collection.dispute`; histórico append-only `collection.dispute_assignment` (anterior, novo, modo `MANUAL|TAKE|AUTOMATIC|
REDISTRIBUTION|RELEASE`, motivo, quem). `DisputeAccess#requireWork`: `DISPUTE_MANAGE` trabalha qualquer uma;
`DISPUTE_WORK` só as próprias (sem responsável → `TAKE` implícito); outra pessoa → `409 dispute.assigned_to_other`.
Responsável elegível: ativo, com `DISPUTE_WORK`/`DISPUTE_MANAGE` e acesso ao credor (`UserDirectory`). Distribuição
`LEAST_LOADED` (`DisputeDistributor`): menos abertas, depois atribuição mais antiga, entre os membros de
`collection.dispute_pool_member` (gerais ou do credor). `POST /disputes/redistributions` move em lote (com motivo);
com destino informado, ele precisa ser elegível em todos os credores das contestações abertas da origem
(`DisputeAssignments#isEligibleForAll`). Regras únicas de elegibilidade (`isEligible`, `isEligibleForPool`,
`isEligibleForAll`) valem na gravação, em `assignee-options` e nas listas de §3.3.1.

### 7.2 Prazos

`internal_due_date` (≤ `response_due_date`) e `deadline_source` (`INFORMED|CREDITOR_TYPE|CREDITOR|TYPE|TENANT_DEFAULT`)
calculados na abertura por `DisputeDeadlines.resolve` sobre `collection.dispute_deadline_rule` (únicos por credor/tipo,
nulos = qualquer) e `collection.dispute_settings` (`DisputeSettings.Values.DEFAULTS`: manual, interno 7, credor 5,
lembrete 1, link 7). Nenhum prazo é pausado; "aguardando o credor" é `awaiting_creditor_since` (derivado do pedido em
aberto, sem status novo).

### 7.3 Canal formal com o credor

* Contatos: `organization.creditor_dispute_contact` (`CreditorDisputeContactUseCases#recipients`: contatos da carteira
  com PRIMARY ativo substituem os do credor).
* Pedido: `collection.dispute_creditor_request` (+ `_title`, `_evidence`), modelo do tipo (`DisputeTemplateProvider`,
  versionado em `collection.dispute_template`, append-only; padrão em `DisputeTemplates`; sem nome/documento do devedor).
* Link das evidências: token aleatório (só o SHA-256 em `collection.dispute_link_route`, tabela global sem RLS que só
  resolve o tenant; a leitura do pacote roda no tenant), validade `evidenceLinkDays`, até
  `app.collection.disputes.link-max-downloads` (20) downloads e `link-requests-per-minute` (30) por IP;
  `GET /api/v1/dispute-files/{token}` é `PublicEndpoint`; ZIP com as evidências escolhidas; `FILE_DOWNLOADED` no log de
  acesso; recusa → `DISPUTE_LINK_REJECTED`. Links vencidos são removidos pela manutenção diária. O envio do ZIP não
  segura transação (decisão 871): a contagem do download e os metadados das evidências são lidos em transações curtas,
  e a leitura do armazenamento e a escrita para o cliente anônimo (que pode ser lento) correm só com o tenant definido,
  sem conexão do banco presa (`DisputeWorkflowIT` confere que nenhuma escrita acontece com transação ativa).
* E-mail: `collection.dispute_mail` (`REQUEST|REMINDER|OVERDUE`, `QUEUED|SENT|FAILED|SKIPPED`) enviado pelo job
  `collection.dispute_mail` (`DisputeMailer` → `SmtpDisputeMailer`, `app.mail.from`, *reply-to* do responsável ou, com a
  leitura de e-mail ligada, o endereço próprio do pedido — §7.6), até
  `mail-max-attempts` (6) com espera crescente; falha definitiva marca o pedido `FAILED` (`DisputeMailFailures`,
  `REQUIRES_NEW`).
* Acompanhamento: `collection.dispute-follow-up` (cron `app.collection.disputes.follow-up-cron`, padrão `0 5 8 * * *`,
  ShedLock): lembrete até `reminderDaysBefore` antes do prazo pedido e cobrança no dia seguinte ao vencimento, uma vez
  cada.
* Resposta: `collection.dispute_creditor_response` (append-only, `MANUAL|PUBLIC_API|EMAIL`, posição, resumo criptografado);
  documentos manuais viram `dispute_evidence` com `creditor_response_id`. API pública em `publicapi` (escopos
  `disputes:read`/`disputes:write`, ator `INTEGRATION`).
* Documentos da API (decisões 814–819): tipo pelo conteúdo (`FileSignatures`: PDF, PNG, JPEG), até
  `app.collection.disputes.api-max-documents` (5) por resposta e `api-max-document-bytes` (10 MB) cada, gravados na
  categoria `QUARANTINE` em `collection.dispute_document_quarantine` (`V0605`) e verificados pelo job
  `collection.dispute_document_scan` (`MalwareScanner`, `app.storage.malware-scan.mode` = `disabled` | `clamd`; até
  `scan-max-attempts`, padrão 8). Limpo ou `NOT_SCANNED` → cópia para `EVIDENCE` e `DisputeEvidence.fromQuarantine`
  (append-only); infectado → conteúdo apagado após o commit, `MALWARE_DETECTED` no log de acesso; sem veredito →
  `FAILED` (`DisputeQuarantineFailures`, nunca liberado). `CreditorResponseView` traz `documentsInVerification` e
  `documentsRejected`.

### 7.4 Resposta ao devedor

`DisputeOrigin.expectsDebtorResponse` (DEBTOR, OPERATOR) → `debtor_notice_status = PENDING` na resolução.
`collection.dispute_debtor_notice` (canal `EMAIL|WHATSAPP|OTHER`, situação, texto criptografado). Envio pela porta SPI
`DisputeDebtorMessenger` (ADR-0010), implementada no communication (`DisputeNoticeMessenger` →
`MessageService#queueTransactional`), com `ContactGate` na categoria `TRANSACTIONAL` (ver compliance: não aplica
limites/intervalo da cobrança nem restrições `CREDITOR_REQUEST`; aplica as demais, janela e feriados). A situação
acompanha `MessageStatusChanged` (consumidor `collection.dispute-notice-status`; nunca regride).

### 7.5 Ajuste sugerido

`DisputeAdjustmentAction.suggestedFor(type, outcome)` (editável na resolução, `NONE` = sem ajuste);
`adjustment_status` `PENDING → DONE|DISMISSED`, `adjustment_baseline` = saldo dos títulos na resolução.
`DisputeAdjustments#reconcile` reage a `TitleStatusChanged` e à varredura diária: cancelados (cancelar), quitados
(pagamento) ou saldo ≠ base (corrigir). Dispensa com motivo (`POST /disputes/{id}/adjustment/dismiss`). O frontend leva
aos fluxos existentes (título com `?acao=cancelar&motivo=`; "Informar pagamento" com os títulos marcados; corrigir o
valor com `?acao=corrigir-valor&contestacao=<id>&motivo=`, a correção manual do debt — docs/negocio/correcao-de-valor.md).

### 7.6 Respostas do credor por e-mail

Decisões 820–836; operação em [email-entrada.md](../operacao/email-entrada.md). Migration `V0606`.

* **Endereço de resposta**: `DisputeCreditorChannel#send`, com `app.collection.inbound-mail.mode` ≠ `disabled`, gera o
  token (`newReplyToken`, 130 bits base32), grava `contestacao+<token>@<domain>` criptografado em
  `dispute_creditor_request.reply_address_encrypted` e a rota em `collection.dispute_reply_route` (global, sem RLS; só
  o SHA-256, tenant, pedido e validade = prazo pedido + `route-validity`). `DisputeMailJob` usa esse endereço como
  *reply-to* e acrescenta a orientação ao credor; o responsável vai sempre em cópia.
* **Entrada** (`InboundMailUseCases#receive`, idempotente pelo id do provedor): adaptadores em
  `collection.infrastructure.inbound` — `SesInboundMailConsumer` (modo `ses`: SQS em leitura longa, envelope SNS com a
  notificação do SES e os veredictos, e-mail lido do bucket; só apaga da fila depois do registro) e
  `LocalDirectoryInboundMailSource` (modo `local`, pasta de `.eml`, veredictos por `X-Sandbox-*`, recusado no perfil
  prod). `InboundMailService` acha o endereço entre os destinatários do envelope (ou dos cabeçalhos no modo local),
  resolve a rota e registra no tenant (`TenantExecution`, ator de sistema); sem rota → `collection.inbound_mail_unrouted`.
* **Leitura** (`InboundMailParser` → `JakartaInboundMailParser`): Jakarta Mail sem rede; texto da parte `text/plain` ou
  HTML → texto com `jsoup` `Safelist.none()`; anexos por disposição/nome de arquivo (profundidade máxima 8); nunca lança
  por conteúdo inválido (`malformed`).
* **Registro** (`DisputeMailReplyIntake`): original na categoria `INBOUND_MAIL` (SHA-256), remetente e assunto
  criptografados (+ hash do remetente para o limite por hora), triagem por `MailReplyScreening` (domínio) e
  `collection.dispute_mail_reply` (`PENDING_CONFIRMATION|QUARANTINED|CONFIRMED|DISMISSED`, motivo, veredictos). Gatilho
  `dispute_mail_reply_guard`: sem remoção, conteúdo imutável, decisão única. Aprovado → anexos por
  `DisputeEvidenceStore#quarantineMailAttachment` (mesma quarentena e job `collection.dispute_document_scan`;
  `dispute_document_quarantine.mail_reply_id`; evidência com `dispute_evidence.mail_reply_id`).
* **Decisões** (`DisputeMailReplyUseCases`, `DisputeMailReplyController`):
  `GET /collection/disputes/{id}/mail-replies`, `POST …/{replyId}/confirm` (posição + resumo →
  `DisputeCreditorChannel#respond` com origem `EMAIL` e a data do e-mail), `POST …/dismiss`, `POST …/release`
  (`DISPUTE_MANAGE`, só motivos liberáveis; processa os anexos relendo o original e conferindo o SHA-256),
  `GET …/original` (.eml, `FILE_DOWNLOADED`), `GET /collection/dispute-mail-quarantine` (`DISPUTE_MANAGE`). Fila:
  `mailReplyPending` em `GET /collection/disputes`.

### 7.7 Pendências e indicadores

`PendingWorkSource`: `collection.disputes_mine`, `collection.disputes_due` (supervisão: sem responsável ou vencidas),
`collection.dispute_debtor_notices`, `collection.dispute_adjustments`, `collection.dispute_mail_replies` (respostas por
e-mail a confirmar; responsável, ou todas para a gestão) e `collection.dispute_mail_quarantine` (gestão). Analytics: visões `src_dispute` e
`src_dispute_creditor_request` (`security_invoker`), `GET /analytics/disputes` e exportação `disputes`
([indicadores.md §7](../../negocio/indicadores.md#7-contestações)).

## 8. Ficha do caso

`GET /cases/{id}/sheet`: caso (posição, elegibilidade e motivos, dono, próxima ação), devedor e contatos **mascarados**
(`DebtorSheetQueries`), títulos da carteira, restrições de contato vigentes que alcançam o credor
(`ContactRestrictionUseCases#search`), contestações abertas, promessa pendente, tarefas em aberto, últimas 10 interações e
demais casos do devedor no escopo.

Na estação, a ficha mostra também um aviso compacto dos pagamentos externos do devedor no credor que aguardam a aprovação
de outra pessoa ou foram recusados recentemente — lido do payment (`GET /payment/review-status/debtors/{id}`, `AGREEMENT_VIEW`),
não da ficha, porque o collection não depende do payment (decisão 436).

A aba **Conversas** (`Alt + 7`, só com `INBOX_VIEW`) lista as conversas do caso em qualquer situação, lidas do
communication (`GET /communication/conversations?caseId=&status=ALL`), e o botão **Mensagem** (`Alt + W`,
`MESSAGE_SEND`) inicia o contato ativo por WhatsApp, SMS ou e-mail (`comunicacao.md` §4). As mensagens enviadas e
recebidas já entram no histórico como interações `MESSAGE_SENT`/`MESSAGE_RECEIVED` (`comunicacao.md` §8, decisões 8 e 9);
a estação não cria interação própria para o envio.

A aba **Acordos** (`Alt + 8`, no menu "Mais", só com `AGREEMENT_VIEW`) é o histórico de acordos do caso, com resumo
e link para cada acordo numa aba nova; a aba Negociação mostra só os acordos em vigor (12/11/2026,
[acordos-ficha-lateral-e-atencao-especial.md](acordos-ficha-lateral-e-atencao-especial.md) §A1).

### 8.1 "Posso contatar agora?" (operador)

`GET /cases/{id}/contact-availability[?channel=&contactId=]` (`CASE_WORK`), `ContactAvailabilityQueries`. Fica no
collection (e não no compliance) porque parte do caso: devedor e credor vêm do caso visível ao ator (404 fora do escopo),
sem que o operador possa combinar devedor e credor arbitrários, e a resposta combina a decisão do porteiro com a
elegibilidade do caso (respeita o grafo collection → compliance).

* Para cada canal (PHONE, WHATSAPP, SMS, EMAIL, ou o pedido) chama `ContactGate#evaluate` **sem registrar tentativa**, no
  instante atual, com origem `OPERATOR`: restrições, política vigente (credor ou assessoria), janela, feriados, limites
  diário/semanal e intervalo mínimo.
* `contactId` (opcional) é um contato utilizável do devedor; o valor é descriptografado só para o porteiro calcular o
  blind index (restrição daquele número/e-mail) e nunca volta na resposta. Contato de outro devedor ou inválido →
  `422 case.contact_invalid`.
* Resposta: `caseEligible` e `caseReasons` (elegibilidade persistida sem `CONTACT_RESTRICTED`, que é detalhado por canal) e,
  por canal, `allowed` (porteiro permite **e** caso elegível), `reasons` (motivos do porteiro), `nextAllowedAt` (próximo
  instante permitido pelo porteiro; nulo se permitido ou sem fim previsto) e `contactId`. Não expõe a política (janelas,
  limites, intervalos).
* A estação consulta ao abrir o caso e a cada minuto; a tela não calcula janelas. Métrica: a do porteiro,
  `compliance.contact_gate.decisions{operation=evaluate}`.
* `POST /compliance/contact-gate/evaluate` (devedor + credor informados) continua existindo para outros usos; a estação
  não o usa.

### 8.2 Atendimento por caso na estação (frontend)

`AttendanceService` (`features/collection/station`, `providedIn: 'root'`) guarda, por caso, o início da contagem, o
rascunho do registro de interação e a aba ativa (também em `?aba=` na URL). Abrir a ficha do devedor ou o simulador —
que abrem num painel lateral dentro da estação — ou sair para outra tela e voltar retoma o mesmo atendimento. Abrir outro
caso encerra a contagem do anterior (o tempo em outro caso não conta) e guarda o rascunho dele; registrar a interação
limpa o rascunho e reinicia a contagem; devolver à fila descarta. Atendimento parado há mais de 30 minutos (sem abrir,
digitar, trocar de aba nem sair da estação; `ATTENDANCE_IDLE_MS`) recomeça a contagem ao voltar — o rascunho continua —
para que pausa ou fim de turno não entrem na duração. O simulador recebe o canal do atendimento no momento em que o
painel abre. Só em memória (no máximo 20 casos, limpos na troca de
usuário): a observação pode conter dado pessoal e não vai para `localStorage`/`sessionStorage`; recarregar a página
recomeça o atendimento. O acordo fecha sem sair do caso (`negociacao-e-alcadas.md` §10.13).

### 8.2.1 Endereço único do caso e modo consulta (revisão de produto 11/2026, J9)

* **Endereço canônico**: `/cobranca/casos/:id[?aba=]`. Duas rotas com o mesmo caminho (`collection.routes.ts`): a primeira
  tem `canMatch` com `CASE_WORK` e abre a **estação** (fila "Meus casos", cronômetro, janela de contato e registro), com
  `data.menuRoute = '/cobranca/fila'` — menu e trilha marcam "Estação de cobrança" (`menuRouteOf` em
  `core/layout/navigation.ts`, usado pelo shell e pela trilha); a segunda abre a **ficha em modo consulta**
  (`features/collection/case/case-consult.ts`) para quem só tem `CASE_VIEW` (jurídico, gestor, auditoria, financeiro), com
  menu e trilha em "Casos". O endereço antigo `/cobranca/fila?caso=<id>[&aba=]` redireciona (guard `legacyCaseRedirect`,
  `UrlTree`, antes do `permissionGuard`, para valer também a quem só consulta) para o canônico, mantendo a aba e os demais
  parâmetros; a estação passou a navegar sempre para `/cobranca/casos/:id` ao abrir um caso
  ("Meus casos", "Próximo caso"), e a `/cobranca/fila` sem caso continua como a estação vazia.
* **Modo consulta**: os mesmos dados e abas da ficha (`CaseSheetPanel`), sem fila, cronômetro, janela de contato nem
  registro de interação (essas consultas nem são feitas). Cabeçalho "Caso · <devedor>", "Ficha do devedor" (com
  `DEBTOR_VIEW`) e "Mais ações" só com o que o perfil faz: simular (`NEGOTIATION_SIMULATE`, abre o simulador com o caso),
  atribuir (`CASE_ASSIGN`), atualizar a posição dos títulos e mudar a situação (`CASE_MANAGE`). "Fechar acordo" numa
  proposta pronta abre a proposta. O servidor continua conferindo cada comando.
* **Selos do caso**: a área `sp-head__chips` (`data-tour="case-badges"`) do cabeçalho da ficha é a mesma nos dois modos —
  é onde entram marcadores como o de vulnerabilidade.
* **Avaliar o atendimento**: no histórico, quem tem `QUALITY_EVALUATE` vê "Avaliar" em cada interação registrada por outra
  pessoa (não o assistente nem o sistema), que abre a monitoria com o negociador, o caso e a data preenchidos
  (`remuneracao-variavel.md` §2.4.1).

### 8.3 "Seu dia" no início

`GET /my-day` (`CASE_WORK` ou `CASE_ASSIGN`), `MyDayQueries`: só contagens, sem dado de devedor, no escopo de credores do
ator (RLS + filtro por credor), em consultas indexadas (`JdbcMyDayStore`).

* **Operador** (`CASE_WORK`): casos ativos com ele; retornos de hoje (pendentes a partir de agora) e atrasados (perdidos
  nos últimos 30 dias ou com horário passado — a mesma janela da agenda e das pendências); promessas pendentes nos casos
  dele que vencem hoje e as vencidas ainda na tolerância; interações, contatos efetivos e promessas registrados por ele
  desde o início do dia (fuso de operação).
* **Supervisor** (`CASE_ASSIGN`): equipes ativas em que ele é o supervisor (identity, `TeamUseCases`), com os casos ativos
  dos membros, retornos atrasados, promessas que vencem hoje e as filas ativas atendidas por essas equipes (casos
  elegíveis aguardando operador e os de próxima ação vencida). Sem equipe supervisionada, `team` vem nulo.
* Retornos de hoje e atrasados usam a mesma regra SQL de "Minhas pendências" (`TaskDueSql`, pacote de persistência).
  "Aguardando operador" numa fila conta os casos elegíveis sem operador, inclusive os de próxima ação futura (a coluna
  "com próxima ação vencida" separa os que já passaram do horário).
* Aprovações, conversas e prazos continuam em "Minhas pendências" (`pendencias.md`); o "Seu dia" não os repete. Leitura
  de contagens: sem auditoria nem log de acesso a dado sensível; sem métrica nova.

### 8.4 Busca rápida de devedor e caso (Ctrl+K)

`POST /quick-search` (`DEBTOR_VIEW`; corpo `{ "term": "…" }`, até 80 caracteres; resposta `no-store`),
`QuickSearchQueries` → `QuickSearchService`. O termo vai no corpo, nunca na URL (ux.md §16).

* Devedores pelo debt (`DebtorQuickSearch`, [debt.md](debt.md) §6): nome (3+), CPF/CNPJ completo, telefone completo ou
  código do título; até 10, no escopo; busca por documento/telefone com `PERSONAL_DATA_SEARCHED` no log de acesso.
* Casos desses devedores numa consulta só (`findByDebtorIdInOrderByOpenedAtDesc`, índice do devedor) só para quem tem
  `CASE_VIEW`, filtrados pelos credores do escopo, ativos primeiro, até 20.
* Nomes de credor e carteira resolvidos pelo organization (`getAll`, já filtrado pelo escopo), para a paleta mostrar
  "devedor · credor" e "situação · carteira" sem outra chamada.
* Resposta mínima: nome, documento mascarado, situação, credores; caso com credor, carteira e situação — sem saldo,
  contato ou nascimento. Sem auditoria (leitura), sem evento e sem métrica nova.

### 8.5 Vínculos da ficha do devedor

* `GET /debtors/{debtorId}/interactions` (`CASE_VIEW`, paginado), `InteractionUseCases#listByDebtor`: interações do
  devedor em todos os casos dos credores do escopo (filtro na consulta; sem credor no escopo, página vazia), da mais
  recente à mais antiga (`ix_interaction_debtor`, **V0607**). A aba "Interações" da ficha mostra as 50 mais recentes.
* `GET /disputes?debtorId=` (`DisputeQuery.debtorId`, `DisputeSearchCriteria.debtorId`): contestações do devedor no
  escopo, para o "Relacionados" (`ix_dispute_debtor`, **V0607**). Ambos substituem uma consulta por caso.

## 9. Eventos de integração publicados

| Evento | Quando | Campos relevantes |
| --- | --- | --- |
| `CaseStatusChanged` | abertura, mudança de status, mudança de elegibilidade | construtor completo: `assigneeId`, `teamId`, `eligibleBalance` (saldo elegível se elegível, senão zero) |
| `CaseAssignmentChanged` | responsável humano ou equipe mudou | `previousAssigneeId`, `assigneeId`, `teamId`, `eligibleBalance` |
| `CaseBalanceChanged` (**novo**, 23/10/2026) | só o saldo elegível atribuível mudou (pagamento, carga, baixa, contestação), sem mudança de status, elegibilidade ou responsável | `assigneeId`, `teamId`, `eligible`, `previousEligibleBalance`, `eligibleBalance` |
| `InteractionRecorded` | interação registrada | construtor completo: `direction`, `portfolioId`, `contactId` (contato usado, conferido contra o devedor do caso: `interaction.contact_not_of_debtor`), `source` (origem: `MANUAL`, `TELEPHONY`, `COMMUNICATION`, `ASSISTANT`, `SYSTEM` — as de telefonia e mensageria espelham `CallCompleted` e `MessageStatusChanged`) — base da inteligência de contato, [inteligencia-de-contato.md](inteligencia-de-contato.md)); `occurredAt` = início da interação |
| `CallbackStatusChanged` (**novo**) | retorno agendado/cumprido/atrasado/cumprido com atraso/cancelado | `assigneeId`, `teamId`, `dueAt`, `toleranceUntil`, `status`, `completedBy` |
| `PromiseStatusChanged` (**novo**) | promessa registrada/cumprida/quebrada/cancelada/reaberta | `operatorId`, `amount`, `paidAmount`, `dueDate`, `breaksAfter`, `previousStatus`, `status` |
| `DisputeStatusChanged` | contestação aberta, em análise, resolvida, retirada | `titleIds`, `status`, `blocksCollection` |

**Score de propensão** (24/10/2026): consome `CaseScoreChanged` do `scoring` (`collection.case-score`): grava `score` e
`score_calculated_at` (`V0602`; cálculo mais antigo que o aplicado é ignorado) e reavalia só a fila (critério de faixa de
score). A ordem `HIGHEST_SCORE` do "próximo caso" já usava `c.score desc nulls last`. Teste: `CollectionScoreOrderingIT`.
Nova consulta pública `CaseUseCases#listActiveAfter(cursor, limite ≤ 500)` para varreduras em lote (escoragem diária).

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
`VulnerabilityMarkerChanged` (indicador `collection_case.vulnerable` e nova fila dos casos ativos do devedor),
`DebtorContactNoveltyChanged` (`collection.contact-novelty`: relê no debt, sob o lock do devedor, o tipo de pessoa e o fim
da novidade — eventos podem chegar fora de ordem — e refaz só a fila dos casos ativos do devedor),
`PaymentConfirmed`, `PaymentReversed`, `AgreementStatusChanged`, `CaseScoreChanged`, `TenantProvisioned` — consumidores
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
  `DISPUTE_MANAGE` e `DISPUTE_WORK` (**nova**: trabalhar as contestações atribuídas a si e assumir as sem responsável;
  operador e, por herança, supervisor). Perfis padrão: operador (trabalho), supervisor (+ atribuição, gestão de casos, filas, contestações),
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
| `/cases/{id}/contact-availability` | `GET` (`CASE_WORK`; §8.1) |
| `/cases/{id}/suspend` · `/resume` · `/legal` · `/legal-return` · `/close` · `/refresh` | `POST` |
| `/cases/{id}/assign` · `/release` · `/take-over` · `/assistant` | `POST` |
| `/next-case` (200 caso / 204 vazio) · `/my-cases` | `POST` · `GET` |
| `/my-day` | `GET` (`CASE_WORK` ou `CASE_ASSIGN`; §8.3) |
| `/quick-search` | `POST` (`DEBTOR_VIEW`; termo no corpo; §8.4) |
| `/debtors/{debtorId}/interactions` | `GET` (`CASE_VIEW`, paginado; §8.5) |
| `/queues` · `/queues/{id}` · `/queues/{id}/summary` · `/queues/{id}/activate|deactivate` · `/queues/reroute` · `/queues/{id}/distribute` | CRUD + ações |
| `/redistributions` | `POST` |
| `/assignable-users?purpose=…[&caseId][&creditorId][&fromUserId][&q][&ids]` | `GET` (`CASE_ASSIGN` ou `DISPUTE_MANAGE`, conforme o campo; §3.3.1) |
| `/tabulations` · `/tabulations/{id}` · `/activate|deactivate` | CRUD + ações (com `followUp` e `disputeType`, §4.1) |
| `/cases/{id}/interactions` | `GET`, `POST` (201; 200 se reenvio de integração) |
| `/interactions/{id}` | `GET` (`CASE_VIEW`; só `{interactionId, caseId}`, no escopo do caso da interação, fora dele 404; volta da monitoria ao histórico, decisão 1077) |
| `/cases/{id}/tasks` · `/agenda` · `/tasks/{id}/complete|cancel|reschedule|reassign` | `GET`, `POST` |
| `/cases/{id}/promises` · `/promises/{id}` · `/promises/{id}/cancel` | `GET`, `POST` |
| `/cases/{id}/disputes` · `/disputes` (filtro `debtorId`, §8.5) · `/disputes/{id}` · `/review|resolve|withdraw` · `/evidences` · `/evidences/{eid}` | `GET`, `POST` (multipart) |
| `/disputes/{id}/detail` · `/assign` · `/take` · `/assignee-options` · `/disputes/redistributions` | `GET`, `POST` (§7.1) |
| `/disputes/{id}/creditor-request-draft` · `/creditor-requests` · `/creditor-responses` (multipart `response` + `files`) | `GET`, `POST` (§7.3) |
| `/disputes/{id}/debtor-notice-draft` · `/debtor-notices` · `/adjustment/dismiss` | `GET`, `POST` (§7.4, §7.5) |
| `/dispute-config/settings` · `/deadline-rules[/{id}]` · `/deadline-preview` · `/pool[/{id}]` · `/templates[/{kind}/{key}[/history|/restore-default]]` | `GET`, `PUT`, `POST`, `DELETE` (`DISPUTE_MANAGE`; leitura também `DISPUTE_WORK`) |
| `/api/v1/dispute-files/{token}` (público, fora de `/collection`) | `GET` — ZIP das evidências do pedido ao credor |
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
| 15 | "Posso contatar agora?" no collection, por caso | endpoint no compliance por devedor + credor | o caso define devedor/credor no escopo do ator e traz a elegibilidade (contestação, suspensão); grafo collection → compliance |
| 16 | Nome do devedor na agenda só com `CASE_VIEW`/`DEBTOR_VIEW`, sem documento | sempre; nunca | mesma regra da ficha; minimização |
| 17 | Títulos da contestação lidos do debt na consulta | cópia dos dados na contestação | sem duplicar dados nem migration; escopo do ator aplicado pelo debt |
