package br.com.gestorcobranca.shared.logging.business;

/**
 * Catálogo de tipos de evento de negócio ({@code <dominio>.<fato_no_passado>}). Manter este
 * catálogo estável: dashboards e consultas do CloudWatch dependem dos nomes. Ao criar um tipo
 * novo, documente-o em {@code docs/tecnico/logs-e-metricas.md}.
 */
public final class BusinessEventTypes {

    // Estrutura
    public static final String CREDITOR_CREATED = "creditor.created";
    public static final String PORTFOLIO_CREATED = "portfolio.created";
    public static final String PORTFOLIO_STATUS_CHANGED = "portfolio.status_changed";
    public static final String POLICY_PUBLISHED = "policy.published";

    // Ingestão
    public static final String IMPORT_STARTED = "import.started";
    public static final String IMPORT_COMPLETED = "import.completed";
    public static final String IMPORT_FAILED = "import.failed";
    public static final String IMPORT_CANCELLED = "import.cancelled";
    public static final String IMPORT_FORMAT_CONFIRMED = "import.format_confirmed";
    public static final String IMPORT_TOTALS_MATCHED = "import.totals_matched";
    public static final String IMPORT_TOTALS_MISMATCH = "import.totals_mismatch";
    public static final String IMPORT_ALERTS_DETECTED = "import.alerts_detected";
    public static final String IMPORT_OUTLIERS_REVIEWED = "import.outliers_reviewed";
    public static final String IMPORT_REVIEW_COMMITTED = "import.review_committed";
    public static final String IMPORT_REVIEW_APPROVED = "import.review_approved";
    public static final String IMPORT_REVIEW_REJECTED = "import.review_rejected";

    // Dívida
    public static final String DEBTOR_CONSOLIDATED = "debtor.consolidated";
    public static final String TITLE_STATUS_CHANGED = "title.status_changed";
    public static final String COLLATERAL_REGISTERED = "collateral.registered";
    public static final String COLLATERAL_STATUS_CHANGED = "collateral.status_changed";
    public static final String COLLATERAL_APPRAISED = "collateral.appraised";
    public static final String OBLIGATION_PARTY_REGISTERED = "obligation_party.registered";
    public static final String TITLE_PRESCRIPTION_INTERRUPTED = "title.prescription_interrupted";
    public static final String TITLE_PRESCRIPTION_APPROACHING = "title.prescription_approaching";

    // Operação
    public static final String CASE_OPENED = "case.opened";
    public static final String CASE_ASSIGNED = "case.assigned";
    public static final String CASE_CLOSED = "case.closed";
    public static final String INTERACTION_RECORDED = "interaction.recorded";
    public static final String CONTACT_EFFECTIVE = "contact.effective";
    public static final String CALLBACK_SCHEDULED = "callback.scheduled";
    public static final String PROMISE_REGISTERED = "promise.registered";
    public static final String DISPUTE_OPENED = "dispute.opened";
    public static final String DISPUTE_RESOLVED = "dispute.resolved";
    public static final String CASE_STATUS_CHANGED = "case.status_changed";
    public static final String CASE_ELIGIBILITY_CHANGED = "case.eligibility_changed";
    public static final String CALLBACK_KEPT = "callback.kept";
    public static final String CALLBACK_MISSED = "callback.missed";
    public static final String PROMISE_KEPT = "promise.kept";
    public static final String PROMISE_BROKEN = "promise.broken";

    // Negociação
    public static final String NEGOTIATION_SIMULATED = "negotiation.simulated";
    public static final String PROPOSAL_CREATED = "proposal.created";
    public static final String PROPOSAL_APPROVAL_REQUESTED = "proposal.approval_requested";
    public static final String PROPOSAL_APPROVED = "proposal.approved";
    public static final String PROPOSAL_REJECTED = "proposal.rejected";
    public static final String PROPOSAL_HANDOFF_REQUESTED = "proposal.handoff_requested";
    public static final String PROPOSAL_ACCEPTED = "proposal.accepted";
    public static final String PROPOSAL_EXPIRED = "proposal.expired";
    public static final String AGREEMENT_FORMALIZED = "agreement.formalized";
    public static final String AGREEMENT_SETTLED = "agreement.settled";
    public static final String AGREEMENT_BROKEN = "agreement.broken";
    public static final String AGREEMENT_RENEGOTIATED = "agreement.renegotiated";
    public static final String AGREEMENT_CANCELLED = "agreement.cancelled";
    /** Quebra suspensa por pagamento externo em revisão (decisão do responsável de 26/10/2026). */
    public static final String AGREEMENT_BREAK_SUSPENDED = "agreement.break_suspended";
    /** Suspensão da quebra encerrada: aprovado, recusado, prazo máximo ou acordo encerrado. */
    public static final String AGREEMENT_BREAK_SUSPENSION_ENDED = "agreement.break_suspension_ended";
    public static final String INSTALLMENT_OVERDUE = "installment.overdue";
    public static final String INSTALLMENT_PAID = "installment.paid";

