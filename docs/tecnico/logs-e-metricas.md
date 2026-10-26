# Logs e métricas

## 1. Três fluxos separados

| Fluxo | Como gerar | Arquivo / destino | Persistência adicional |
| --- | --- | --- | --- |
| Técnico | logger da classe (`LoggerFactory.getLogger(X.class)`) | `technical.log` → `/gestor-cobranca/<env>/technical` | — |
| Negócio | `BusinessEventLogger.log(BusinessEvent...)` | `business.log` → `/gestor-cobranca/<env>/business` | `shared.business_event` (mesma transação) |
| Acesso | `AccessLogger.log(AccessEvent...)` | `access.log` → `/gestor-cobranca/<env>/access` | `shared.access_log` (transação própria) |

Os appenders são independentes (`additivity=false`): eventos de negócio e de acesso nunca aparecem no log técnico e vice-versa. Todos são JSON de uma linha (`JsonLogFormatter`), prontos para o CloudWatch Logs Insights.

## 2. Campos

Comuns: `@timestamp`, `level`, `stream`, `logger`, `thread`, `service`, `env`, `instance`, `message`, e do contexto (MDC) `requestId`, `tenantId`, `actorId`, `sessionId`, `clientIp`, `userAgent`, `route`, `process` (jobs).

- **Técnico, por requisição** (`logger=http.request`): `http.method`, `http.route` (padrão, sem ids), `http.status`, `durationMs`, `slow=true` acima de 2 s.
- **Negócio**: `eventType`, `eventId`, `occurredAt`, `creditorId`, `portfolioId`, `debtorId`, `caseId`, `referenceType`, `referenceId`, `channel`, `amount`, `durationMs`, `attr.*`.
- **Acesso**: `eventType`, `success`, `actorType`, `principal` (mascarado), `detail.*`.

Dados pessoais são mascarados automaticamente na mensagem; pares chave-valor devem conter apenas ids e valores não pessoais.

## 3. Catálogo de eventos de negócio

Definido em `shared/logging/business/BusinessEventTypes`. Os nomes são contrato com painéis e consultas — não renomear. Ao criar um tipo, adicione-o à tabela abaixo.

| Domínio | Eventos |
| --- | --- |
| Estrutura | `creditor.created`, `portfolio.created`, `portfolio.status_changed`, `policy.published` |
| Ingestão | `import.started`, `import.completed`, `import.failed`, `import.cancelled`, `import.format_confirmed`, `import.totals_matched`, `import.totals_mismatch`, `import.alerts_detected`, `import.outliers_reviewed`, `import.review_committed`, `import.review_approved`, `import.review_rejected` |
| Dívida | `debtor.consolidated`, `title.status_changed`, `collateral.registered`, `collateral.status_changed`, `collateral.appraised`, `obligation_party.registered`, `title.prescription_interrupted`, `title.prescription_approaching` |
| Operação | `case.opened`, `case.assigned`, `case.status_changed`, `case.eligibility_changed`, `case.closed`, `interaction.recorded`, `contact.effective`, `callback.scheduled`, `callback.kept`, `callback.missed`, `promise.registered`, `promise.kept`, `promise.broken`, `dispute.opened`, `dispute.resolved` |
| Negociação | `negotiation.simulated`, `proposal.created`, `proposal.approval_requested`, `proposal.handoff_requested`, `proposal.approved`, `proposal.rejected`, `proposal.accepted`, `proposal.expired`, `agreement.formalized`, `agreement.settled`, `agreement.broken`, `agreement.renegotiated`, `agreement.cancelled`, `agreement.break_suspended`, `agreement.break_suspension_ended`, `installment.overdue`, `installment.paid` |
| Pagamentos | `payment_instruction.created`, `payment.informed`, `payment.confirmed`, `payment.rejected`, `payment.reconciled`, `payment.reallocated`, `payment.divergence`, `payment.reversed` |
| Prestação de contas (settlement) | `commission.accrued`, `commission.reversed`, `commission.rules_published`, `settlement.receipt_pending_rules`, `settlement.calculated`, `settlement.adjusted`, `settlement.closed`, `settlement.exported`, `settlement.sent`, `settlement.acknowledged`, `settlement.contested`, `settlement.transfer_recorded` |
| Remuneração variável (incentive) | `attribution.recorded`, `commission.forecast`, `commission.entry_posted`, `commission.entry_cleared`, `commission.entry_reversed`, `commission.period_closed`, `commission.statement_disputed`, `commission.statement_adjusted`, `commission.statement_approved`, `commission.statement_exported`, `commission.plan_published`, `quality.evaluated` |
| Comunicação | `message.sent`, `message.delivered`, `message.read`, `message.failed`, `message.replied`, `message.received`, `message.blocked`, `conversation.handoff`, `conversation.closed`, `template.published` |
| Campanhas | `campaign.activated`, `campaign.paused`, `campaign.finished`, `campaign.step_executed`, `campaign.step_skipped`, `campaign.replied`, `campaign.cadence_exhausted`, `experiment.variant_assigned` |
| Compliance | `contact.opt_out`, `contact.restricted`, `contact.restriction_revoked`, `contact.denied`, `data_subject_request.opened`, `data_subject_request.completed`, `retention.run_completed` |
| Jurídico (legal) | `legal.matter_referred`, `legal.stage_changed`, `legal.matter_assigned`, `legal.viability_assessed`, `legal.authorization_requested`, `legal.authorization_decided`, `legal.measure_registered`, `legal.measure_status_changed`, `legal.lawsuit_filed`, `legal.movement_recorded`, `legal.recovery_recorded`, `legal.deadline_created`, `legal.deadline_approaching`, `legal.deadline_met`, `legal.deadline_missed`, `legal.hearing_scheduled`, `legal.hearing_held`, `legal.expense_recorded`, `legal.expense_cancelled`, `legal.expense_reimbursed`, `legal.agreement_linked`, `legal.agreement_broken`, `legal.titles_settled`, `legal.prescription_alert`, `legal.matter_closed` |
| Telefonia (telephony) | `call.dialed`, `call.blocked`, `call.completed`, `call.abandoned`, `call.tabulated`, `call.recording_stored`, `dialer.campaign_activated`, `dialer.campaign_paused`, `dialer.campaign_finished`, `agent.presence_changed` |
| Assistente de IA (assistant) | `assistant.replied`, `assistant.handoff` (publicado pelo communication na transferência), `assistant.handoff_triggered`, `assistant.draft_proposed`, `assistant.approval_requested`, `assistant.guardrail_blocked`, `assistant.reply_blocked`, `assistant.turn_failed`, `assistant.policy_retired`, `assistant.portfolio_disabled`, `assistant.review_recorded`, `assistant.pix_issued`, `assistant.pix_denied`, `assistant.pix_suggested`, `assistant.pix_delivery`, `assistant.pix_paid`; `policy.published` com `policyKind = assistant` |
| Portal de autonegociação (portal) | `portal.accessed`, `portal.simulated`, `portal.agreement_formalized`, `portal.pix_issued`, `portal.payment_confirmed`, `portal.service_requested`, `portal.configuration_changed` (§3.10) |
| Chat de suporte (helpdesk) | `helpdesk.question_answered`, `helpdesk.question_unanswered`, `helpdesk.feedback_recorded`, `helpdesk.ticket_drafted`, `helpdesk.ticket_opened`, `helpdesk.ticket_cancelled` (§3.12) |
| Score (scoring) | `score.calculated`, `score.batch_completed`, `score.training_completed`, `score.model_promoted`, `score.model_rejected`, `score.drift_detected`, `score.cohort_measured`, `score.reviewed` |
| Minhas pendências (pendingwork) | `pending_work.observed` (§3.11) |

### 3.1 Atributos dos eventos de compliance

| Evento | Quando | Atributos |
| --- | --- | --- |
| `contact.opt_out` / `contact.restricted` | restrição de contato criada (opt-out ou demais tipos) | `type`, `source`, `temporary`, `contactSpecific`; `channel` |
| `contact.restriction_revoked` | restrição revogada | `type`, `activeDays` |
| `contact.denied` | `ContactGate.authorizeAndRecord` negou um contato | `origin`, `reasons` (lista separada por vírgula); `channel` |
| `data_subject_request.opened` / `.completed` | solicitação de titular aberta / encerrada (concluída ou recusada) | `type`, `dueDays`; no encerramento `outcome`, `rejectionCause`, `withinDeadline`, `durationMs` |
| `retention.run_completed` | execução de retenção concluída (simulada ou efetiva) | `category`, `trigger`, `dryRun`, `candidates`, `eligible`, `processed`, `retained`, `failed` |

### 3.2 Atributos dos eventos de garantias, partes e prescrição (debt)

Nenhum evento leva placa, RENAVAM, chassi, matrícula, documento ou nome: apenas ids, enums e datas.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `collateral.registered` | garantia cadastrada (manual ou carga) | `type`, `assetKind`, `originContractId`, `source` (`MANUAL`/`IMPORT`) |
| `collateral.status_changed` | mudança de situação validada | `type`, `from`, `to`; `amount` = valor recuperável estimado |
| `collateral.appraised` | avaliação registrada | `type`, `appraisalSource`, `appraisalDate`, `estimatedValue`; `amount` = valor da avaliação |
| `obligation_party.registered` | coobrigado incluído no contrato | `role`, `originContractId`, `principalDebtorId`, `personType`, `source`; `debtorId` = coobrigado |
| `title.prescription_interrupted` | interrupção registrada (manual ou módulo legal) | `type`, `origin` (`MANUAL`/`LEGAL`), `hasEvidence`, `executionEstimatedDate`, `collectionEstimatedDate`, `extended` (alguma data estimada foi adiada); `amount` = saldo em aberto |
| `title.prescription_approaching` | varredura diária: data estimada entrou na janela da regra (uma vez por data) | `claim` (`EXECUTION`/`COLLECTION`), `instrumentType`, `executiveTitle`, `estimatedDate`, `daysRemaining`, `ruleValidated`, `titleStatus`; `amount` = saldo em aberto |

### 3.2a Atributos dos eventos de ingestão

Referência `import_batch`/id do lote, `creditorId`, `portfolioId`; nenhum dado de devedor. Ator: sistema (o processamento é assíncrono); quem enviou o lote vai em `requestedBy`.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `import.started` | leitura prévia concluída, antes da primeira gravação | `source` (`CSV`/`XLSX`/`API`), `mode` (`IMPORT`/`VALIDATE_ONLY`), `layoutCode`, `layoutVersion`, `totalRows`, `fileSize`, `forced`, `reprocess`, `ignoredColumns`, `requestedBy` |
| `import.completed` | lote concluído (`COMPLETED`, `COMPLETED_WITH_ERRORS` ou `VALIDATED`) | `source`, `mode`, `status`, `layoutCode`, `totalRows`, `rowsRead`, `rowsValid`, `rowsWithError`, `errors`, `warnings`, `debtorsCreated`, `debtorsUpdated`, `titlesCreated`, `titlesUpdated`, `titlesUnchanged`, `titlesSkipped`, `titlesSuspended`, `errorRate` (%), `requestedBy`; `amount` = principal das linhas válidas; `durationMs` |
| `import.failed` | arquivo inválido, carteira sem aceitar cargas, falha técnica persistente ou arquivo sem linhas | os de `import.completed` + `failureCode` |
| `import.cancelled` | cancelado na fila (`phase=queued`) ou em processamento | os de `import.completed` |

