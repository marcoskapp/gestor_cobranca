# Desenho — módulo `campaign` (segmentos, réguas, execução, testes A/B)

Escopo: M04, F13 (régua automatizada), F25 (testes A/B), RB07 (resposta redireciona a sequência). Schema `campaign`,
migrations V1100–V1199 (`V1100` tabelas, RLS, gatilhos e privilégios). Permissões já existentes no catálogo:
`SEGMENT_MANAGE`, `CAMPAIGN_VIEW`, `CAMPAIGN_MANAGE`, `CAMPAIGN_EXECUTE`, `EXPERIMENT_MANAGE` (gestor: todas; supervisor
e auditor: `CAMPAIGN_VIEW`) — sem migration de identity.
Dependências: `shared`, `organization`, `debt`, `compliance`, `collection`, `negotiation`, `communication`, `portal`
(interfaces `usecases`; o portal só para o link de acesso de uso único). Os demais fatos chegam por eventos de integração (`shared.events.*`).

## 1. Componentes

| Componente | Onde | Papel |
| --- | --- | --- |
| `Segment` + `SegmentCriteria` | `domain.model` | filtros combináveis salvos (jsonb tipado) |
| `Campaign` | `domain.model` | credor (carteira opcional), segmento, finalidade, prioridade, grupo de exclusividade, parâmetros |
| `Cadence` + `CadenceDefinition`/`CadenceStep`/`StepAction`/`StepCondition`/`SendWindow` | `domain.model` | régua versionada (`DRAFT → PUBLISHED → RETIRED`), imutável depois de publicada (gatilho) |
| `Enrollment` | `domain.model` | execução da campanha para um caso (devedor × carteira) na versão da régua com que começou |
| `StepExecution` | `domain.model` | execução de etapa append-only (só a situação da mensagem é atualizada), chave idempotente |
| `Attribution` | `domain.model` | resultado atribuído (resposta, contato efetivo, acordo, pagamento, estorno), append-only |
| `Experiment`, `ExperimentAssignment`, `ExperimentVariant`/`StepOverride` | `domain.model` | teste A/B, braço estável por devedor |
| `SegmentMatcher`, `ConcurrencyPolicy`, `PauseRules`, `ReplyRouting`, `VariantAllocator`, `ExperimentStatistics` | `domain.service` | regras puras, testadas sem Spring |
| `AudienceProjection` + `AudienceStore` (`campaign.audience_case`) | `application.service`, `port.out` | read model de audiência |
| `EnrollJob`, `EnrollmentEngine` | `application.service` | inscrição em lotes com concorrência |
| `StepJob`, `StepRunner`, `ActionExecutor`, `VariableResolver` | `application.service` | motor de etapas |
| `CampaignReactions` + `CampaignEventListeners` | `application.service` | pausas, retomadas, respostas, atribuição |
| `CampaignMaintenance`, `CampaignJobs`, `FinishCampaignJob`, `AudienceRebuildJob` | `application.service` | varredura, retomadas programadas, fim do controle, reconciliação |
| `CampaignOfferPort` | `application.port.out` | oferta/link de pagamento — **somente porta** (§3.5) |
| `AgreementInstallments` | `application.service` | próxima parcela em aberto do acordo em vigor do caso (`AgreementUseCases`) — projeção e envio (§3.6) |

## 2. Segmentos e read model de audiência

**Sem leitura de tabelas de outros módulos.** O módulo mantém `campaign.audience_case`, uma projeção própria (sem dado
pessoal) de cada caso de cobrança: situação e estágio guardado, elegibilidade e motivos, canais contatáveis, saldo em
aberto/elegível, vencimento mais antigo, dono do atendimento, interações, contatos efetivos, últimas datas, score e acordos
quebrados. É atualizada a partir de `CaseUseCases#get` ao consumir `CaseStatusChanged`, `CaseBalanceChanged`,
`CaseAssignmentChanged`, `PromiseStatusChanged`, `InteractionRecorded`, `DisputeStatusChanged`, `LegalMatterStageChanged`
e `AgreementStatusChanged` (este também conta os acordos quebrados) e `InstallmentStatusChanged` (parcela paga, vencida ou
cancelada), antes de cada etapa (posição atual) e pela
reconciliação diária/sob demanda (`AudienceRebuildJob`, páginas de `CaseUseCases#search`). Grava com
`case_version` crescente: eventos fora de ordem nunca regridem a projeção. Para casos em acordo (estágio ou estágio
guardado `AGREEMENT`), a projeção grava também o vencimento da **próxima parcela em aberto do acordo em vigor**
(`next_installment_due_date`, `V1102`), lido do negotiation no momento (`AgreementInstallments`) e gravado à parte
(`applyNextInstallment`, sem a guarda de versão: a baixa de parcela nem sempre muda o caso); fora de acordo, nulo sem
consultar o negotiation.