    // Pagamentos
    public static final String PAYMENT_INSTRUCTION_CREATED = "payment_instruction.created";
    public static final String PAYMENT_INFORMED = "payment.informed";
    public static final String PAYMENT_CONFIRMED = "payment.confirmed";
    public static final String PAYMENT_RECONCILED = "payment.reconciled";
    public static final String PAYMENT_DIVERGENCE = "payment.divergence";
    public static final String PAYMENT_REVERSED = "payment.reversed";
    public static final String PAYMENT_REJECTED = "payment.rejected";
    public static final String PAYMENT_REALLOCATED = "payment.reallocated";

    // Comissão e prestação de contas
    public static final String COMMISSION_ACCRUED = "commission.accrued";
    public static final String SETTLEMENT_CLOSED = "settlement.closed";
    public static final String COMMISSION_REVERSED = "commission.reversed";
    public static final String COMMISSION_RULES_PUBLISHED = "commission.rules_published";
    public static final String SETTLEMENT_RECEIPT_PENDING_RULES = "settlement.receipt_pending_rules";
    public static final String SETTLEMENT_CALCULATED = "settlement.calculated";
    public static final String SETTLEMENT_ADJUSTED = "settlement.adjusted";
    public static final String SETTLEMENT_SENT = "settlement.sent";
    public static final String SETTLEMENT_ACKNOWLEDGED = "settlement.acknowledged";
    public static final String SETTLEMENT_CONTESTED = "settlement.contested";
    public static final String SETTLEMENT_EXPORTED = "settlement.exported";
    public static final String SETTLEMENT_TRANSFER_RECORDED = "settlement.transfer_recorded";

    // Remuneração variável dos negociadores (incentive)
    public static final String ATTRIBUTION_RECORDED = "attribution.recorded";
    public static final String COMMISSION_FORECAST = "commission.forecast";
    public static final String COMMISSION_ENTRY_POSTED = "commission.entry_posted";
    public static final String COMMISSION_ENTRY_CLEARED = "commission.entry_cleared";
    public static final String COMMISSION_ENTRY_REVERSED = "commission.entry_reversed";
    public static final String COMMISSION_PERIOD_CLOSED = "commission.period_closed";
    public static final String COMMISSION_STATEMENT_DISPUTED = "commission.statement_disputed";
    public static final String COMMISSION_STATEMENT_ADJUSTED = "commission.statement_adjusted";
    public static final String COMMISSION_STATEMENT_APPROVED = "commission.statement_approved";
    public static final String COMMISSION_STATEMENT_EXPORTED = "commission.statement_exported";
    public static final String COMMISSION_PLAN_PUBLISHED = "commission.plan_published";
    public static final String QUALITY_EVALUATED = "quality.evaluated";

    // Comunicação e campanhas
    public static final String MESSAGE_SENT = "message.sent";
    public static final String MESSAGE_DELIVERED = "message.delivered";
    public static final String MESSAGE_READ = "message.read";
    public static final String MESSAGE_FAILED = "message.failed";
    public static final String MESSAGE_REPLIED = "message.replied";
    public static final String MESSAGE_BLOCKED = "message.blocked";
    public static final String MESSAGE_RECEIVED = "message.received";
    public static final String CONVERSATION_HANDOFF = "conversation.handoff";
    public static final String CONVERSATION_CLOSED = "conversation.closed";
    public static final String CONVERSATION_SLA_BREACHED = "conversation.sla_breached";
    public static final String TEMPLATE_PUBLISHED = "template.published";
    public static final String CAMPAIGN_ACTIVATED = "campaign.activated";
    public static final String CAMPAIGN_PAUSED = "campaign.paused";
    public static final String CAMPAIGN_STEP_EXECUTED = "campaign.step_executed";
    public static final String CAMPAIGN_STEP_SKIPPED = "campaign.step_skipped";
    public static final String CAMPAIGN_FINISHED = "campaign.finished";
    public static final String CAMPAIGN_REPLIED = "campaign.replied";
    public static final String CAMPAIGN_CADENCE_EXHAUSTED = "campaign.cadence_exhausted";
    public static final String EXPERIMENT_VARIANT_ASSIGNED = "experiment.variant_assigned";