Conferência da importação (`docs/tecnico/desenho/importacao.md`): referência `import_review`/id da conferência (exceto o `import.totals_mismatch` da API, com `import_batch`), `creditorId`, `portfolioId`; ator = usuário (análise: sistema). Nenhum dado de devedor — só contagens, valores agregados e nomes de coluna.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `import.format_confirmed` | usuário confirma/decide a leitura das colunas monetárias | `batchId`, `decisions` (coluna → opção), `statuses` (coluna → `NOT_NEEDED`/`DETECTED`/`INFERRED`/`AMBIGUOUS`/`MIXED`), `decisionRequired`, `textValues`, `newPreview`, `requestedBy` |
| `import.totals_matched` / `import.totals_mismatch` | conferência de totais (`origin` = `REVIEW` ou `API`) | `batchId`, `measures`, `mismatched`, `scaleExponent` (n da razão 10ⁿ, ou nulo), `alternativeMatch`, `attempt`, `requestedBy`; `amount` = principal calculado |
| `import.alerts_detected` | análise de valores concluída | `batchId`, `alerts`, `stored`, `truncated`, `byType`, `reference` (referência → linhas), `rowsAnalyzed` |
| `import.outliers_reviewed` | efetivação pedida (resumo da revisão) | `batchId`, `alerts`, `byType` (tipo → situação → quantidade), `rowsExcluded`, `requestedBy` |
| `import.review_committed` | efetivação pedida | `batchId`, `rows`, `rowsToImport`, `rowsWithError`, `rowsExcluded`, `newTitles`, `existingTitles`, `approvalRequired`, `previews`, `totalsChecks`, `totalsMismatches`, `forced`, `requestedBy`; `amount` = principal a importar |
| `import.review_approved` / `import.review_rejected` | decisão da segunda pessoa | `batchId`, `requestedBy`, `approvedBy`/`rejectedBy`, `waitMinutes` |

### 3.3 Atributos dos eventos de remuneração variável

Remuneração individual **nunca** aparece em log (técnico ou de negócio): os eventos levam ids e faixas (`*Band`: `0`, `0-100`, `100-500`, `500-1000`, `1000-3000`, `3000-10000`, `10000+`). Totais agregados (`amount` de `commission.period_closed` e `commission.statement_exported`) só são preenchidos com 3 ou mais extratos; abaixo disso, apenas a faixa.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `attribution.recorded` | atribuição do acordo registrada (§3) | `origin`, `delayBucket`, `renegotiation`, `incrementalAmount`, `individualCredits`, `teamPool`, `influencerSource`, `planId`, `concessionLevel`, `envelopeUsage`; `amount` = valor do acordo |
| `commission.forecast` | comissão prevista do acordo (informativa) | `origin`, `forecastBand`, `delayBucket` |
| `commission.entry_posted` | crédito lançado no razão por pagamento confirmado | `agreementId`, `paymentId`, `beneficiaryType`, `beneficiaryId`, `role`, `origin`, `creditWeight`, `renegotiationFactor`, `creditNote`, `delayBucket`, `clearsOn`; `amount` = parte do pagamento (base, não comissão) |
| `commission.entry_cleared` | lançamento liberado da carência (ou dispensado) | `kind`, `status`, `beneficiaryType`, `beneficiaryId`, `origin`, `period` |
| `commission.entry_reversed` | compensação por estorno | `agreementId`, `paymentId`, `reversalId`, `reversesEntryId`, `beneficiaryType`, `beneficiaryId`, `origin`, `effect` (`WITHIN_GRACE`, `OPEN_PERIOD`, `CLAWBACK`, `WAIVED`), `status`; `amount` = base estornada |
| `commission.period_closed` | período fechado e extratos gerados | `period`, `trigger` (`SCHEDULED`/`MANUAL`), `statements`, `creditedRecovery`, `totalBand`, `costPerRecovered` (≥ 3 extratos) |
| `commission.statement_disputed` | contestação aberta pelo negociador | `disputeId`, `period`, `target` (`LINE`/`ENTRY`/`AGREEMENT`), `component` |
| `commission.statement_adjusted` | ajuste aplicado (direto ou após segunda aprovação) | `adjustmentId`, `fromDispute`, `direction` (`CREDIT`/`DEBIT`), `amountBand`, `dualApproval`, `period` |
| `commission.statement_approved` | aprovação financeira | `period`, `outcome`, `planCode`, `planVersion`, `payableBand`, `adjusted` |
| `commission.statement_exported` | exportação para a folha | `period`, `format`, `statements`, `totalBand` |
| `commission.plan_published` | versão de plano publicada (elasticidade antes/depois) | `code`, `version`, `effectiveFrom`, `tierMode`, `hasGoal`, `hasPool`, `hasFixedPerAgreement`, `graceBusinessDays`, `clawbackEnabled`, `creditModel` |
| `quality.evaluated` | monitoria registrada | `userId`, `formId`, `score`, `criticalFailure`, `referenceType` |

### 3.3 Atributos dos eventos de negociação (parte 1)

Ids e códigos apenas — nunca nome, documento ou contato do devedor, nem a observação livre da justificativa.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `policy.published` (`policyKind = collection`) | versão de política de cobrança publicada | `version`, `effectiveFrom`, `overrides`, `ladderLevels`, `maxInstallments`, `interestMode`, `hasCorrection` |
| `negotiation.simulated` | simulação registrada (humano ou assistente) | `actorType`, `profileCode`, `policyVersion`, `override`, `requestedLevel`, `grantedLevel`, `levelCapped`, `escalationReason`, `titles`, `excludedTitles`, `offers`, `validOffers`, `daysOverdue`, `bestOfferAmount`; `amount` = dívida atualizada; `channel` |

### 3.4 Atributos dos eventos de propostas e acordos (negotiation parte 2)

Todos levam `creditorId`, `portfolioId`, `debtorId`, `caseId`, `referenceType`/`referenceId` (`negotiation_proposal` ou `negotiation_agreement`) e `channel`. Nunca levam observação da justificativa, observação da decisão, motivo livre de cancelamento, IP ou agente do aceite. `actorType` permite comparar humanos e assistente.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `proposal.created` | proposta registrada | `actorType`, `profileCode`, `policyVersion`, `concessionLevel`, `installments`, `outcome` (WITHIN_AUTHORITY/REQUIRES_APPROVAL), `status`, `envelopeUsage`, `discountAmount`, `daysOverdue`, `renegotiation`, `fromHandoff`, `copilot`; `amount` = valor do acordo proposto |
| `proposal.approval_requested` | humano acima da alçada → fila de aprovação | `actorType`, `profileCode`, `concessionLevel`, `reasons` (códigos de alçada) |
| `proposal.handoff_requested` | assistente acima da alçada → transferência para humano | `actorType`, `concessionLevel`, `reasons` |
| `proposal.approved` / `proposal.rejected` | decisão do aprovador | `actorType` (do proponente), `approverProfile`, `concessionLevel`, `installments`, `envelopeUsage`; `durationMs` = espera na fila |
| `proposal.accepted` | aceite do devedor registrado (termo gerado) | `actorType`, `concessionLevel`, `installments`, `approved`; `channel` = canal do aceite |
| `proposal.expired` | validade vencida (job diário) | `previousStatus`, `actorType`, `concessionLevel` |
| `agreement.formalized` | acordo criado | `actorType` (de quem formalizou), `origin`, `concessionLevel`, `envelopeUsage`, `installments`, `policyVersion`, `daysOverdue`, `renegotiationNumber`, `totalAmount`, `discountAmount`, `incrementalAmount`, `replacedAgreementId`; `amount` = valor do acordo |
| `agreement.settled` / `agreement.broken` / `agreement.cancelled` / `agreement.renegotiated` | encerramento | mesmos atributos + `previousStatus`, `paidAmount`, `daysSinceFormalization` (safra), `replacedByAgreementId`; `amount` = valor pago |
| `agreement.break_suspended` | regra de quebra atingida com pagamento externo do acordo em revisão: quebra suspensa (job diário; decisões 540–549) | `suspensionId`, `pendingPayments`, `suspendedUntil`, `maxDays`, `overdueInstallments`, `triggerInstallmentNumber`, `daysOverdue` (da parcela que dispara), `oldestPendingHours` (espera do pendente mais antigo), `remainingAmount`, `origin`, `actorType`; `amount` = total em revisão |
| `agreement.break_suspension_ended` | fim da suspensão (na revisão, no job diário ou pelo prazo) | `suspensionId`, `reason` (APPROVED, REJECTED, DEADLINE, AGREEMENT_ENDED), `durationDays`, `suspendedUntil`, `graceUntil` (recusa), `stillPendingPayments`, `agreementStatus`, `origin`; `amount` = total em revisão no início. Insumos: tempo de revisão × prazo, taxa de recusa que leva à quebra, quantos acordos a suspensão salvou (APPROVED seguido de quitação) |
| `installment.overdue` | parcela vencida sem pagamento integral (job diário) | `installmentNumber`, `installments`, `dueDate`, `origin`, `actorType`, `previousStatus`; `amount` = saldo da parcela |
| `installment.paid` | parcela paga integralmente | idem; `amount` = valor da parcela |

Consulta a extrato, razão, prévia ou simulação de **outra pessoa** gera `COMPENSATION_DATA_VIEWED` no log de acesso; exportação gera `DATA_EXPORTED` e download `FILE_DOWNLOADED`.

### 3.3 Atributos dos eventos da operação (collection)

Todos levam `creditorId`, `portfolioId`, `debtorId`, `caseId` e os atributos `daysOverdue`, `overdueBand` (`0-30`, `31-90`,
`91-180`, `181-360`, `361+`) e `valueBand` do saldo em aberto (`0-500`, `500-1k`, `1k-5k`, `5k-20k`, `20k+`). Observações,
descrições e pareceres nunca entram no log (ficam criptografados no banco).

| Evento | Quando | Atributos |
| --- | --- | --- |
| `case.opened` | caso aberto por carga ou mudança de título | `trigger`, `openTitles`, `eligible`, `ineligibleReasons`, `queueId`; `amount` = saldo em aberto |
| `case.assigned` | responsável humano ou equipe mudou | `mode` (NEXT_CASE, DISTRIBUTION, MANUAL, REDISTRIBUTION, RELEASE, INTERACTION, TAKE_OVER, ASSISTANT_HANDOFF…), `ownerType`, `assigneeId`, `previousAssigneeId`, `teamId`, `queueId`, `eligible`; `amount` = saldo elegível atribuído |
| `case.status_changed` | mudança de status (exceto encerramento) | `from`, `to`, `trigger`, `assigneeId` |
| `case.eligibility_changed` | caso passou a elegível ou inelegível | `eligible`, `reasons`, `previousReasons`, `trigger`; `amount` = saldo elegível |
| `case.closed` | encerramento | `reason`, `from`, `trigger`, `durationDays`, `interactions`, `effectiveContacts`, `assigneeId`; `durationMs` desde a abertura |
| `interaction.recorded` | interação registrada | `direction`, `outcomeCode` (tabulação), `effectiveContact`, `promise`, `callback`, `source`, `actorType`, `operatorId`, `caseStatus`, `interactionNumber`; `channel`; `durationMs` quando informada |
| `contact.effective` | interação com contato efetivo (CPC) | `direction`, `outcomeCode`, `operatorId`, `attemptsUntilContact`, `firstEffectiveContact`; `channel`; `amount` = saldo elegível; `durationMs` |
| `callback.scheduled` | retorno agendado | `assigneeId`, `leadHours`, `dueHour` (hora local), `originInteractionId` |
| `callback.kept` | retorno cumprido até a tolerância | `assigneeId`, `completedBy`, `minutesFromDue`; `durationMs` após o horário |
| `callback.missed` | retorno atrasado (tolerância vencida) | `assigneeId`, `minutesLate` |
| `promise.registered` | promessa registrada | `promiseValueBand`, `daysToDue`, `coverage` (prometido ÷ saldo), `operatorId`; `channel`; `amount` = prometido |
| `promise.kept` / `promise.broken` | promessa cumprida / quebrada | `paidAmount`, `promiseValueBand`, `daysFromDue`, `partial`, `operatorId`; `amount` = prometido; `durationMs` desde o registro |
| `dispute.opened` | contestação aberta | `type`, `origin`, `blocksCollection`, `titles`, `responseDays`; `amount` = valor contestado |
| `dispute.resolved` | contestação resolvida ou retirada | `type`, `origin`, `status`, `outcome`, `durationDays`, `withinDeadline`, `evidences`; `amount`; `durationMs` |

### 3.4 Atributos dos eventos de comunicação