`SegmentCriteria` (todos por **E**; nulo = sem filtro): carteiras, faixa de atraso (dias desde o vencimento mais antigo,
fuso de operação, nunca negativo), faixa de saldo em aberto, situações do caso, somente elegíveis (padrão sim — RB02),
canal contatável, faixa de interações, já houve/nunca houve contato efetivo, sem interação/contato efetivo há N dias
(inclui nunca), com/sem promessa pendente, com/sem acordo em vigor (estágio ou estágio guardado), acordos quebrados
mínimos, faixa de score (critério opcional: casos sem score ficam fora; o score vem do módulo `scoring` pelo evento
`CaseScoreChanged`), dono do atendimento e **parcela do acordo vencendo** (`minDaysToInstallment`/`maxDaysToInstallment`:
dias de hoje até o vencimento da próxima parcela em aberto, 0 = hoje, até 366; casos sem acordo em vigor com parcela em
aberto ficam fora; parcela já vencida não atende ao máximo; incompatível com "sem acordo em vigor"). A mesma semântica é implementada em SQL (`JdbcAudienceStore.where`, contagem e
seleção em lote) e em memória (`SegmentMatcher`, reavaliação antes de cada etapa) — `SegmentMatcherTest` documenta os
limites.

Contagem prévia: `GET /segments/{id}/count` e `POST /segments/preview` (casos, devedores distintos, saldo somado). Sem
credor, exige escopo de todos os credores.

## 3. Campanhas e réguas

### 3.1 Campanha

Credor obrigatório, carteira opcional, segmento (do credor ou da assessoria), finalidade (`EXTRAJUDICIAL_COLLECTION`
padrão, `REMINDER`, `AGREEMENT_FOLLOW_UP`, `INFORMATIVE`), prioridade (0–1000, padrão 100), grupo de exclusividade,
reinscrição após N dias (nulo = nunca), janela de atribuição (padrão 7 dias), retomada após pagamento (nulo = manual),
adiamento máximo por porteiro (padrão 24 h) e "sair quando deixar o segmento" (padrão sim). Ciclo `DRAFT → ACTIVE ⇄
PAUSED → FINISHED`.

### 3.2 Régua (`CadenceDefinition`, JSON)

```json
{ "defaultOnReply": "MOVE_TO_QUEUE", "replyTeamId": null,
  "steps": [
    { "code": "D0", "waitMinutes": 0,
      "window": { "start": "09:00:00", "end": "12:00:00", "days": ["MONDAY", "FRIDAY"] },
      "condition": { "criteria": { "maxInteractions": 0 }, "requireNoReply": true },
      "action": { "type": "SEND_MESSAGE", "channel": "WHATSAPP", "templateCode": "COBRANCA",
                  "variables": { "nome": "DEBTOR_FIRST_NAME", "valor": "OPEN_BALANCE" } },
      "onReply": "GOTO_STEP", "onReplyStepCode": "LIGAR" },
    { "code": "LIGAR", "waitMinutes": 60,
      "action": { "type": "CREATE_TASK", "taskType": "CALLBACK", "taskDueInMinutes": 30 } } ] }
```

* **Espera**: minutos desde a etapa anterior (na primeira, desde a inscrição), até 60 dias. Até 30 etapas.
* **Janela da etapa**: dias e horário no fuso de operação (estratégia da campanha); fora dela, a etapa é **adiada** para
  a próxima abertura. Não substitui o porteiro.