    // Compliance
    public static final String CONTACT_OPT_OUT = "contact.opt_out";
    public static final String CONTACT_RESTRICTED = "contact.restricted";
    public static final String CONTACT_RESTRICTION_REVOKED = "contact.restriction_revoked";
    public static final String CONTACT_DENIED = "contact.denied";
    public static final String DATA_SUBJECT_REQUEST_OPENED = "data_subject_request.opened";
    public static final String DATA_SUBJECT_REQUEST_COMPLETED = "data_subject_request.completed";
    public static final String RETENTION_RUN_COMPLETED = "retention.run_completed";

    // Telefonia (telephony) — atributos em docs/tecnico/logs-e-metricas.md §3.7
    public static final String CALL_DIALED = "call.dialed";
    public static final String CALL_BLOCKED = "call.blocked";
    public static final String CALL_COMPLETED = "call.completed";
    public static final String CALL_ABANDONED = "call.abandoned";
    public static final String CALL_TABULATED = "call.tabulated";
    public static final String CALL_RECORDING_STORED = "call.recording_stored";
    public static final String DIALER_CAMPAIGN_ACTIVATED = "dialer.campaign_activated";
    public static final String DIALER_CAMPAIGN_PAUSED = "dialer.campaign_paused";
    public static final String DIALER_CAMPAIGN_FINISHED = "dialer.campaign_finished";
    public static final String AGENT_PRESENCE_CHANGED = "agent.presence_changed";

    // Portal, score, IA
    public static final String PORTAL_ACCESSED = "portal.accessed";
    public static final String PORTAL_AGREEMENT_FORMALIZED = "portal.agreement_formalized";
    /** Devedor consultou as condições/simulou no portal (etapa "simulação" do funil). */
    public static final String PORTAL_SIMULATED = "portal.simulated";
    /** Pix da entrada/à vista ou 2ª via emitido pelo portal (etapa "pagamento solicitado"). */
    public static final String PORTAL_PIX_ISSUED = "portal.pix_issued";
    /** Pagamento confirmado de acordo fechado no portal (etapa "pagamento" do funil). */
    public static final String PORTAL_PAYMENT_CONFIRMED = "portal.payment_confirmed";
    /** Pedido que foge do portal (falar com atendente, contestação) encaminhado à fila humana. */
    public static final String PORTAL_SERVICE_REQUESTED = "portal.service_requested";
    /** Configuração do portal de um credor criada ou alterada. */
    public static final String PORTAL_CONFIGURATION_CHANGED = "portal.configuration_changed";
    public static final String SCORE_CALCULATED = "score.calculated";
    // Score de propensão (módulo scoring) — atributos em docs/tecnico/logs-e-metricas.md §3.8
    public static final String SCORE_BATCH_COMPLETED = "score.batch_completed";
    public static final String SCORE_TRAINING_COMPLETED = "score.training_completed";
    public static final String SCORE_MODEL_PROMOTED = "score.model_promoted";
    public static final String SCORE_MODEL_REJECTED = "score.model_rejected";
    public static final String SCORE_DRIFT_DETECTED = "score.drift_detected";
    public static final String SCORE_COHORT_MEASURED = "score.cohort_measured";
    public static final String SCORE_REVIEWED = "score.reviewed";
    // API pública de integração (módulo publicapi) — atributos em docs/tecnico/logs-e-metricas.md §3.13
    public static final String PUBLIC_API_TOKEN_CREATED = "public_api.token_created";
    public static final String PUBLIC_API_TOKEN_REVOKED = "public_api.token_revoked";
    public static final String PUBLIC_API_TOKEN_ROTATED = "public_api.token_rotated";
    public static final String PUBLIC_API_PRICE_PLAN_PUBLISHED = "public_api.price_plan_published";
    public static final String PUBLIC_API_STATEMENT_CLOSED = "public_api.statement_closed";
    public static final String PUBLIC_API_RECORDS_PROCESSED = "public_api.records_processed";
    public static final String ASSISTANT_REPLIED = "assistant.replied";
    public static final String ASSISTANT_HANDOFF = "assistant.handoff";

    private BusinessEventTypes() {
    }