Nunca conteúdo de mensagem, contato, nome ou resumo de transferência (ficam criptografados no banco). Todos levam
`creditorId`, `portfolioId`, `debtorId`, `caseId` (quando identificados), `channel` e, nos de mensagem,
`referenceType=message` com `conversationId`, `direction`, `actorType` (autor: USER, ASSISTANT, CAMPAIGN, SYSTEM,
DEBTOR), `ownerType` (dono da conversa) e, com template, `templateCode`, `templateVersion`, `purpose`;
`campaignExecutionId` quando houver.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `message.blocked` | porteiro negou (verificação prévia ou no envio) | `stage` (`precheck`/`send`), `reasons` |
| `message.sent` | provedor aceitou | `provider`, `attempts`; `durationMs` do enfileiramento ao aceite |
| `message.delivered` / `message.read` | status do provedor | `durationMs` desde o envio |
| `message.failed` | recusa do provedor, conta indisponível, janela encerrada ou tentativas esgotadas | `failureCode`, `attempts` |
| `message.replied` / `message.received` | mensagem do devedor (com / sem envio anterior na conversa) | `contentType`, `identified`, `optOut` |
| `conversation.handoff` / `assistant.handoff` | mudança de dono (o segundo quando o assistente transfere para humanos) | `kind`, `reason`, `from`, `to`, `fromUserId`, `toUserId`, `requiredProfile`, `referenceType`, `awaitingReply`; `durationMs` com o dono anterior |
| `conversation.closed` | conversa encerrada | `from`, `messages`, `identified`; `durationMs` desde a abertura |
| `conversation.sla_breached` | conversa aberta cruzou o limite de primeira resposta sem resposta (varredura; uma vez por espera) | `referenceType=conversation`, `owner` (ASSISTANT/QUEUE/USER/NONE), `ownerUserId`, `requiredProfile`, `breachMinutes`; `durationMs` = limite vigente |
| `template.published` | versão de template publicada | `code`, `templateVersion`, `purpose`, `variables`, `replacedVersion` |

### 3.5 Atributos dos eventos do jurídico (legal)

Todos levam `creditorId`, `portfolioId`, `debtorId`, `caseId` (caso de cobrança) e referência `legal_matter` (ou o registro indicado). Nunca levam número de documento, nome, parecer, teor de andamento ou participantes; o número CNJ fica só na auditoria. `matterId` aparece como atributo quando a referência é outro registro.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `legal.matter_referred` | indicação (regra, alerta de prescrição, régua extrajudicial esgotada ou manual — `origin` `RULE`, `PRESCRIPTION_ALERT`, `CADENCE_EXHAUSTED`, `MANUAL`) | `origin`, `ruleId`, `titles`, `excludedTitles`, `flags`, `prescribedExcluded`; `amount` = dívida |
| `legal.stage_changed` | toda transição de fase | `from`, `to`, `route`, `responsibleType`, `lawFirmId`, `cause` (`manual`, `measure`, `lawsuit`, `movement`, `capture`, `agreement`), `collectionHold`; `amount` = dívida |
| `legal.matter_assigned` | responsável alterado | `responsibleType`, `previousType`, `responsibleUserId`, `lawFirmId`, `stage` |
| `legal.viability_assessed` | análise registrada | `viable`, `economicallyViable`, `route`, `routes`, `debtAmount`, `collectibleDebt`, `collateralValue`, `expectedCosts`, `netBenefit`, `costBenefitRatio`, `successProbability`, `flags`, `checklistMissing`; `amount` = valor recuperável estimado |
| `legal.authorization_requested` / `legal.authorization_decided` | solicitação / decisão do credor | `routes`, `stage`, `cause`; na decisão `decision`, `channel`, `requestedBudget`, `hasEvidence`, `supplementary`, `daysToDecision`; `amount` = orçamento |
| `legal.measure_registered` / `legal.measure_status_changed` | medida extrajudicial registrada / mudou de situação | `type`, `titles`, `collateralType`, `provider`, `stage`; na mudança `from`, `to`, `prescriptionInterruptions`, `daysSinceSubmission`; `amount` = valor da medida |
| `legal.lawsuit_filed` | processo cadastrado | `route`, `courtCode`, `segment`, `defendants`, `creditorPole`, `responsibleType`, `lawFirmId`, `daysSinceReferral`; `amount` = valor da causa |
| `legal.movement_recorded` | andamento manual ou capturado | `lawsuitId`, `type`, `source`, `provider`, `route`, `courtCode`, `prescriptionInterruptions`, `stage`; `amount` |
| `legal.recovery_recorded` | levantamento de valores | `route`, `recoveredTotal`, `stage`; `amount` = valor levantado |
| `legal.deadline_created` / `_approaching` / `_met` / `_missed` | ciclo do prazo | `kind`, `countingMode`, `termDays`, `calendarDays`, `source`, `level`, `businessDaysRemaining`, `dueDate`, `onTime`, `correctedFromMissed`, `daysBeforeDue`, `responsibleUserId`, `lawFirmId` |
| `legal.hearing_scheduled` / `legal.hearing_held` | audiência agendada / realizada | `type`, `mode`, `daysAhead`, `outcome`, `rescheduleCount` |
| `legal.expense_recorded` / `_cancelled` / `_reimbursed` | custas e despesas | `type`, `advancedBy`, `reimbursable`, `overBudget`, `hasReceipt`, `expensesTotal`, `authorizedBudget`, `daysToReimburse`; `amount` |
| `legal.agreement_linked` / `legal.agreement_broken` | acordo do negotiation vinculado / acordo judicial quebrado ou cancelado | `agreementId`, `judicial`, `status`, `resumedStage`, `cause`; `amount` = valor do acordo |
| `legal.titles_settled` | todos os títulos do caso jurídico saíram de cobrança | `stage`, `titleStatuses`, `removalDeadlines` |
| `legal.prescription_alert` | alerta de prescrição do debt para título de caso jurídico aberto | `titleId`, `claim`, `estimatedDate`, `daysRemaining`, `ruleValidated`, `stage` |
| `legal.matter_closed` | `CLOSED` ou `RETURNED_TO_CREDITOR` | `stage`, `from`, `closureReason`, `route`, `durationDays`, `debtAmount`, `expensesTotal`, `recoveredAmount`, `responsibleType`, `lawFirmId`, `deadlinesCancelled`, `removalDeadlines`; `amount` = valor recuperado; `durationMs` |
### 3.6 Atributos dos eventos de campanhas

Referência `campaign` (eventos da campanha) ou `campaign_enrollment` (eventos de inscrição, com `creditorId`,
`portfolioId`, `debtorId`, `caseId` e os atributos `campaignId`, `campaignCode`, `variant`, `control`). Nunca nome,
documento, contato, valores de variáveis ou conteúdo de mensagem.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `campaign.activated` | campanha ativada ou reativada | `scope=campaign`, `campaignCode`, `purpose`, `cadenceVersion`, `priority`, `exclusivityGroup`, `segmentId`, `resumed` |
| `campaign.paused` | campanha pausada (`scope=campaign`, `reason=MANUAL`) ou inscrição pausada (`scope=enrollment`) | `reason` (`PAYMENT_CONFIRMED`, `AGREEMENT_FORMALIZED`, `DISPUTE_OPENED`, `CONTACT_RESTRICTED`, `CASE_SUSPENDED`, `CASE_LEGAL`, `MANUAL`), `trigger`, `stepsExecuted`, `lastStepCode` |
| `campaign.finished` | campanha encerrada | `campaignCode`, `endedEnrollments`; `durationMs` desde a ativação |
| `campaign.step_executed` | etapa executada | `cadenceVersion`, `stepCode`, `iteration`, `actionType`, `messageId`, `taskId`; `channel`; `durationMs` da execução |
| `campaign.step_skipped` | etapa pulada | `cadenceVersion`, `stepCode`, `iteration`, `actionType`, `reason` (`CASE_INELIGIBLE`, `CONDITION_NOT_MET`, `CONTACT_DENIED`, `NO_CONTACT`, `OFFER_UNAVAILABLE`, `NO_ACTIVE_AGREEMENT`, `NO_OPEN_INSTALLMENT`, `PORTAL_UNAVAILABLE`, `NOT_APPLICABLE`), `reasonDetail` (códigos: motivos do porteiro, critérios não atendidos); `channel` |
| `campaign.replied` | primeira resposta do devedor numa inscrição ativa | `transition` (RB07: `STOP`, `GOTO_STEP`, `HAND_TO_ASSISTANT`, `MOVE_TO_QUEUE`, `CONTINUE`), `lastStepCode`, `stepsExecuted`; `channel`; `durationMs` desde a última etapa |
| `campaign.cadence_exhausted` | régua percorrida sem resultado (insumo do jurídico) | `purpose`, `cadenceVersion`, `stepsExecuted`, `stepsSkipped`, `messagesSent`; `durationMs` desde a inscrição |
| `experiment.variant_assigned` | braço do teste A/B atribuído ao devedor (uma vez) | `experimentId`, `arm`, `control`, `bucket` |
### 3.7 Atributos dos eventos de pagamentos (payment)

Ids, enums e valores apenas — nunca documento, nome, observação, descrição de extrato ou chave Pix. Referência `payment` (ou `payment_instruction`/`payment_divergence`), `creditorId`, `debtorId` e, quando alocado, `portfolioId`/`caseId`. Atributos comuns `source` (`PROVIDER`/`EXTERNAL`) e `method`.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `payment_instruction.created` | instrução criada | `type`, `targetKind`, `agreementId`, `installmentNumber`, `dueDate`, `expiresInMinutes`; `channel`; `amount` |
| `payment.informed` | pagamento externo registrado | `origin`, `reviewRequired`, `reviewReason` (`AMOUNT`, `DESTINATION_CUMULATIVE`, `INFORMER_CUMULATIVE`; ausente se confirmado sem revisão), `targetKind`, `hasEvidence`, `informedBy`, `daysSincePayment`; `amount` |
| `payment.confirmed` | confirmado (provedor, extrato, baixo risco ou aprovação) | `trigger` (`provider`, `statement`, `auto_low_risk`, `review`), `reviewMode`, `allocations`, `allocatedAmount`, `unallocatedAmount`, `leftoverReason`, `targetKind`, `agreementId`, `instructionType`, `delayMinutes`; `channel` da instrução; `amount`; `durationMs` do pagamento à confirmação |
| `payment.rejected` | informado recusado na revisão | `origin`, `reviewedBy`; `durationMs` na fila |
| `payment.reconciled` | conciliado | `mode` (`PROVIDER_API`, `STATEMENT`, `MANUAL`), `statementImportId`, `daysFromPayment` |
| `payment.reallocated` | sobra/não aplicado realocado ou devolvido | `fromKind`, `fromAgreementId`, `toKind`, `toAgreementId`; `amount` |
| `payment.divergence` | divergência aberta ou resolvida | `type`, `source`, `status`, `paymentId`, `expectedAmount`, `resolution`, `resolvedBy`; `durationMs` até a resolução |
| `payment.reversed` | estorno | `reason`, `reversalSource`, `previousStatus`, `allocations`, `daysSinceConfirmation`; `amount` |

### 3.7 Atributos dos eventos da telefonia