* **Condição**: filtros sobre a posição atual do caso, "sem resposta", "sem contato efetivo desde a inscrição", "última
  mensagem não lida". Não atendida → etapa **pulada** (`CONDITION_NOT_MET`) e a régua segue.
* **Ações**: `SEND_MESSAGE` (template publicado, canal WHATSAPP/EMAIL/SMS, variáveis por origem — `DEBTOR_FIRST_NAME`,
  `OPEN_BALANCE`, `ELIGIBLE_BALANCE`, `OLDEST_DUE_DATE`, `DAYS_OVERDUE`, `CREDITOR_NAME`, `NEXT_INSTALLMENT_AMOUNT`,
  `NEXT_INSTALLMENT_DUE_DATE`, `PORTAL_LINK`, `PORTAL_LINK_HOURS` (só junto com `PORTAL_LINK` — `cadence.portal_hours_without_link`),
  `LITERAL:texto` e, em oferta, `OFFER_AMOUNT`, `OFFER_DUE_DATE`, `PAYMENT_LINK` — §3.6); `CREATE_TASK` (retorno ou acompanhamento no caso, prazo em minutos);
  `HAND_TO_ASSISTANT` (`CaseWorkUseCases#handToAssistant`); `MOVE_TO_QUEUE` (`assign` à equipe ou `release` para a fila
  do roteamento); `OFFER` (porta §3.5). Entregar ao assistente e mover para a fila encerram a inscrição.
* **Resposta (RB07)**: `onReply` da última etapa executada ou o `defaultOnReply` (padrão `STOP`): `STOP` (encerra),
  `GOTO_STEP` (cancela o agendado e vai para a etapa, respeitando a espera dela), `HAND_TO_ASSISTANT`, `MOVE_TO_QUEUE`
  (executadas pelo job — §4.4), `CONTINUE`.
* **Publicação**: valida a régua, exige template **publicado** no communication para o credor (ou da assessoria) no canal
  de cada etapa de mensagem e que as variáveis da etapa cubram exatamente as do template (`cadence.template_*`); etapa de
  oferta sem adaptador → `409 cadence.offer_unavailable`. A versão publicada anterior é retirada; inscrições em andamento
  continuam na versão com que começaram (reprodutibilidade).

### 3.3 Variáveis e dados pessoais

Os valores são calculados no envio (`VariableResolver`) e entregues ao `MessageUseCases#send`, que os valida (tipos sem
documento/contato) e guarda criptografados. O módulo campaign não grava nem registra em log nenhum valor: só ids,
códigos e enums.

### 3.4 Pausa automática

| Fato | Origem | Pausa | Retomada |
| --- | --- | --- | --- |
| pagamento confirmado (mesmo devedor, credor e carteira) | `PaymentConfirmed` | `PAYMENT_CONFIRMED` | após `paymentPauseDays` (job) ou manual |
| acordo formalizado | `AgreementStatusChanged` + posição do caso (`AGREEMENT`) | `AGREEMENT_FORMALIZED` | acordo quebrado/cancelado (caso volta a negociação) |
| contestação bloqueante | `DisputeStatusChanged`/caso `DISPUTED` | `DISPUTE_OPENED` | contestação encerrada |
| restrição de contato em todos os canais da régua | `ContactRestrictionChanged` ou elegibilidade `CONTACT_RESTRICTED` | `CONTACT_RESTRICTED` | revogação e algum canal da régua livre no porteiro |
| caso suspenso | `CaseStatusChanged` (`SUSPENDED`) | `CASE_SUSPENDED` | retomada do caso |
| caso no jurídico | `CaseStatusChanged`/`LegalMatterStageChanged` (`LEGAL`) | `CASE_LEGAL` | retorno do jurídico |
| caso encerrado | `CaseStatusChanged` (`CLOSED`) | — encerra (`CASE_CLOSED`) | — |