    // Jurídico (módulo legal) — atributos em docs/LOGGING.md §3.6
    public static final String LEGAL_MATTER_REFERRED = "legal.matter_referred";
    public static final String LEGAL_STAGE_CHANGED = "legal.stage_changed";
    public static final String LEGAL_MATTER_ASSIGNED = "legal.matter_assigned";
    public static final String LEGAL_VIABILITY_ASSESSED = "legal.viability_assessed";
    public static final String LEGAL_AUTHORIZATION_REQUESTED = "legal.authorization_requested";
    public static final String LEGAL_AUTHORIZATION_DECIDED = "legal.authorization_decided";
    public static final String LEGAL_MEASURE_REGISTERED = "legal.measure_registered";
    public static final String LEGAL_MEASURE_STATUS_CHANGED = "legal.measure_status_changed";
    public static final String LEGAL_LAWSUIT_FILED = "legal.lawsuit_filed";
    public static final String LEGAL_MOVEMENT_RECORDED = "legal.movement_recorded";
    public static final String LEGAL_RECOVERY_RECORDED = "legal.recovery_recorded";
    public static final String LEGAL_DEADLINE_CREATED = "legal.deadline_created";
    public static final String LEGAL_DEADLINE_APPROACHING = "legal.deadline_approaching";
    public static final String LEGAL_DEADLINE_MET = "legal.deadline_met";
    public static final String LEGAL_DEADLINE_MISSED = "legal.deadline_missed";
    public static final String LEGAL_HEARING_SCHEDULED = "legal.hearing_scheduled";
    public static final String LEGAL_HEARING_HELD = "legal.hearing_held";
    public static final String LEGAL_EXPENSE_RECORDED = "legal.expense_recorded";
    public static final String LEGAL_EXPENSE_CANCELLED = "legal.expense_cancelled";
    public static final String LEGAL_EXPENSE_REIMBURSED = "legal.expense_reimbursed";
    public static final String LEGAL_AGREEMENT_LINKED = "legal.agreement_linked";
    public static final String LEGAL_AGREEMENT_BROKEN = "legal.agreement_broken";
    public static final String LEGAL_TITLES_SETTLED = "legal.titles_settled";
    public static final String LEGAL_PRESCRIPTION_ALERT = "legal.prescription_alert";
    public static final String LEGAL_MATTER_CLOSED = "legal.matter_closed";

    // Assistente de IA (módulo assistant) — atributos em docs/tecnico/logs-e-metricas.md §3.8.
    // assistant.replied e assistant.handoff (este publicado pelo communication na transferência) acima.
    public static final String ASSISTANT_DRAFT_PROPOSED = "assistant.draft_proposed";
    public static final String ASSISTANT_HANDOFF_TRIGGERED = "assistant.handoff_triggered";
    public static final String ASSISTANT_APPROVAL_REQUESTED = "assistant.approval_requested";
    public static final String ASSISTANT_GUARDRAIL_BLOCKED = "assistant.guardrail_blocked";
    public static final String ASSISTANT_REPLY_BLOCKED = "assistant.reply_blocked";
    public static final String ASSISTANT_TURN_FAILED = "assistant.turn_failed";
    public static final String ASSISTANT_POLICY_RETIRED = "assistant.policy_retired";
    public static final String ASSISTANT_PORTFOLIO_DISABLED = "assistant.portfolio_disabled";
    public static final String ASSISTANT_REVIEW_RECORDED = "assistant.review_recorded";
    // Pix na conversa pelo assistente (ADR-0009)
    public static final String ASSISTANT_PIX_ISSUED = "assistant.pix_issued";
    public static final String ASSISTANT_PIX_DENIED = "assistant.pix_denied";
    public static final String ASSISTANT_PIX_SUGGESTED = "assistant.pix_suggested";
    public static final String ASSISTANT_PIX_DELIVERY = "assistant.pix_delivery";
    public static final String ASSISTANT_PIX_PAID = "assistant.pix_paid";

    // Minhas pendências (módulo pendingwork): primeira observação do dia por usuário e tipo (envelhecimento)
    public static final String PENDING_WORK_OBSERVED = "pending_work.observed";

    // Chat de suporte (módulo helpdesk) — atributos em docs/tecnico/logs-e-metricas.md §3.11. Sem conteúdo da conversa.
    public static final String HELPDESK_QUESTION_ANSWERED = "helpdesk.question_answered";
    public static final String HELPDESK_QUESTION_UNANSWERED = "helpdesk.question_unanswered";
    public static final String HELPDESK_FEEDBACK_RECORDED = "helpdesk.feedback_recorded";
    public static final String HELPDESK_TICKET_DRAFTED = "helpdesk.ticket_drafted";
    public static final String HELPDESK_TICKET_OPENED = "helpdesk.ticket_opened";
    public static final String HELPDESK_TICKET_CANCELLED = "helpdesk.ticket_cancelled";
}