Referência `call` (ou `dialer_campaign`/`telephony_agent`), canal `PHONE`, `creditorId`, `portfolioId`, `debtorId` e
`caseId` quando identificados, e `direction`, `origin` (`CLICK_TO_CALL`, `DIALER`, `INBOUND`), `operatorId`,
`campaignId`, `provider` (`SANDBOX`, `ALVARIA`, `TARIFANDO`), `accountId` e `fallback` (discada pela conta de
contingência) — base da comparação de efetividade entre plataformas. Nunca número (nem mascarado), nome, ramal ou credenciais. Ver `docs/tecnico/desenho/telefonia.md`.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `call.dialed` | provedor aceitou a discagem (tentativa registrada no compliance) | `attemptNumber`, `recording` |
| `call.blocked` | porteiro negou (verificação prévia do clique para ligar ou imediatamente antes de discar) | `stage` (`precheck`/`dial`), `reasons`, `deferrable` (há próximo instante permitido), `attemptNumber` |
| `call.completed` | chamada que chegou ao provedor encerrada | `status`, `result` (`ANSWERED`, `NO_ANSWER`, `BUSY`, `VOICEMAIL`, `FAILED`, `CANCELLED`, `ABANDONED`), `attemptNumber`, `amd`, `abandoned`, `hangupSide`, `ringSeconds`, `talkSeconds`, `failureCode`, `recording`; `durationMs` = conversação |
| `call.abandoned` | atendida por pessoa sem operador livre (discador) | `attemptNumber`, `amd` |
| `call.tabulated` | tabulação do operador (`mode=operator`) ou automática por prazo (`mode=deadline`) | `tabulationCode`, `result`, `origin`, `effectiveContact`, `promise`, `callback`, `wrapUpSeconds`; `durationMs` = pós-atendimento |
| `call.recording_stored` | gravação copiada para o armazenamento próprio | `recordingId`, `durationSeconds`, `announced`, `sizeBytes` |
| `dialer.campaign_activated` / `_paused` / `_finished` | ciclo da campanha de discagem | `campaignCode`, `mode`, `maxDialRatio`, `maxAbandonRate`, `maxAttempts`, `queueId`; na ativação `resumed`; no encerramento `excludedEntries` e `durationMs` desde a ativação |
| `agent.presence_changed` | mudança de presença do operador | `userId`, `from`, `to`, `fromReason`, `reason` (motivo da pausa), `productivePause`, `campaignId`, `trigger` (`operator`, `call`, `tabulation`, `supervisor`, `timeout`, `configuration`); `durationMs` no estado anterior |

### 3.8 Atributos dos eventos da prestação de contas (settlement)

Comissão da assessoria junto ao credor (não é remuneração de pessoa): os eventos levam valores. Referência `settlement_receipt` (recebimento) ou `settlement_statement` (demonstrativo); `creditorId`, `portfolioId` e `debtorId` (id) quando houver. Nunca nome, documento ou contato do devedor. Ver `docs/tecnico/desenho/prestacao-de-contas.md`.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `commission.accrued` | recebimento apurado no razão de comissão | `entryId`, `allocationId`, `agreementId`, `contractId`, `commission`, `fixedFee`, `agencyHonorarium`, `creditorHonorarium`, `rate`, `ruleCode`, `ruleVersion`, `paymentType`, `daysPastDue`, `receivedBy`, `renegotiation`, `method`, `paidOn`; `amount` = valor recuperado |
| `commission.reversed` | estorno/realocação compensado no razão | `entryId`, `allocationId`, `agreementId`, `contractId`, `commission`, `fixedFee`, `agencyHonorarium`, `reason`, `reversesEntryId`, `ruleVersion`, `finalReversal`; `amount` = valor estornado (negativo) |
| `commission.rules_published` | versão de regras publicada (elasticidade antes/depois) | `contractId`, `ruleVersion`, `effectiveFrom`, `defaultRate`, `specificRules`, `fixedFeePerAgreement`, `honorariumOwnership`, `honorariumAgencyShare`, `reconciliationRequired` |
| `settlement.receipt_pending_rules` | recebimento sem contrato ou regra vigente (aguarda publicação) | `reason` (`NO_CONTRACT`/`NO_RULES`), `paidOn`, `contractId`; `amount` |
| `settlement.calculated` | demonstrativo calculado (ou recalculado) | `sequenceNumber`, `periodStart`, `periodEnd`, `recovered`, `commission`, `pendingReconciliation`, `pendingRules`, `openDivergences`, `receipts`; `amount` = líquido a repassar |
| `settlement.adjusted` | ajuste aplicado (direto ou após segunda aprovação) | `adjustmentId`, `category`, `dualApproval`, `relatedStatementId` + identificação do demonstrativo; `amount` = efeito do ajuste |
| `settlement.closed` | demonstrativo aprovado (fechado e imutável) | `recovered`, `reversed`, `commission`, `fixedFees`, `agencyHonorarium`, `expenses`, `adjustments`, `receipts`, `effectiveRate` (comissão + fixos ÷ recuperado, %), `negotiated`, `forecastCommission`, `pendingReconciliation`, `openDivergences`, `daysAfterPeriod`; `amount` = líquido a repassar |
| `settlement.exported` | arquivo do demonstrativo gerado | `format`, `rows`, `sequenceNumber` |
| `settlement.sent` | demonstrativo enviado ao credor | `channel`, `exports`, `hoursAfterApproval` |
| `settlement.acknowledged` / `settlement.contested` | resposta do credor registrada | `daysAfterSent`; aceite: `afterContestation`; contestação: `amount` = líquido |
| `settlement.transfer_recorded` | repasse (ou recebimento do credor) registrado com comprovante | `statementId`, `direction`, `outstandingAfter`, `daysAfterApproval`; `amount` |

### 3.8 Atributos dos eventos do score (scoring)

Ids, faixas e valores apenas — nunca dado pessoal nem os valores das features. Score 0–1000 (probabilidade × 1000).

| Evento | Quando | Atributos |
| --- | --- | --- |
| `score.calculated` | score publicado (primeiro, mudança de faixa ou variação mínima) ou cálculo fora do lote (sob demanda, evento, revisão) | `creditorId`, `portfolioId`, `debtorId`, `caseId`, referência `case_score`; `score`, `band`, `previousBand`, `bandChanged`, `published`, `trigger` (`BATCH`, `ON_DEMAND`, `EVENT`, `REVIEW`), `modelKind`, `modelVersion`, `outcomeDays`, `caseStatus`, `daysOverdue`; `amount` = saldo em aberto |
| `score.batch_completed` | escoragem em lote concluída | referência `scoring_run`; `referenceDate`, `trigger`, `snapshot`, `scored`, `skipped`, `published`, `bandChanges`, `failures`, `modelKind`, `modelVersion`, `challenger`; `durationMs` |
| `score.training_completed` | treino encerrado | `outcome` (`challenger`, `failed_validation`, `insufficient_data`, `error`), `trigger`, `observations`, `distinctCases`, `distinctPositiveCases`, `modelVersion`, `status`, `validationAuc`, `trainingRows`, `validationRows`, `reasons`; `durationMs` |
| `score.model_promoted` / `score.model_rejected` | decisão manual sobre o desafiante | referência `scoring_model`; `modelVersion`, `kind`, `previousChampionVersion`, `validationAuc`, `decisionId`, `hasRealized` |
| `score.drift_detected` | monitoramento com atenção/alerta | referência `scoring_monitoring`; `status`, `modelVersion`, `modelKind`, `referenceDate`, `scorePsi`, `alerts`, `kinds` (`score_psi:score`, `feature_psi:<feature>`, `realized_auc:<data>`) |
| `score.cohort_measured` | recuperado × esperado de uma safra apurado | referência `scoring_run`; `referenceDate`, `outcomeDays`, `cohorts`, `cases`, `expectedAmount`, `realizedAmount`, `ratio`; `amount` = realizado |
| `score.reviewed` | revisão do score (LGPD art. 20) | `creditorId`, `portfolioId`, `debtorId`, `caseId`, referência `score_review`; `origin`, `previousBand`, `band`, `bandChanged`, `modelChanged` |

### 3.9 Atributos dos eventos do assistente de IA (assistant)

Eventos de turno levam `creditorId`, `portfolioId`, `debtorId`, `caseId`, `channel`, referência `assistant_turn`/id do turno e os atributos comuns `conversationId`, `mode`, `policyId`, `policyVersion`, `promptVersion` (`código@versão`), `episode`, `turn`. Nunca levam texto do devedor, resposta, nome, documento ou contato; o conteúdo fica cifrado na trilha (`assistant.assistant_turn`). `amount` = total da proposta corrente, quando houver.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `assistant.replied` | resposta enviada ao devedor (passou pelos guardrails e pelo porteiro) | `intent`, `toolCalls`, `modelCalls`, `identity`, `inputTokens` (prompt inteiro, com e sem cache), `outputTokens`, `cacheReadTokens`, `cacheWriteTokens`, `modelTier` (maior nível de modelo do turno: `STANDARD`/`ADVANCED`; ausente sem chamada ao modelo), `advancedModelCalls`, `cost`, `costCurrency`, `proposalId`, `agreementId`; `durationMs` = turno |
| `assistant.handoff_triggered` | o turno decidiu transferir (o communication registra `assistant.handoff` na mudança de dono) | os de `assistant.replied` + `reason` (motivo de `HandoffReason`), `source` (`RULE`, `MODEL`, `GUARDRAIL`, `LIMIT`, `ENGINE`, `POLICY`, `FAILURE`), `requiredProfile` |
| `assistant.approval_requested` | aceite registrado; formalização por humano (modo com aprovação ou perfil sem `canFormalize`) | os de `assistant.handoff_triggered`; `proposalId` |
| `assistant.draft_proposed` | rascunho/sugestão para o operador (sombra/copiloto) | os de `assistant.replied` |
| `assistant.guardrail_blocked` | guardrail recusou a resposta | `codes` (lista), `action` (`FALLBACK_HANDOFF` no autônomo, `DRAFT_SUPPRESSED` no rascunho) |
| `assistant.reply_blocked` | porteiro de contato negou o envio | `reasons` (motivos do compliance) |
| `assistant.turn_failed` | falha técnica (provedor, tempo esgotado, turno interrompido) | `errorCode`; transferência registrada à parte |
| `policy.published` (`policyKind = assistant`) | versão de política do assistente publicada | `code`, `version`, `mode`, `channels`, `promptCode`, `promptVersion`, `customAuthority`, `maxTurns`, `reviewSampleRate`, `extraTriggers`, `pixInConversation`, `pixMaxAmount`, `modelRouting` (`BY_STAGE`/`ADVANCED_ONLY`) |
| `assistant.policy_retired` | versão aposentada | `code`, `version`, `mode` |
| `assistant.portfolio_disabled` | desligamento imediato da carteira | `previousMode`, `previousPolicyId` |
| `assistant.review_recorded` | avaliação humana de turno amostrado | `score`, `verdict`, `sampleReason`, `tags`, `policyId` |

Eventos do Pix na conversa (ADR-0009) levam referência `assistant_pix_request`/id do pedido, `creditorId`, `portfolioId`, `debtorId`, `caseId`, `channel` e os atributos comuns `conversationId`, `turnId`, `mode`, `policyId`, `decision`, `agreementId`, `installmentNumber`, `installmentKind` (`SINGLE`, `DOWN_PAYMENT`, `INSTALLMENT`); `amount` = saldo da parcela (valor do Pix). Nunca o "copia e cola", txid, chave ou texto do devedor.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `assistant.pix_issued` | Pix gerado e liberado para envio (turno respondeu ao devedor) | `reusedInstruction`, `expiresInMinutes` |
| `assistant.pix_denied` | critério do servidor recusou | `reason` (`PIX_DISABLED`, `MODE_NOT_ALLOWED`, `IDENTITY_NOT_VERIFIED`, `NO_FORMALIZED_AGREEMENT`, `OTHER_PARTY`, `INSTALLMENT_NOT_OPEN`, `NOT_FORMALIZED_BY_HUMAN`, `CONVERSATION_RISK`, `OPEN_DISPUTE`, `NO_EXPLICIT_REQUEST`, `AMOUNT_ABOVE_LIMIT`, `DAILY_LIMIT_REACHED`, `PAYMENT_UNAVAILABLE`), `failedCriteria` (lista) |
| `assistant.pix_suggested` | sombra/copiloto: critérios atendidos, sugestão ao operador | — |
| `assistant.pix_delivery` | entrega concluída, recusada ou cancelada com o turno | `outcome` (`DELIVERED`, `NOT_DELIVERED`, `CANCELLED`), `code` (`CONTACT_DENIED`, `CONVERSATION_LEFT_ASSISTANT`, `INSTRUCTION_FAILED`, `INSTRUCTION_EXPIRED`, `INVALID_PIX_CODE`, `STATIC_PIX_CODE`, `AMOUNT_MISMATCH`, `ALREADY_PAID`, `TURN_<resultado>`…), `instructionId`; `durationMs` = pedido → entrega |
| `assistant.pix_paid` | Pix gerado pelo assistente pago (evento `PaymentInstructionStatusChanged`) | `paidWithin24h`, `delivered`, `minutesToPay`; `durationMs` = pedido → pagamento |