Campanhas `AGREEMENT_FOLLOW_UP` (lembrete de parcela) não pausam por acordo nem por pagamento. A pausa é reavaliada
também no início de cada etapa (posição atual do caso), então nenhum evento perdido faz a régua seguir num caso
bloqueado; a inscrição de novos casos exclui essas situações. A retomada manual
(`POST /enrollments/{id}/resume`) recusa caso ainda bloqueado (`409 enrollment.still_blocked`).

### 3.5 Oferta e link de pagamento (porta)

`CampaignOfferPort` é o contrato para gerar a oferta pelo motor do negotiation **com alçada própria da campanha**
(passando pelo `NegotiationGuard`) e a instrução/link no payment. Não há adaptador: o negotiation recusa atores de
sistema (`authority.actor_not_supported`) e o payment está em desenvolvimento em paralelo. Sem o bean, publicar régua
com oferta é recusado e, por defesa, a etapa é pulada com `OFFER_UNAVAILABLE`. Para plugar: implementar a porta em
`infrastructure`, criar o sujeito de alçada de campanha no negotiation (perfil por credor) e o link no payment; nada muda
no motor.

### 3.6 Parcela do acordo e link do portal

* **`NEXT_INSTALLMENT_AMOUNT` (MONEY) / `NEXT_INSTALLMENT_DUE_DATE` (DATE)**: no envio, `AgreementInstallments` busca os
  acordos do devedor no credor (`AgreementUseCases#search`), fica com os em vigor (`FORMALIZED`, `IN_PAYMENT`) do caso
  (ou, sem caso no acordo, da carteira do caso) e escolhe a parcela `OPEN`/`PARTIALLY_PAID`/`OVERDUE` de vencimento mais
  próximo; o valor é o ainda devido (valor − pago). Sem acordo em vigor → etapa pulada `NO_ACTIVE_AGREEMENT`; sem
  parcela em aberto → `NO_OPEN_INSTALLMENT`. Nada é enviado com valor vazio.
* **`PORTAL_LINK` (URL) / `PORTAL_LINK_HOURS` (INTEGER)**: depois do porteiro, da oferta e da parcela, imediatamente antes
  do envio, `PortalLinkUseCases#issue` gera o link de uso único (regras do link do operador — `portal.md` §5) para a
  carteira do caso; indisponível → etapa pulada `PORTAL_UNAVAILABLE` (detalhe = motivo do portal). Depois do
  `MessageUseCases#send`, `recordDispatch` vincula a mensagem ao link (mensagem `FAILED` revoga o link). Tudo na
  transação da etapa: erro desfaz também o link. A URL só existe nas variáveis da mensagem (criptografadas pelo
  communication) — nunca em log, evento, auditoria ou execução da etapa.
* **Publicação**: as novas origens passam pela mesma conferência de variáveis do template (`TemplateCheck`).
* **Régua de lembrete de parcela**: campanha `AGREEMENT_FOLLOW_UP` (não pausa por acordo nem por pagamento), segmento
  "acordo em vigor" + "parcela vence em N dias" (ex.: 3 a 3), reinscrição após ~20 dias (uma inscrição por parcela) e
  uma etapa `SEND_MESSAGE` com `ACORDO_LEMBRETE_PARCELA` (`negocio/templates-cobranca.md` §4.4). A saída do segmento
  antes da etapa (parcela paga) encerra a inscrição sem enviar.

## 4. Execução

### 4.1 Inscrição e concorrência

Ativar (`CAMPAIGN_EXECUTE`) exige régua publicada e segmento ativo; enfileira `campaign.enroll` e a varredura
(`campaign.sweep`, a cada 5 min, ShedLock) repete para os casos que entram no segmento depois. O job lê lotes de
candidatos (`app.campaign.enrollment-batch`, 200) em ordem de caso a partir de um cursor e encadeia a continuação
(retomada). Cada candidato é inscrito em transação própria (`EnrollmentEngine`) sob lock consultivo por devedor:

1. já inscrito, em controle, ou encerrado dentro da reinscrição → nada;
2. situação que pausaria a régua → não inscreve agora;
3. teste A/B em execução → braço estável (§5); controle → inscrição `HOLDOUT`;
4. **grupo de exclusividade** (`ConcurrencyPolicy`): inscrição em andamento de outra campanha do grupo com prioridade
   igual ou maior → **supressão** (registrada em `enrollment_suppression`, painel); prioridade menor → a outra é
   encerrada `PREEMPTED` e esta inscreve; a mesma campanha nunca roda duas vezes para o devedor num grupo;