### 3.10 Atributos dos eventos do portal de autonegociação (portal)

Sem documento, contato, nome ou IP: só ids, canal `PORTAL`, valores e códigos. Funil por sessão: `portal.accessed` → `portal.simulated` → `portal.agreement_formalized` → `portal.pix_issued` → `portal.payment_confirmed`.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `portal.accessed` | sessão aberta (código ou link, e conhecimento quando exigido) | `method` (`OTP`/`LINK`), `knowledgeChallenge`; referência `portal_session` |
| `portal.simulated` | ofertas ou simulação do devedor (registrada no negotiation) | `offers`, `grantedLevel`, `plans`, `daysOverdue`; `amount` = dívida atualizada; referência `negotiation_simulation` |
| `portal.agreement_formalized` | acordo fechado no portal | `installments`, `concessionLevel`, `discountAmount`, `origin` (`PORTAL_SELF_SERVICE`), `method`; `durationMs` = desde a abertura da sessão; `amount` = total |
| `portal.pix_issued` | Pix da parcela emitido ou reaproveitado (2ª via) | `installmentNumber`, `reused`; `amount` |
| `portal.payment_confirmed` | primeiro pagamento confirmado de acordo fechado no portal | `installments`, `method`; `durationMs` = desde a formalização; `amount` |
| `portal.service_requested` | pedido de atendimento humano | `reason` (`TALK_TO_AGENT`, `DISPUTE`, `CANNOT_AFFORD`, `OTHER`), `preferredChannel`, `withNote` |
| `portal.configuration_changed` | configuração do portal criada/alterada | `operation`, `enabled`, `portfolios`, `knowledgeChallenge`, `privacyNoticeVersion`, `noticeChanged` |

Log de acesso (segurança): `INTEGRATION_ENDPOINT_CHANGED` (`action` `created`/`changed`, `pixAccountId`, hosts atual e anterior, `webhookAuth`, `active`) a cada criação ou troca de endereços de conta Pix; `MFA_RESET` traz `targetIsAdmin`; `ACCESS_DENIED` traz `reason` `target_outranks_actor`/`role_outranks_actor` (com `operation` e `missingPermissions`), `reserved_role_code`, `admin_role_reduction_requires_admin`, `admin_mfa_reset_requires_other_admin`. Eventos gravados com IP de cliente inválido trazem `clientIpInvalid=true` e `client_ip` nulo.

Log de acesso: `PORTAL_CODE_REQUESTED` (inclusive para documento inexistente, com `match` só no log), `PORTAL_LOGIN_SUCCESS`, `PORTAL_LOGIN_FAILURE` (`reason`: `wrong_code`, `wrong_knowledge`, `no_match`, `challenge_closed`, `unknown_link`, `link_unusable`…), `PORTAL_LOCKED` (`key` `DOCUMENT`/`IP`, `until`), `PORTAL_LINK_ISSUED` (`linkId`, `channel`, `delivered`, `origin` `OPERATOR`/`CAMPAIGN`, `reference`; nunca a URL), `PORTAL_LOGOUT`, `PORTAL_SESSION_REVOKED`, `PORTAL_PRIVACY_ACKNOWLEDGED`; documento sempre mascarado.

### 3.11 Envelhecimento das pendências (pendingwork)

`pending_work.observed`: **primeira** consulta do dia em que um tipo de pendência aparece para o usuário (`GET /api/v1/me/pending-work`, tela inicial ou indicador da barra superior). Único por usuário, tipo e dia (tabela `pendingwork.daily_observation`, retenção 30 dias). Ator = o usuário; sem credor, devedor ou valor. Insumo de gestão: quais pendências envelhecem, para quem e com que frequência ficam vencidas. Desenho em `tecnico/desenho/pendencias.md`.

| Atributo | Conteúdo |
| --- | --- |
| `itemType` | código estável da pendência (ex.: `payment.external_review`, `legal.deadlines_due`) |
| `source` | módulo dono (`payment`, `negotiation`, `ingestion`, `settlement`, `incentive`, `compliance`, `collection`, `communication`, `legal`, `assistant`, `scoring`) |
| `total`, `overdue`, `dueToday`, `severity` | contagens e destaque (`OVERDUE`, `DUE_TODAY`, `NORMAL`) no momento da observação |
| `oldestAgeHours`, `ageBand` | idade do item mais antigo e faixa (`<1d`, `1-3d`, `3-7d`, `7-30d`, `30d+`) |
| `nextDueDate` | prazo mais próximo, quando a pendência tem prazo |

Consulta sugerida: `filter eventType = "pending_work.observed" | stats count(*), avg(attr.oldestAgeHours), sum(attr.overdue > 0) by attr.itemType, attr.ageBand`.

### 3.12 Atributos dos eventos do chat de suporte (helpdesk)

Nunca o texto da pergunta, da resposta ou do chamado, nome, documento ou contato: só ids, códigos de catálogo, contagens e consumo. Referência `helpdesk_message` (perguntas e avaliações) ou `support_ticket` (chamados); ator = o usuário interno.

| Evento | Quando | Atributos |
| --- | --- | --- |
| `helpdesk.question_answered` / `helpdesk.question_unanswered` | resposta gravada (`ANSWERED` × `UNANSWERED`, `OUT_OF_SCOPE` ou `FAILED`) | `conversationId`, `turn`, `outcome`, `topic` (`HOW_TO`, `DATA_LOOKUP`, `BUSINESS_RULE`, `ACCESS`, `SYSTEM_ERROR`, `OUT_OF_SCOPE`, `OTHER`), `screen` (chave da tela do manual), `module`, `ticketDrafted`, `toolCalls`, `modelCalls`, `inputTokens`, `outputTokens`, `cost`, `costCurrency`, `model`, `promptVersion`, `knowledgeVersion`, `errorCode`; `durationMs` |
| `helpdesk.feedback_recorded` | avaliação 👍/👎 (inclusive troca) | `conversationId`, `rating` (`UP`/`DOWN`), `reason` (catálogo), `screen`, `topic`, `outcome` |
| `helpdesk.ticket_drafted` | IA preparou o chamado | `conversationId`, `screen` |
| `helpdesk.ticket_opened` | issue aberta (ou duplicado de uma aberta) | `conversationId`, `screen`, `duplicate`, `duplicateOf`, `attempts`, `recovered` (encontrada pelo marcador), `minutesToConfirm`, `issueNumber` |
| `helpdesk.ticket_cancelled` | pessoa decidiu não enviar | `conversationId`, `screen` |

Log de acesso: `SUPPORT_DATA_QUERIED` (`via=helpdesk`, `tool`, `outcome` `ok`/`not_found`, `results`, `resourceIds` — até 20 —, `conversationId`, `screen`) a cada consulta de dados do chat; `ACCESS_DENIED` com `via=helpdesk`, `tool` e `permission` quando a ferramenta exige permissão que a pessoa não tem; `SUPPORT_TICKET_OPENED` (`repository`, `issueNumber`, `ticketId`) na abertura da issue.

## 4. Consultas úteis (CloudWatch Logs Insights)

```
# p95 de latência por rota (técnico)
filter logger = "http.request"
| stats pct(durationMs, 95) as p95, count(*) as req by `http.route`
| sort p95 desc

# erros por tenant (técnico)
filter level = "ERROR" | stats count(*) by tenantId, logger

# falhas do Amazon Bedrock por modelo e código da AWS (técnico — sem conteúdo da conversa)
filter provider = "bedrock" and level = "WARN"
| stats count(*) by model, tier, outcome, errorCode

# custo e nível de modelo dos turnos do assistente por credor (negócio)
filter eventType in ["assistant.replied", "assistant.handoff_triggered", "assistant.draft_proposed"]
| stats sum(`attr.cost`) as custo, sum(`attr.advancedModelCalls`) as avancadas, sum(`attr.modelCalls`) as chamadas by creditorId, `attr.modelTier`

# funil de negociação por credor (negócio)
filter eventType in ["proposal.created", "agreement.formalized", "payment.confirmed"]
| stats count(*) by creditorId, eventType

# valor recuperado por operador no dia (negócio)
filter eventType = "payment.confirmed"
| stats sum(amount) by actorId, bin(1d)

# resultados das chamadas do discador por campanha e dia (negócio)
# abandono = ABANDONED ÷ (ANSWERED + ABANDONED); ASR = (ANSWERED + VOICEMAIL + ABANDONED) ÷ total
filter eventType = "call.completed" and `attr.origin` = "DIALER"
| stats count(*) by `attr.campaignId`, `attr.result`, bin(1d)

# etapas puladas por motivo e campanha (negócio)
filter eventType = "campaign.step_skipped"
| stats count(*) by `attr.campaignCode`, `attr.reason`

# chat de suporte: perguntas sem resposta por tela e tema (negócio)
filter eventType = "helpdesk.question_unanswered"
| stats count(*) as semResposta by `attr.screen`, `attr.topic`, `attr.outcome` | sort semResposta desc

# chat de suporte: chamados abertos por tela e avaliação das respostas (negócio)
filter eventType in ["helpdesk.ticket_opened", "helpdesk.feedback_recorded"]
| stats count(*) by eventType, `attr.screen`, `attr.rating`

# consultas de painel lentas (técnico)
filter message = "Consulta de painel lenta"
| stats count(*), pct(durationMs, 95) by report, groupBy

# tours mais dispensados (técnico — adoção da interface)
filter event = "ui.onboarding.tour_recorded"
| stats count(*) by tourId, outcome | sort count(*) desc

# tentativas de login negadas por IP (acesso)
filter eventType in ["LOGIN_FAILURE", "LOGIN_BLOCKED"]
| stats count(*) as falhas by clientIp | sort falhas desc
```

## 5. Métricas (Micrometer → CloudWatch, namespace `GestorCobranca`)