5. inscrição ativa com a primeira etapa agendada.

Defesa no banco: uma inscrição em andamento por (campanha, caso) e uma por (devedor, grupo) — índices únicos parciais.

### 4.2 Etapa (`campaign.step`)

Transação própria, inscrição bloqueada (`FOR UPDATE`): estado e obsolescência do job (etapa/iteração/instante) →
campanha ativa → posição atual do caso → fim/pausa → ação de resposta pendente → **saída do segmento** (encerra
`LEFT_SEGMENT`) → **inelegível** (pula `CASE_INELIGIBLE`) → condição (pula) → janela da etapa (adia) → **porteiro**
(`ContactGate#evaluate`, sem registrar tentativa): negação temporária (janela legal, feriado, limites, intervalo) adia até
`nextAllowedAt`, limitado a `maxDeferralHours` desde que a etapa ficou devida (depois disso pula `CONTACT_DENIED`);
restrição pula `CONTACT_DENIED` → ação → próxima etapa ou fim.

O envio passa pelo communication (`MessageUseCases#send` com `campaignExecutionId` = inscrição e autor `CAMPAIGN`), que
aplica de novo o porteiro na verificação prévia e **imediatamente antes do envio** (`authorizeAndRecord`, origem
`CAMPAIGN`). Mensagem negada na verificação prévia vira etapa pulada `CONTACT_DENIED` com o id da mensagem.

### 4.3 Idempotência, erros e reprocessamento

* Chave `campaign:<inscrição>:<etapa>:<iteração>` (iteração = sequência de execuções da inscrição): única entre execuções
  `EXECUTED`/`SKIPPED` (índice parcial) **e** enviada como chave de idempotência ao communication — o reenvio devolve a
  mensagem já criada. Registro da execução e efeito são confirmados juntos.
* Job duplicado, atrasado ou de etapa já reagendada encontra outra etapa/iteração/horário e não faz nada.
* Erro de negócio de outro módulo (template retirado, canal sem conta…) desfaz a transação e é registrado em outra:
  execução `FAILED` (motivo = código), inscrição `FAILED`. Falta de contato no canal (`message.no_contact`/
  `contact_unusable`) é etapa pulada `NO_CONTACT`. Falha técnica é repetida pelo job (`app.campaign.step-max-attempts`, 5)
  e, na última, tratada como falha da inscrição (ERROR + `campaign.jobs.failures`).
* `POST /campaigns/{id}/reprocess` volta as inscrições `FAILED` para a mesma etapa e iteração: sem duplicidade.
* Retomada após queda: a varredura reagenda etapas ativas atrasadas além de `reconcile-grace` (15 min) com
  `SKIP LOCKED`; reativar campanha pausada reagenda todas as etapas ativas.
* Laços de "ir para etapa" limitados a 100 execuções por inscrição.

### 4.4 Respostas

`MessageStatusChanged` `INBOUND/RECEIVED` com devedor e credor identificados: atribui `REPLY` às inscrições que observam o
devedor no credor e, nas ativas, aplica a transição. `HAND_TO_ASSISTANT`/`MOVE_TO_QUEUE` não chamam o collection no
consumidor (uma recusa desfaria o consumo e repetiria o evento): marcam a ação pendente e o job de etapa a executa
(`StepExecution` com etapa `REPLY`), encerrando a inscrição.

### 4.5 Régua esgotada → jurídico

Fim das etapas **sem** resposta, contato efetivo, acordo ou pagamento atribuídos → `CADENCE_EXHAUSTED` e evento de
integração **`shared.events.campaign.CadenceExhausted`** (inscrição, campanha, finalidade, versão da régua, caso, devedor,
credor, carteira, etapas executadas/puladas, mensagens enviadas, início), publicado uma vez, na transação que encerra a
inscrição; com resultado → `CADENCE_COMPLETED`. **Contrato para o legal** (critério "régua extrajudicial esgotada",
`juridico.md` §B.10): considerar só `campaignPurpose = EXTRAJUDICIAL_COLLECTION`, consumir com `TenantEventListener`
(idempotente) e manter por caso a última régua esgotada (data), avaliando a regra de elegibilidade no consumo. O legal não
depende do campaign: lê somente o evento.