| Métrica | Uso |
| --- | --- |
| `http.server.requests` (percentis, SLO 200 ms/500 ms/1 s/2 s) | latência e erros por rota |
| `hikaricp.*`, `jvm.*`, `process.*` | saúde do pool e da JVM |
| `business.events{type}` | volume de fatos de negócio |
| `access.events{type,success}`, `access.log.write_failures` | segurança e integridade do log de acesso |
| `http.forwarded.invalid_client_ip` | proxy confiável informou IP de cliente que não é IP literal (evento gravado sem IP, `clientIpInvalid=true`); deve ser zero |
| `crypto.legacy_format_reads` | leituras de campos cifrados no formato anterior ao v2 (sem vínculo com a assessoria); tende a zero conforme os registros são regravados |
| `jobs.pending`, `jobs.dead`, `jobs.duration{type,outcome}`, `jobs.executed`, `jobs.recovered` | fila de trabalho |
| `events.publications.incomplete`, `executor.*{name=events}` | eventos de integração (consumidores repetem conflitos de concorrência transitórios até 4 vezes) |
| `executor.*{name=mail}` | envio de e-mail (executor próprio, não ocupa as threads de eventos) |
| `executor.*{name=portal_code_dispatch}` | envio do código de acesso do portal fora da requisição (fila cheia = códigos não enviados) |
| `audit.sealed`, `audit.seal.failures` | selagem da auditoria |
| `storage.operation{operation,category,outcome}` | S3 |
| `mail.sent{type,outcome}` | e-mail |
| `integration.calls{provider,operation,outcome}` | provedores externos (convenção para os adaptadores) |
| `compliance.contact_gate.decisions{operation,outcome}`, `compliance.contact_gate.denials{operation,reason}` | porteiro de contato (consultas e autorizações, motivos de negação) |
| `compliance.dsr.overdue{type}` | solicitações de titulares que passaram do prazo (alarme) |
| `compliance.restrictions.expired`, `compliance.retention.runs{category,dryRun,outcome}`, `compliance.retention.debtors{outcome}` | fim de vigência de restrições e execuções de retenção |
| `collection.cases.opened{trigger}`, `collection.cases.closed{reason}`, `collection.cases.transitions{from,to}` | ciclo de vida dos casos |
| `collection.eligibility.evaluations{outcome,changed}`, `collection.eligibility.reasons{reason}` | elegibilidade (RB02) e motivos de inelegibilidade |
| `collection.assignments{mode,owner}`, `collection.distribution.cases{strategy,outcome}` | mudanças de dono e distribuição em lote (casos sem destino = `unassigned`) |
| `collection.next_case{outcome}`, `collection.next_case.duration{outcome}` (p50/p95/p99) | "próximo caso": `own_callback`, `queue`, `empty`, `capacity` |
| `collection.interactions{channel,direction,effective,source}` | volume de interações e contatos efetivos |
| `collection.callbacks{status}`, `collection.promises{status}`, `collection.disputes{status}` | retornos, promessas e contestações por situação |
| `collection.disputes.overdue{type}` | contestações com prazo de resposta vencido (alarme) |
| `collection.sync.cases{trigger}`, `collection.sync.duration{trigger}` | sincronização/reavaliação em lote (carga, roteamento, política, carteira) |
| `collection.events.consumed{type}`, `collection.jobs.failures{job}` | consumo de eventos (inclui `case_score`, do scoring) e falhas dos agendamentos (alarme) |
| `ingestion.batches.received{source,mode}`, `ingestion.batches.finished{source,mode,status}`, `ingestion.batch.duration{source,mode,status}` | volume e duração dos lotes de importação |
| `ingestion.files.rejected{reason}` | envios recusados no recebimento (tamanho, tipo pelo conteúdo, duplicidade, chave de idempotência…) |
| `ingestion.rows{mode,outcome}`, `ingestion.titles{outcome}`, `ingestion.issues{severity}`, `ingestion.issues.stored` | linhas válidas/rejeitadas, títulos criados/atualizados/inalterados/não gravados/suspensos, ocorrências |
| `ingestion.slice.duration{mode,outcome}`, `ingestion.row.write.duration{outcome}`, `ingestion.rows.retried` | desempenho do processamento (fatia, gravação por linha) e conflitos entre cargas simultâneas |
| `ingestion.format.columns{status}`, `ingestion.format.text_numbers{source}` | colunas monetárias por situação do formato (proporção de `AMBIGUOUS`/`MIXED` por origem) e valores numéricos em texto (no XLSX, "números armazenados como texto") |
| `ingestion.totals.checks{origin,result,hint}` | conferências de totais (`REVIEW`/`API`); **taxa de divergência** = `result=mismatch` ÷ total; `hint=scale` indica erro de separador decimal |
| `ingestion.review.alerts{type}`, `ingestion.review.decisions{decision}` | alertas de valor por tipo e decisões (confirmados, excluídos, reabertos) |
| `ingestion.review.commits{approval}`, `ingestion.review.approvals{decision}`, `ingestion.review.analysis.duration{outcome}` | efetivações (com/sem aprovação), decisões da segunda pessoa e duração da análise de valores |
| `ingestion.rows{mode,outcome=excluded}` | linhas excluídas na conferência e puladas na importação |
| `incentive.facts.consumed{type}` | fatos consumidos pelo módulo incentive por tipo de evento (`expected_recovery`, `interaction`, `call`, `message`, `case_status`, `case_assignment`, `case_balance`, `callback`, `promise`, `attribution`, `agreement_status`, `installment_status`, `payment_confirmed`, `payment_reversed`) |
| `incentive.facts.ignored{type,reason}` | evento consumido sem efeito nos fatos (`unknown_status` = situação nova do produtor ainda não tratada; `incomplete` = sem tarefa/horário/promessa). Crescimento contínuo = contrato do produtor mudou |
| `incentive.facts.out_of_order{type}` | evento de retorno/promessa mais antigo que o último já aplicado ao mesmo compromisso (entrega fora de ordem, tratada sem perda: a situação só avança ou vale o evento mais recente) |
| `incentive.ledger.entries{kind,beneficiary}`, `incentive.ledger.transitions{status}` | lançamentos do razão e liberações/dispensas |
| `incentive.payments.awaiting_attribution` | pagamento recebido antes da atribuição do acordo (crescimento contínuo = produtor sem `AgreementAttributionRecorded`) |
| `incentive.period.close.duration{trigger}`, `incentive.statements.calculated{trigger}`, `incentive.statements.transitions{status}` | fechamento de período e ciclo dos extratos |
| `incentive.exports{format}`, `incentive.jobs.failures{job}` | exportações para a folha e falhas dos agendamentos (alarme) |
| `negotiation.simulations{actorType,outcome}`, `negotiation.simulation.offers{actorType}`, `negotiation.simulation.duration{actorType}` | simulações (OFFERED × NO_VALID_OFFER), ofertas geradas e latência do motor |
| `negotiation.guard.decisions{actorType,outcome}`, `negotiation.guard.violations{rule}` | validações de condições (WITHIN_AUTHORITY, REQUIRES_APPROVAL, REJECTED) e regras violadas |
| `negotiation.concession.escalations{actorType,level,reason}` | pedidos de subida na escada de concessões |
| `negotiation.policy.transitions{status}`, `negotiation.authority.changes{operation}`, `negotiation.authority.profiles_seeded` | ciclo das políticas, mudanças de alçada e semeadura dos perfis padrão |
| `negotiation.calculation.index_pending{index}` | cálculo com competência de índice não publicada (crescimento contínuo = tabela de índices desatualizada) |
| `negotiation.proposals{actorType,status}` | propostas criadas por situação inicial (DRAFT, PENDING_APPROVAL, HANDED_OFF, REJECTED = recusada pela política) |
| `negotiation.proposal.decisions{decision}`, `negotiation.proposal.approval_wait{decision}` | aprovações/recusas e tempo de espera na fila (F16) |
| `negotiation.proposal.acceptances{channel}`, `negotiation.proposal.expirations`, `negotiation.handoffs` | aceites por canal, propostas expiradas e transferências do assistente |
| `negotiation.agreement.transitions{status,origin}`, `negotiation.formalization.duration`, `negotiation.term.render.duration` | ciclo dos acordos por origem (humano × assistente) e latência da formalização e do termo |
| `negotiation.installments.overdue` | parcelas que entraram em atraso |
| `negotiation.agreement.break_evaluations{outcome}` | acordos que atingiram a regra de quebra no job: BREAK, BREAK_SUSPENSION_USED (pagamento em revisão, mas a parcela já teve suspensão encerrada por recusa/prazo), SUSPEND, HOLD_SUSPENDED, HOLD_GRACE (carência após recusa), DEFER_IN_TRANSIT (pagamento confirmado ainda em baixa) |
| `negotiation.agreement.break_suspensions{event,reason}`, `negotiation.agreement.break_suspension.days{reason}` | suspensões da quebra iniciadas (`STARTED`) e encerradas (`ENDED` por APPROVED, REJECTED, DEADLINE, AGREEMENT_ENDED) e duração em dias; DEADLINE crescente = fila de revisão parada (WARN técnico a cada fim pelo prazo) |
| `payment.instructions{type,outcome}` | instruções: `created`, `reused`, `registered`, `failed`, `expired`, `cancelled`, `provider_cancelled`, `provider_cancel_refused`, `provider_unavailable` |
| `payment.payments{source,method,status}`, `payment.confirmation.latency{source}` (p50/p95/p99) | transições de pagamento e tempo do pagamento à confirmação |
| `payment.allocations{kind,origin,outcome}`, `payment.leftovers{reason}`, `payment.agreement.outcomes{outcome}` | alocações (`allocated`, `leftover`, `released`, `reversed`), sobras (`no_target`, `agreement_closed`, `title_unavailable`, `excess`) e resultado da baixa no negotiation (UNAPPLIED contínuo = fila de realocação crescendo) |
| `payment.review.decisions{decision}`, `payment.review.wait{decision}` | revisão por segunda pessoa e espera na fila |
| `payment.external.review_decisions{decision}` | pagamento externo: `AUTO`, `AMOUNT`, `DESTINATION_CUMULATIVE`, `INFORMER_CUMULATIVE` (crescimento dos acumulados = possível fracionamento) |
| `payment.pix.endpoint_refused{reason}`, `payment.pix_account.endpoint_changes{action}` | chamada a PSP recusada (`host_not_allowed`, `internal_address`, `invalid` — alarme) e criação/troca de endereços de conta Pix (`created`, `changed`; alerta de segurança) |
| `payment.reversals{reason,source}` | estornos |
| `payment.webhook.requests{provider,outcome}` | `ACCEPTED`, `DUPLICATE`, `UNKNOWN_ROUTE`, `UNAUTHENTICATED` (alarme), `MALFORMED`, `TOO_LARGE`, `RATE_LIMITED` |
| `payment.webhook.events{outcome}`, `payment.pix.sync{outcome}` | itens do webhook (`scheduled`, `stale`, `invalid`) e confirmação por consulta (`confirmed`, `linked`, `duplicate_payment`, `unidentified`, `refund`, `unchanged`, `not_found`) |
| `payment.reconciliation.imports{format,status}`, `payment.reconciliation.lines{outcome}`, `payment.divergences{type,status}` | conciliação por extrato e fila de divergências |
| `payment.ledger.postings{operation,outcome}` | lançamentos no razão de títulos avulsos (`payment`/`reversal`; `failed` = alarme) |
| `payment.events.consumed{type}`, `payment.jobs.failures{job}` | consumo de eventos e falhas (`pix_sync`, `expiration`, `webhook_receipts`; alarme) |
| `integration.calls{provider=pix_bacen|pix_sandbox,operation,outcome}` | chamadas à API Pix (`oauth_token`, `create_cob`, `create_cobv`, `cancel_charge`, `find_pix`, `configure_webhook`) |
| `integration.calls{provider=bedrock,operation=converse,outcome}`, `integration.calls.duration{provider=bedrock,operation=converse}` | chamadas do assistente ao Amazon Bedrock (`ok`, `timeout`, `transient`, `rejected`) |
| `negotiation.payments{outcome}`, `negotiation.payment.reversals{outcome}` | baixas (APPLIED, PARTIAL = excedente, UNAPPLIED = acordo encerrado, DUPLICATE, IGNORED) e estornos (REVERSED, UNKNOWN, DUPLICATE); UNAPPLIED/PARTIAL contínuos = realocação pendente no payment |
| `negotiation.ledger.postings{operation,outcome}` | lançamentos no razão do título (PAYMENT inclui a quitação do título; REVERSAL); `FAILED` = job em DEAD (alarme) |
| `negotiation.anonymized_records` | registros neutralizados na anonimização LGPD do devedor |
| `negotiation.jobs.failures{job}` | falhas dos agendamentos do módulo (alarme) |
| `debt.collateral.registered{type,source}`, `debt.collateral.transitions{type,to}`, `debt.collateral.appraisals{source}` | garantias cadastradas, mudanças de situação e avaliações |
| `debt.obligation_parties.registered{role,source}` | coobrigados incluídos |
| `debt.prescription.interruptions{type,origin}` | interrupções de prescrição registradas |
| `debt.prescription.recalculated{trigger}` | linhas de prescrição criadas/alteradas/removidas por recálculo (`IMPORT`, `CLASSIFICATION`, `INTERRUPTION`, `RULE_CHANGE`, `RECONCILIATION`); `RECONCILIATION` contínuo indica recálculo que não está acontecendo na origem |
| `debt.prescription.alerts{claim,instrumentType}` | alertas de prescrição próxima emitidos |
| `debt.prescription.scan.duration{phase}` | duração da varredura diária por fase (`reconcile`, `alert`) |
| `debt.jobs.failures{job}` | falhas dos agendamentos do módulo debt (alarme) |
| `legal.matters.referred{origin}`, `legal.matters.transitions{from,to}` | indicações e ciclo dos casos jurídicos |
| `legal.eligibility.evaluations{outcome}`, `legal.eligibility.reasons{reason}` | avaliações de elegibilidade (`eligible`, `ineligible`, `already_open`, `cooldown`, `case_status`, `no_rule`) e motivos |
| `legal.viability.assessments{viable,economicallyViable}`, `legal.authorizations{decision}` | triagem e decisões do credor |
| `legal.measures.transitions{type,to}`, `legal.lawsuits.filed{route}`, `legal.movements{type,source}` | medidas, ajuizamentos e andamentos |
| `legal.deadlines.created{kind,mode}`, `legal.deadlines.alerts{level}`, `legal.deadlines.met{onTime}`, `legal.deadlines.rescheduled{trigger}` | prazos |
| `legal.deadlines.missed{kind}` | prazos perdidos (alarme) |
| `legal.hearings{status}`, `legal.expenses.recorded{type,advancedBy,overBudget}`, `legal.expenses.blocked{reason}` | audiências e custas (bloqueios: `advancer_not_allowed`, `no_authorization`, `budget_exceeded`) |
| `legal.collection.holds{operation,outcome}` | retenção LEGAL no collection (`placed`, `already_legal`, `released`, `case_closed`, `not_legal`) |
| `legal.prescription.interruptions{type,outcome}` | interrupções enviadas ao debt (`registered`/`duplicate`) |
| `legal.providers.manual_requests{port,operation}`, `legal.providers.captured{provider}` | pedidos de operação automática com adaptador manual; andamentos capturados |
| `legal.access.law_firm_denied{resource}` | acessos negados a membros de escritório fora dos casos atribuídos |
| `legal.events.consumed{type}`, `legal.scan.duration{phase}`, `legal.jobs.failures{job}` | consumo de eventos (inclui `cadence_exhausted` e `cadence_exhausted_ignored` — régua de outra finalidade), duração da varredura diária e falhas (alarme) |
| `assistant.turns{mode,outcome}`, `assistant.turn.duration{mode,outcome}` (p50/p95/p99) | turnos por resultado (`REPLIED`, `DRAFTED`, `HANDED_OFF`, `APPROVAL_REQUESTED`, `SEND_BLOCKED`, `GUARDRAIL_BLOCKED`, `SKIPPED`, `FAILED`) e latência total do turno |
| `assistant.model.calls{provider,outcome}`, `assistant.model.latency{provider}` (p50/p95/p99) | chamadas ao modelo (`ok`, `MODEL_TIMEOUT`, `MODEL_UNAVAILABLE`, `MODEL_REJECTED`) — alarme em taxa de falha |
| `assistant.tokens{provider,direction}`, `assistant.cost{provider,currency}` | consumo de tokens (`input` sem cache, `output`, `cache_read`, `cache_write`) e custo estimado do modelo (base do custo por acordo) |
| `assistant.model.routing{tier,reason}` | nível de modelo escolhido para cada chamada (`STANDARD`/`ADVANCED`) e motivo (`POLICY` = política `ADVANCED_ONLY`, `INTENT` = negociar/aceitar/pagar, `STAGE` = etapa com ofertas/proposta/parcelas, `DEFAULT`); participação do avançado = `ADVANCED` ÷ total |
| `assistant.bedrock.calls{model,tier,outcome,error}`, `assistant.bedrock.latency{model,tier}` (p50/p95/p99) | chamadas ao Amazon Bedrock por modelo/perfil e nível: `outcome` = `ok`, `MODEL_TIMEOUT`, `MODEL_UNAVAILABLE`, `MODEL_REJECTED`; `error` = código da AWS (`ThrottlingException`, `ValidationException`, `AccessDeniedException`…), motivo de parada recusado (`model_context_window_exceeded`, `malformed_tool_use`…) ou `none`; latência total (inclusive a retentativa). Alarmes: `MODEL_REJECTED` > 0 (configuração/acesso ao modelo) e taxa de `MODEL_UNAVAILABLE`/`MODEL_TIMEOUT` |
| `assistant.bedrock.retries{model,reason}` | retentativa única (limite de taxa/5xx); crescente = cota insuficiente (Service Quotas) |
| `assistant.bedrock.tokens{model,tier,type}`, `assistant.bedrock.cost{model,tier,currency}` | tokens por modelo (`input`, `output`, `cache_read`, `cache_write`) e custo pela tabela configurada; acerto do cache = `cache_read` ÷ (`input` + `cache_read` + `cache_write`) |
| `assistant.bedrock.stop_reasons{model,reason}`, `assistant.bedrock.long_context{model}` | motivo de parada das respostas (`END_TURN`, `TOOL_USE`, `MAX_TOKENS`, `CONTENT_FILTERED`) e chamadas cobradas pela faixa de contexto longo (prompt acima do limiar configurado — Haiku 5.5: 100 mil tokens) |
| `assistant.tool.calls{tool,outcome}` | ferramentas pedidas pelo modelo (`OK`, `REJECTED` = validação do servidor recusou, `ERROR` = módulo dono recusou); `REJECTED` crescente = prompt/modelo tentando sair dos limites |
| `assistant.handoffs{reason,source}`, `assistant.guardrails{code}` | transferências por motivo e origem; guardrails acionados por código |
| `assistant.drafts{mode}`, `assistant.identity.checks{outcome}`, `debt.identity_checks{outcome}` | rascunhos gerados; confirmações de identidade (`MATCHED`, `NOT_MATCHED`, `UNAVAILABLE`) |
| `assistant.turns.scheduled{outcome}`, `assistant.turns.recovered` | agendamento dos turnos (`enqueued`, `deferred` fora do horário, `not_applicable`, `no_new_messages`, `skipped_conversation`) e turnos interrompidos recuperados (alarme se contínuo) |
| `assistant.policy.transitions{status}`, `assistant.reviews{verdict}` | ciclo das políticas e avaliações humanas (`OUT_OF_POLICY` deve ser zero para avançar de fase) |
| `assistant.retention.purged`, `assistant.events.consumed{type}`, `assistant.jobs.failures{job}` | descarte do conteúdo da trilha, consumo de eventos e falhas dos agendamentos (alarme) |
| `assistant.pix.issued{mode,reused}`, `assistant.pix.denied{reason}`, `assistant.pix.suggested{mode}` | Pix na conversa: gerados (instrução nova ou reutilizada), recusados por critério e sugeridos ao operador |
| `assistant.pix.deliveries{outcome,code}`, `assistant.pix.delivery.latency` (p50/p95/p99) | entrega do código (`DELIVERED`, `NOT_DELIVERED`, `CANCELLED`) e tempo pedido → entrega (registro no PSP); `NOT_DELIVERED` com `INVALID_PIX_CODE`/`STATIC_PIX_CODE`/`AMOUNT_MISMATCH` = alarme (PSP devolvendo código fora do padrão) |
| `assistant.pix.paid{within_24h}` | Pix do assistente pagos, dentro ou fora de 24 h do pedido (conversão: ÷ `assistant.pix.issued`) |
| `helpdesk.turns{provider,outcome}`, `helpdesk.turn.duration{outcome}` (p50/p95/p99) | perguntas ao chat de suporte por resultado (`ANSWERED`, `UNANSWERED`, `OUT_OF_SCOPE`, `FAILED`) e latência total (alarme sugerido: p95 > 20 s) |
| `helpdesk.model.calls{provider,outcome}`, `helpdesk.model.latency{provider}` | chamadas ao modelo do chat (`ok`, `MODEL_TIMEOUT`, `MODEL_UNAVAILABLE`, `MODEL_REJECTED`) — alarme em taxa de falha |
| `helpdesk.tokens{provider,direction}`, `helpdesk.cost{provider,currency}` | consumo e custo estimado do chat (direção `input`, `output`, `cache_read`, `cache_write`) |
| `helpdesk.bedrock.calls{model,tier,outcome,error}`, `helpdesk.bedrock.latency`, `helpdesk.bedrock.retries`, `helpdesk.bedrock.tokens`, `helpdesk.bedrock.cost`, `helpdesk.bedrock.stop_reasons`, `helpdesk.bedrock.long_context` | mesmas métricas do cliente Bedrock do kernel com o prefixo do chat (ver `assistant.bedrock.*`) |
| `helpdesk.tool.calls{tool,outcome}` | ferramentas pedidas pelo modelo (`OK`, `REJECTED` = argumento inválido, `DENIED` = sem permissão/escopo, `NOT_FOUND`, `ERROR`); `DENIED` crescente = modelo tentando sair do que foi oferecido |
| `helpdesk.limits.reached{limit}`, `helpdesk.unavailable` | perguntas recusadas por limite (`helpdesk.limit_rate`, `_daily`, `_conversation`) e por chat desligado |
| `helpdesk.questions{topic,outcome}`, `helpdesk.questions.unanswered{screen}` | negócio: perguntas por tema e resultado; perguntas sem resposta por tela (onde o manual ou a tela precisam melhorar) |
| `helpdesk.feedback{rating,screen}` | negócio: avaliações 👍/👎 por tela (satisfação = `UP` ÷ total) |
| `helpdesk.tickets{outcome}`, `helpdesk.tickets.opened{screen}` | chamados (`DRAFTED`, `OPENED`, `DUPLICATE`, `CANCELLED`, `FAILED`, `UNAVAILABLE`, `BLOCKED_PUBLIC_REPOSITORY`) e abertos por tela |
| `integration.calls{provider=github,operation,outcome}`, `integration.calls.duration{provider=github,operation}` | chamadas ao GitHub (`create_issue`, `find_issue`, `get_repository`; `ok`, `transient`, `rejected`, `timeout`) — alarme em `rejected` (token) |
| `helpdesk.retention.purged`, `helpdesk.jobs.failures{job}` | descarte do conteúdo das conversas e falhas da retenção (alarme) |
| `settlement.receipts{kind,outcome}`, `settlement.ratings{kind,outcome}` | recebimentos registrados (`recorded`, `duplicate`) e apuração (`rated`, `pending_no_rules`, `pending_no_contract`, `pending_original_pending`); `pending_*` contínuo = contrato sem regras publicadas |
| `settlement.events.ignored{type,reason}` | eventos sem efeito (ex.: `agreement_payment_recorded`/`unapplied`, `legal_expense`/`reimbursed_after_statement` — possível reembolso em duplicidade, investigar) |
| `settlement.expenses{kind,effect}` | custas do jurídico materializadas (`CHARGE`/`CANCELLATION` × `DEDUCT`/`INFORMATIVE`) |
| `settlement.statements.transitions{status}`, `settlement.statement.calculate.duration`, `settlement.statement.receipts` | ciclo dos demonstrativos, duração e volume do cálculo |
| `settlement.adjustments{status}`, `settlement.exports{format}`, `settlement.transfers{direction,outcome}`, `settlement.rules.published` | ajustes (aplicados, pendentes de segunda aprovação, recusados), exportações, repasses (`registered`/`cancelled`) e publicações de regras |
| `communication.messages{channel,direction,sender,status}` | mudanças de status de mensagens (inclui `QUEUED` e `RECEIVED`) |
| `communication.messages.blocked{channel,sender,stage,reason}` | envios negados pelo porteiro, por motivo (`precheck`/`send`) |
| `communication.send.attempts{channel,provider,outcome}` | tentativas do job: `accepted`, `rejected`, `transient`, `blocked`, `account_unavailable`, `whatsapp_window_closed` |
| `communication.send.latency{channel}`, `communication.delivery.latency{channel}` (p50/p95/p99) | enfileiramento → aceite; aceite → entrega |
| `communication.inbox.first_response{channel,responder}` (p50/p95/p99) | SLA de primeira resposta (operador ou assistente) |
| `communication.inbox.first_response.sla{channel,responder,outcome}` | primeiras respostas pela meta da assessoria: `OK`, `WARNING`, `BREACHED` (taxa de cumprimento do SLA) |
| `communication.inbox.sla.breaches{channel,owner}` | conversas que estouraram o limite sem resposta (varredura de 1 min, contagem única por espera; alarme `inbox-sla-breaches`) |
| `communication.realtime.subscribers` (gauge por instância), `communication.realtime.streams{outcome}` | conexões SSE abertas e ciclo (`opened`, `rejected_limit`, `expired`, `revoked`, `timeout`, `client_gone`, `completed`, `shutdown`) |
| `communication.realtime.signals{kind,outcome}`, `communication.realtime.listener.failures`, `communication.realtime.listener.malformed` | avisos repassados (`conversation`/`resync`; `delivered`/`no_subscriber`), falhas da escuta LISTEN/NOTIFY (alarme `inbox-realtime-listener`) e avisos inválidos |
| `communication.webhook.requests{provider,outcome}` | `ACCEPTED`, `DUPLICATE`, `UNKNOWN_ROUTE`, `INVALID_SIGNATURE` (alarme), `MALFORMED`, `TOO_LARGE`, `RATE_LIMITED`, `VERIFIED`, `VERIFY_REJECTED` |
| `communication.webhook.events{provider,type,outcome}` | mensagens/status do webhook: `applied`, `duplicate`, `stale` (replay), `unknown_message`, `ignored` |
| `communication.opt_out{channel}` | descadastros por palavra-chave |
| `communication.handoffs{kind,from,to}` | mudanças de dono das conversas |
| `communication.interactions{direction,outcome}` | interações registradas no caso (`recorded`, `duplicate`, `skipped_*`) |
| `communication.drafts{mode,outcome}` | rascunhos do assistente: `proposed`, `sent`, `edited`, `discarded` |
| `communication.retention.purged`, `communication.events.consumed{type}`, `communication.jobs.failures{job}` | descarte de conteúdo, consumo de eventos e falhas (alarme; inclui `communication.send` com tentativas esgotadas) |
| `campaign.enrollments{outcome}` | inscrições: `enrolled`, `holdout`, `suppressed_concurrency`, `preempted`, `skipped_blocked`, `already_enrolled` |
| `campaign.enrollments.ended{reason}` | encerramentos por motivo (`cadence_exhausted`, `replied`, `left_segment`, `preempted`…) |
| `campaign.steps{action,channel,outcome}`, `campaign.step.duration{action}` (p50/p95/p99) | etapas executadas, puladas, com falha e adiadas; latência da execução |
| `campaign.steps.skipped{reason}`, `campaign.steps.failed{code}` | motivos de etapa pulada e códigos de falha (crescimento de `failed` = régua mal configurada) |
| `campaign.pauses{scope,reason}`, `campaign.resumes{trigger}` | pausas automáticas/manuais e retomadas |
| `campaign.replies{transition}`, `campaign.attributions{kind}`, `campaign.cadence.exhausted{purpose}` | respostas (RB07), resultados atribuídos e réguas esgotadas |
| `campaign.audience.refreshes{trigger,outcome}`, `campaign.segment.evaluation{operation}` (p50/p95/p99) | atualização do read model e tempo de avaliação de segmentos |
| `campaign.experiment.assignments{arm}` | atribuições de braço (controle × variante) |
| `campaign.events.consumed{type}`, `campaign.jobs.failures{job}` | consumo de eventos e falhas de agendamentos/etapas (alarme) |
| `telephony.calls{direction,origin,result}` | chamadas encerradas que chegaram ao provedor, por resultado (base do ASR: atendidas ÷ discadas) |
| `telephony.dial.requests{origin,outcome}` | pedidos de discagem: `accepted`, `rejected`, `transient`, `blocked`, `exhausted` |
| `telephony.calls.blocked{origin,stage,reason}` | negações do porteiro por motivo (`precheck`/`dial`) |
| `telephony.dialer.live{mode,outcome}` | atendidas por pessoa no discador: `connected` × `abandoned` — **taxa de abandono** = abandoned ÷ total (alarme acima de 3%) |
| `telephony.dialer.ticks{mode,reason}`, `telephony.dialer.placed{mode}` | ciclos do discador pelo motivo do ritmo (`progressive`, `warming_up`, `predictive`, `abandon_near_limit`, `abandon_limit`, `no_agents`, `lines_busy`, `outside_window`) e chamadas colocadas |
| `telephony.dialer.entries{outcome}` | itens da lista: `loaded`, `deferred`, `retry`, `completed`, `exhausted`, `excluded_<motivo>`, `no_account` |
| `telephony.call.ring_time{origin}`, `telephony.call.talk_time{origin}`, `telephony.call.wrap_up_time{origin}`, `telephony.call.distribution_time{origin}` (p50/p95/p99) | tempos de toque, conversação, pós-atendimento e do atendimento à conexão com o operador |
| `telephony.calls.short{origin}` | atendidas e encerradas pelo sistema em até 3 s (chamadas curtas, acompanhamento da Anatel) |
| `telephony.presence.transitions{to,reason}` | mudanças de presença por estado e motivo de pausa |
| `telephony.tabulations{mode}`, `telephony.interactions{outcome}` | tabulações (`operator`, `automatic`, `deadline`) e registros no caso (`recorded`, `duplicate`, `skipped_*`) |
| `telephony.recordings{outcome}`, `telephony.recording.access{purpose}` | gravações (`stored`, `failed`, `too_large`, `purged_retention`, `purged_anonymization`) e acessos por finalidade |
| `telephony.webhook.requests{provider,outcome}`, `telephony.webhook.events{provider,type,outcome}` | webhooks (`ACCEPTED`, `DUPLICATE`, `UNKNOWN_ROUTE`, `INVALID_SIGNATURE` — alarme —, `MALFORMED`, `TOO_LARGE`, `RATE_LIMITED`) e eventos (`applied`, `duplicate`, `stale`, `unknown_call`, `ignored`) |
| `telephony.reconciliation{kind}`, `telephony.events.consumed{type}`, `telephony.jobs.failures{job}` | conciliação (`agent_timeout`, `stale_call`), consumo de eventos e falhas de jobs/agendamentos (alarme) |
| `telephony.provider.calls{provider,origin,result}`, `telephony.provider.talk_time{provider}` (p50/p95/p99) | multiprovedor: chamadas encerradas e conversação **por plataforma** (ASR por provedor = atendidas ÷ discadas); série separada para não mudar as dimensões de `telephony.calls` |
| `telephony.provider.dial{provider,origin,outcome}` | pedidos à plataforma: `accepted`, `rejected`, `transient` (ambíguo), `transient_not_processed` (não criou a chamada) |
| `telephony.provider.fallbacks{from,to,origin}` | discagens desviadas para a conta de contingência (alarme sugerido: qualquer valor sustentado por 15 min = plataforma principal fora do ar) |
| `integration.calls{provider=alvaria,operation,outcome}`, `integration.calls.duration{provider=alvaria,operation}` | API da Alvaria CX (`oauth_token`, `originate`, `dial_batch`, `bridge`, `hangup`, `fetch_recording`, `delete_recording`; `success`, `rejected`, `transient`, `timeout`, `unreachable`) |
| `portal.funnel{step}` | funil do portal por sessão: `ACCESS`, `SIMULATION`, `ACCEPTANCE`, `PIX_ISSUED`, `PAYMENT_CONFIRMED` (conversão = etapa ÷ anterior) |
| `portal.access.code_requests{channel,outcome}`, `portal.access.logins{method,outcome}` | pedidos de código (`SENT`, `DECOY` = documento sem cadastro, `NOT_DELIVERED`) e verificações (`SUCCESS`, `INVALID`, `KNOWLEDGE_REQUIRED`); alta de `INVALID`/`DECOY` = varredura |
| `portal.access.locks{key,event}`, `portal.access.rate_limited` | bloqueios progressivos aplicados/recusados por documento ou IP e limite por IP (alarme sugerido: > 50 em 15 min) |
| `portal.access.links{channel,outcome}`, `portal.sessions{event}` | links emitidos e ciclo das sessões (`OPENED`, `REFRESHED`, `LOGOUT`, `REVOKED`, `EXPIRED`) |
| `portal.offers.refused{reason}`, `portal.agreements.formalized{plan}`, `portal.pix{outcome}` | condições recusadas no servidor (`not_offered`, `guard_*`), acordos (`CASH`/`INSTALLMENTS`) e Pix (`ISSUED`, `REUSED`, `FAILED`) |
| `portal.service_requests{reason,outcome}`, `portal.job.failures{job}` | pedidos à fila humana (`CREATED`, `FORWARDED`, `UNDELIVERED` — alarme se crescer) e falhas de jobs |
| `compliance.privacy_acknowledgments{channel,outcome}` | aviso de privacidade reconhecido com/sem base legal vigente do credor (`without_legal_basis` exige regularização) |
| `scoring.cases.scored{trigger,model,band}`, `scoring.cases.skipped{reason}` | casos escorados por gatilho, tipo de modelo e faixa; não escorados (`legal`, `closed`, `anonymized`, `error`) |
| `scoring.events.published{reason}` | `CaseScoreChanged` publicados (`first`, `band_change`, `delta`) |
| `scoring.batch.page.duration` (p50/p95/p99), `scoring.batch.page.cases`, `scoring.batch.duration{status}`, `scoring.batch.skipped{reason}` | desempenho da escoragem em lote; lote não iniciado (`legal_basis_missing`, `already_exists`) |
| `scoring.on_demand.duration{outcome}` (p50/p95/p99) | recálculo sob demanda |
| `scoring.training.runs{outcome}`, `scoring.training.duration{outcome}`, `scoring.models.decisions{decision}` | treinos (`challenger`, `failed_validation`, `insufficient_data`, `error`) e promoções/rejeições |
| `scoring.monitoring.psi{scope}`, `scoring.monitoring.alerts{level,kind}` | PSI do score e das features; alertas de deriva (alarme em `level=ALERT`) |
| `scoring.outcomes.resolved`, `scoring.cohorts.measured` | desfechos apurados e safras de recuperado × esperado publicadas |
| `scoring.facts.recorded{kind}`, `scoring.facts.ignored{type,reason}`, `scoring.events.consumed{type}` | fatos gravados; ignorados (`disabled` = sem base legal, `no_portfolio`, `duplicate`, `not_applicable`) |
| `scoring.retention.purged{what}`, `scoring.jobs.failures{job}` | retenção (`facts`, `snapshots`) e falhas de agendamento/execução interrompida (alarme) |
| `analytics.query.duration{report,outcome}` (p50/p95/p99), `analytics.queries.slow{report}` | latência das consultas dos painéis e consultas acima de `app.analytics.slow-query-threshold` (2 s; WARN no log técnico com relatório e agrupamento) |
| `analytics.refresh.duration{dataset,outcome}`, `analytics.refresh.rows{dataset}`, `analytics.refresh.cycle.duration{trigger}`, `analytics.refresh.tenants{trigger,outcome}` | reconstrução dos snapshots dos painéis por conjunto e ciclo agendado (carimbo "dados até") |
| `analytics.refresh.failures{trigger}` | falha ao atualizar os painéis de uma assessoria (alarme) |
| `analytics.exports{report,format}`, `analytics.export.rows{report}` | exportações de relatórios (cada uma também em `DATA_EXPORTED` no log de acesso) |
| `pendingwork.request.duration{outcome}` (p50/p95/p99), `pendingwork.request.sources` | consulta de "Minhas pendências": `complete` × `partial` (alguma fonte indisponível) e fontes consultadas por requisição (depende das permissões) |
| `pendingwork.source.duration{source,outcome}` (p50/p95/p99) | cada fonte: `ok`, `error`, `timeout` (acima de `app.pending-work.timeout`, também `statement_timeout`), `busy` (executor saturado) — alarme sugerido: `error`+`timeout` > 5% em 15 min |
| `executor.*{name=pending_work}` | fila e ocupação do executor das fontes (bulkhead de 4 threads por instância) |
| `pendingwork.observations{outcome}`, `pendingwork.retention.purged`, `pendingwork.jobs.failures{job}` | observações diárias (`recorded`, `already_recorded`, `failed`), retenção e falhas do agendamento (alarme) |
| `ui.onboarding.tours{outcome}` | adoção do onboarding (tours concluídos × dispensados); o id do tour fica só no log técnico `ui.onboarding.tour_recorded` |
| `errors.unexpected{type}`, `errors.optimistic_lock`, `errors.data_integrity` | qualidade |