### 4.6 Painel (`GET /campaigns/{id}/dashboard`)

Denominadores explícitos: inscrições (por situação, pausas por motivo, encerramentos por motivo), supressões por
concorrência, execuções por etapa (executadas, puladas, falhas), puladas por motivo, falhas por código, mensagens da régua
(enviadas, entregues, lidas, falhas, bloqueadas pelo porteiro) e resultados atribuídos na janela (inscrições com
resposta e contato efetivo, acordos e valor, inscrições pagantes e valor recuperado líquido de estornos — cada acordo ou
pagamento contado uma vez na campanha).

### 4.7 Atribuição

"Intenção de tratar": resultado do devedor no credor vale para toda inscrição cujo período de observação o contém —
da inscrição até o fim + janela de atribuição (tratadas) ou até o horizonte do experimento (controle). Uma vez por
inscrição × tipo × referência; estorno (`PaymentReversed`) gera valor negativo para as inscrições do pagamento.

## 5. Testes A/B (F25)

* Um experimento aberto por campanha. Variantes (até 10) com peso e alterações por etapa: **template**, **canal**,
  **horário** (janela) e **oferta**; variantes validadas contra a régua publicada no início (templates publicados,
  variáveis); depois disso variantes, sal, controle e horizonte ficam congelados (gatilho).
* **Atribuição estável**: balde `[0, 10000)` = SHA-256(sal aleatório do experimento + ":" + devedor), 8 primeiros bytes;
  baldes abaixo de `controlShare × 100` → controle (nenhuma ação, só observação, sem exclusividade); demais repartidos
  pelos pesos. Gravada uma vez por devedor (`experiment_assignment`, append-only) e registrada em
  `experiment.variant_assigned`. Todos os casos do devedor ficam no mesmo braço.
* **Métricas** por braço, **denominador = devedores do braço**: taxa de contato efetivo, de resposta, de acordo, de
  pagamento confirmado (valor líquido > 0) e valor recuperado por devedor (líquido de estornos), com resultados entre a
  inscrição e o horizonte de observação. `mature=true` (padrão) usa só devedores inscritos há pelo menos o horizonte:
  todos os braços observados pelo mesmo período.
* **Método (IC de 95%)**: taxas por intervalo de **Wilson**; diferença para o controle (ou a primeira variante, sem
  controle) pelo **método híbrido de Newcombe** (score de Wilson); valor por devedor com intervalo normal da média e
  diferença por **Welch** (aproximação normal). "Significativa" = intervalo da diferença sem o zero. Sem correção para
  múltiplas comparações nem testes sequenciais: decidir com amostra planejada.
* **Avisos**: `SAMPLE_BELOW_MINIMUM` (braço abaixo de `minSamplePerArm`, padrão 100), `FEW_EVENTS` (menos de 5 sucessos
  ou fracassos), `NO_VARIANCE`; geral `INSUFFICIENT_SAMPLE` (na métrica principal) e `NO_COMPARISON`.

## 6. Eventos, logs e métricas

* Publicado: `CadenceExhausted` (§4.5).
* Consumidos (`campaign.<reação>`): `CaseStatusChanged`, `CaseBalanceChanged`, `CaseAssignmentChanged`,
  `PromiseStatusChanged`, `InteractionRecorded`, `DisputeStatusChanged`, `LegalMatterStageChanged`,
  `AgreementStatusChanged`, `InstallmentStatusChanged` (`campaign.installment-status`), `PaymentConfirmed`, `PaymentReversed`, `ContactRestrictionChanged`, `MessageStatusChanged`.
* Negócio: `campaign.activated`, `campaign.paused` (`scope` campanha ou inscrição, `reason`), `campaign.finished`,
  `campaign.step_executed`, `campaign.step_skipped`, `campaign.replied`, `campaign.cadence_exhausted`,
  `experiment.variant_assigned` — atributos em `logs-e-metricas.md` §3.6. Nunca nome, documento, contato ou conteúdo.
* Métricas `campaign.*` — `logs-e-metricas.md` §5.
* Auditoria: segmentos (criação, filtros, ativação), campanhas, rascunho e publicação de régua, ativação/pausa/encerramento,
  reprocessamento, comandos em inscrições, experimentos (criação, alteração, início, encerramento), reconstrução da
  audiência.

## 7. Segurança

* Escopo por credor em tudo (`CampaignAccess`/`AccessScope`: 404 leitura com `SCOPE_DENIED`, 403 comando); segmentos da
  assessoria e contagem sem credor exigem todos os credores; inscrições por caso filtradas pelo escopo.
* O motor age como ator de sistema do tenant e só por casos de uso públicos (communication, collection); o envio sempre
  passa pelo porteiro.
* RLS forçado em todas as tabelas; execuções de etapa só com a situação da mensagem atualizável (gatilho), atribuições e
  braços append-only, réguas publicadas e experimentos iniciados imutáveis; sem `DELETE` para o runtime (exceto
  `audience_case` e `enrollment_suppression`, projeções).

## 8. API REST (`/api/v1/campaign`)

| Recurso | Métodos | Permissão |
| --- | --- | --- |
| `/segments` · `/segments/{id}` · `/segments/{id}/count` | `GET` | `SEGMENT_MANAGE`, `CAMPAIGN_MANAGE` ou `CAMPAIGN_VIEW` |
| `/segments` · `/segments/{id}` · `/{id}/activate|deactivate` | `POST`, `PUT` | `SEGMENT_MANAGE` |
| `/segments/preview` · `/audience/rebuild` | `POST` | `SEGMENT_MANAGE` ou `CAMPAIGN_MANAGE` |
| `/campaigns` · `/campaigns/{id}` · `/{id}/cadences` · `/cadences/{id}` · `/{id}/enrollments` · `/{id}/dashboard` | `GET` | `CAMPAIGN_VIEW`, `CAMPAIGN_MANAGE` ou `CAMPAIGN_EXECUTE` |
| `/campaigns` · `/campaigns/{id}` · `/{id}/cadences/draft` · `/{id}/cadences/draft/publish` | `POST`, `PUT` | `CAMPAIGN_MANAGE` |
| `/campaigns/{id}/activate|pause|finish|reprocess` | `POST` | `CAMPAIGN_EXECUTE` |
| `/enrollments?caseId=` · `/enrollments/{id}` | `GET` | leitura de campanha (por caso também `CASE_VIEW`) |
| `/enrollments/{id}/pause|resume|stop` | `POST` | `CAMPAIGN_EXECUTE` |
| `/campaigns/{id}/experiments` · `/experiments/{id}` · `/experiments/{id}/results?mature=` | `GET` | `EXPERIMENT_MANAGE` ou `CAMPAIGN_VIEW` |
| `/campaigns/{id}/experiments` · `/experiments/{id}` · `/{id}/start|stop` | `POST`, `PUT` | `EXPERIMENT_MANAGE` |

## 9. Parâmetros (`app.campaign.*`)

`enrollment-batch` (200), `sweep-interval` (PT5M), `maintenance-interval` (PT5M), `step-max-attempts` (5),
`reconcile-grace` (PT15M), `maintenance-batch` (500), `audience-batch` (200), `audience-cron` (03:15).

## 10. Decisões

| # | Decisão | Alternativa | Motivo |
| --- | --- | --- | --- |
| 1 | Read model de audiência próprio, alimentado por `CaseUseCases` nos eventos do caso + reconciliação | consultar `collection.collection_case` direto; chamar `CaseUseCases#search` a cada avaliação | regra de não ler tabelas de outros módulos; segmentos combináveis exigem SQL; projeção com versão nunca regride |
| 2 | Filtros salvos como record tipado em jsonb, mesma semântica em SQL e em memória | linguagem de regras livre | validação forte e testável; contagem e reavaliação coerentes |
| 3 | Inscrição por caso (devedor × carteira); exclusividade e braço A/B por devedor | inscrição por devedor | o caso define elegibilidade, dono e pausas; o devedor é a unidade de contato e de experimento |
| 4 | Exclusividade por grupo configurável + prioridade (maior substitui; empate mantém a em andamento) | lista de campanhas incompatíveis | simples de operar e auditar; evita oscilação entre campanhas de mesma prioridade |
| 5 | Pré-verificação do porteiro (`evaluate`) adia negações temporárias até `maxDeferralHours` | deixar o communication registrar `CONTACT_DENIED` | a etapa não se perde por horário/limite; o communication continua autorizando no envio |
| 6 | Chave idempotente compartilhada com o communication (inscrição × etapa × iteração) | dedup só no job | reprocessamento e jobs duplicados nunca duplicam mensagem |
| 7 | Erro de negócio de outro módulo → inscrição `FAILED` reprocessável; falta de contato → pula | descartar ou repetir indefinidamente | rastreável no painel, sem duplicidade, sem bloquear o job |
| 8 | Transições por resposta que mudam o caso executadas pelo job, não no consumidor | chamar o collection no consumidor | recusa do collection não desfaz o consumo nem repete o evento |
| 9 | Régua esgotada = fim sem resposta/contato efetivo/acordo/pagamento, evento `CadenceExhausted` com finalidade | legal consultar o campaign | o legal não depende do campaign (grafo); critério explícito e idempotente |
| 10 | Atribuição por intenção de tratar, com janela; controle observado até o horizonte | só último toque executado | comparável entre braços (inclusive controle) e sem viés de sobrevivência |
| 11 | Wilson/Newcombe para taxas, Welch para valor; avisos de amostra; braço por devedor | teste qui-quadrado/p-valor | intervalos interpretáveis pela operação e válidos com taxas pequenas |
| 12 | Oferta/link de pagamento só como porta; publicação recusada sem adaptador | simular oferta | o negotiation não tem alçada para sistema e o payment não existe; sem adaptador simulado em produção |
| 13 | Campanhas `AGREEMENT_FOLLOW_UP` não pausam por acordo/pagamento | pausar todas | lembrete de parcela tem justamente esse público |
| 14 | Variáveis calculadas no envio e entregues ao communication; nada pessoal no campaign | guardar valores na execução | minimização; o communication já valida e criptografa |
| 15 | Permissões existentes do catálogo | permissões novas | já estavam nos perfis padrão; sem migration de identity |
| 16 | Parcela do acordo lida do negotiation no envio (`AgreementUseCases`), projeção só com o vencimento | guardar valor da parcela na projeção | valor fresco no envio; a projeção só precisa da data para o filtro (decisões 513 e 514) |
| 17 | Filtro de segmento "parcela vence em N dias" (faixa de dias até a próxima parcela em aberto) | régua com espera fixa desde a formalização | parcelas têm vencimentos próprios; a faixa + reinscrição cobre uma parcela por inscrição (decisão 514) |
| 18 | Link do portal pelo caso de uso do portal (`PortalLinkUseCases`), gerado por último antes do envio | campaign gerar token/URL | o portal é dono do link e da trilha; nenhuma verificação posterior descarta um link já gerado (decisão 515) |

## 11. Pendências

* Adaptador de `CampaignOfferPort` (alçada de campanha no negotiation + link no payment).
* Consumo de `CadenceExhausted` pelo legal (critério "régua extrajudicial esgotada").
* ~~Score no read model~~: implementado em 24/10/2026 — `campaign.case-score` consome `CaseScoreChanged` e grava o score na
  projeção (`score_calculated_at`, `V1101`; o evento é a fonte depois do primeiro score, ordenado pelo instante do cálculo).
* Frontend (construtor de segmento e régua, painel, resultados A/B).
* Lembrete de parcela com o link do portal: o devedor com todos os títulos fora de cobrança aberta não entra no portal
  (a etapa é pulada `PORTAL_UNAVAILABLE`).
* Envio por SMS depende do adaptador do communication (issue #12).
