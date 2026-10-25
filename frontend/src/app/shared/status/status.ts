/** Rótulos e tons visuais dos estados de cada conceito do backend. */

export type StatusTone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

export interface StatusDescriptor {
  label: string;
  tone: StatusTone;
}

export type StatusDomain =
  | 'user'
  | 'creditor'
  | 'tenant'
  | 'contract'
  | 'portfolio'
  | 'session'
  | 'active'
  | 'debtor'
  | 'title'
  | 'contact'
  | 'restriction'
  | 'dataSubjectRequest'
  | 'retentionRun'
  | 'retentionItem'
  | 'legalBasis'
  | 'collateral'
  | 'case'
  | 'task'
  | 'promise'
  | 'dispute'
  | 'collectionPolicy'
  | 'negotiationOutcome'
  | 'importBatch'
  | 'importIssue'
  | 'importReview'
  | 'importAlert'
  | 'proposal'
  | 'agreement'
  | 'installment'
  | 'conversation'
  | 'conversationOwner'
  | 'message'
  | 'draft'
  | 'template'
  | 'templateApproval'
  | 'legalStage'
  | 'legalAuthorization'
  | 'legalMeasure'
  | 'lawsuit'
  | 'legalDeadline'
  | 'hearing'
  | 'legalExpense'
  | 'campaign'
  | 'cadence'
  | 'enrollment'
  | 'experiment'
  | 'stepOutcome'
  | 'paymentInstruction'
  | 'payment'
  | 'statementImport'
  | 'statementLine'
  | 'divergence'
  | 'commissionRuleSet'
  | 'receiptRating'
  | 'settlementStatement'
  | 'settlementAdjustment'
  | 'settlementTransfer'
  | 'commissionPlan'
  | 'commissionPeriod'
  | 'commissionStatement'
  | 'commissionOutcome'
  | 'commissionDispute'
  | 'commissionAdjustment'
  | 'commissionEntry'
  | 'conductOccurrence'
  | 'call'
  | 'agentPresence'
  | 'dialerCampaign'
  | 'dialerEntry'
  | 'recording'
  | 'assistantPolicy'
  | 'assistantMode'
  | 'assistantReview'
  | 'assistantTurn'
  | 'portalSession'
  | 'portalRequest';

const STATUS: Record<StatusDomain, Record<string, StatusDescriptor>> = {
  user: {
    PENDING_ACTIVATION: { label: 'Aguardando ativação', tone: 'info' },
    ACTIVE: { label: 'Ativo', tone: 'success' },
    DISABLED: { label: 'Desativado', tone: 'neutral' },
  },
  creditor: {
    ACTIVE: { label: 'Ativo', tone: 'success' },
    INACTIVE: { label: 'Inativo', tone: 'neutral' },
  },
  tenant: {
    ACTIVE: { label: 'Ativa', tone: 'success' },
    SUSPENDED: { label: 'Suspensa', tone: 'danger' },
  },
  contract: {
    ACTIVE: { label: 'Vigente', tone: 'success' },
    TERMINATED: { label: 'Encerrado', tone: 'neutral' },
  },
  portfolio: {
    DRAFT: { label: 'Em preparação', tone: 'info' },
    ACTIVE: { label: 'Ativa', tone: 'success' },
    SUSPENDED: { label: 'Suspensa', tone: 'warning' },
    RETURNED: { label: 'Devolvida', tone: 'neutral' },
    CLOSED: { label: 'Encerrada', tone: 'neutral' },
  },
  session: {
    ACTIVE: { label: 'Ativa', tone: 'success' },
    REVOKED: { label: 'Revogada', tone: 'neutral' },
    EXPIRED: { label: 'Expirada', tone: 'neutral' },
  },
  active: {
    true: { label: 'Ativo', tone: 'success' },
    false: { label: 'Inativo', tone: 'neutral' },
  },
  debtor: {
    ACTIVE: { label: 'Ativo', tone: 'success' },
    ANONYMIZED: { label: 'Anonimizado', tone: 'neutral' },
  },
  title: {
    OPEN: { label: 'Em cobrança', tone: 'info' },
    SUSPENDED: { label: 'Suspenso', tone: 'warning' },
    SETTLED: { label: 'Quitado', tone: 'success' },
    CANCELLED: { label: 'Cancelado', tone: 'neutral' },
    RETURNED: { label: 'Devolvido', tone: 'neutral' },
  },
  contact: {
    VALID: { label: 'Válido', tone: 'success' },
    UNVERIFIED: { label: 'Não verificado', tone: 'info' },
    INVALID: { label: 'Inválido', tone: 'danger' },
  },
  restriction: {
    ACTIVE: { label: 'Ativa', tone: 'danger' },
    REVOKED: { label: 'Revogada', tone: 'neutral' },
  },
  dataSubjectRequest: {
    RECEIVED: { label: 'Recebida', tone: 'info' },
    IDENTITY_VERIFIED: { label: 'Identidade confirmada', tone: 'info' },
    IN_PROGRESS: { label: 'Em atendimento', tone: 'warning' },
    COMPLETED: { label: 'Concluída', tone: 'success' },
    REJECTED: { label: 'Recusada', tone: 'neutral' },
  },
  retentionRun: {
    RUNNING: { label: 'Em execução', tone: 'info' },
    COMPLETED: { label: 'Concluída', tone: 'success' },
    FAILED: { label: 'Falhou', tone: 'danger' },
  },
  retentionItem: {
    ELIGIBLE: { label: 'Elegível', tone: 'info' },
    ANONYMIZED: { label: 'Anonimizado', tone: 'success' },
    RETAINED: { label: 'Mantido (base legal)', tone: 'warning' },
    FAILED: { label: 'Falhou', tone: 'danger' },
  },
  /** Vigência calculada na tela a partir de `validFrom`/`validUntil` (`legalBasisPeriod`). */
  /** `CollateralStatus` do módulo debt. */
  collateral: {
    ACTIVE: { label: 'Vigente', tone: 'success' },
    UNDER_ENFORCEMENT: { label: 'Em execução', tone: 'warning' },
    SEIZED: { label: 'Apreendida', tone: 'warning' },
    CONSOLIDATED: { label: 'Propriedade consolidada', tone: 'info' },
    AUCTIONED: { label: 'Leiloada', tone: 'info' },
    RELEASED: { label: 'Liberada', tone: 'neutral' },
    WRITTEN_OFF: { label: 'Baixada', tone: 'neutral' },
  },
  /** `CaseStatus` do módulo collection. */
  case: {
    OPEN: { label: 'Em aberto', tone: 'info' },
    IN_NEGOTIATION: { label: 'Em negociação', tone: 'info' },
    PROMISE: { label: 'Promessa', tone: 'warning' },
    AGREEMENT: { label: 'Acordo', tone: 'success' },
    DISPUTED: { label: 'Contestado', tone: 'danger' },
    SUSPENDED: { label: 'Suspenso', tone: 'warning' },
    LEGAL: { label: 'Jurídico', tone: 'neutral' },
    CLOSED: { label: 'Encerrado', tone: 'neutral' },
  },
  /** Tarefas e retornos (`TaskStatus`). */
  task: {
    PENDING: { label: 'Agendado', tone: 'info' },
    MISSED: { label: 'Atrasado', tone: 'danger' },
    DONE: { label: 'Concluído', tone: 'success' },
    CANCELLED: { label: 'Cancelado', tone: 'neutral' },
  },
  promise: {
    PENDING: { label: 'Aguardando pagamento', tone: 'warning' },
    KEPT: { label: 'Cumprida', tone: 'success' },
    BROKEN: { label: 'Quebrada', tone: 'danger' },
    CANCELLED: { label: 'Cancelada', tone: 'neutral' },
  },
  dispute: {
    OPEN: { label: 'Aberta', tone: 'warning' },
    UNDER_REVIEW: { label: 'Em análise', tone: 'info' },
    RESOLVED: { label: 'Resolvida', tone: 'success' },
    WITHDRAWN: { label: 'Retirada', tone: 'neutral' },
  },
  /** Versões da política de cobrança e negociação (`PolicyStatus`). */
  collectionPolicy: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    PUBLISHED: { label: 'Publicada', tone: 'success' },
    RETIRED: { label: 'Aposentada', tone: 'neutral' },
  },
  /** Resultado do `NegotiationGuard` (`/validations`). */
  negotiationOutcome: {
    WITHIN_AUTHORITY: { label: 'Dentro da alçada', tone: 'success' },
    REQUIRES_APPROVAL: { label: 'Requer aprovação', tone: 'warning' },
    REJECTED: { label: 'Rejeitado', tone: 'danger' },
  },
  /** Lotes de importação (`ImportBatchStatus`). */
  importBatch: {
    RECEIVED: { label: 'Na fila', tone: 'info' },
    AWAITING_FORMAT: { label: 'Aguardando formato', tone: 'warning' },
    VALIDATING: { label: 'Validando', tone: 'info' },
    VALIDATED: { label: 'Prévia concluída', tone: 'success' },
    PROCESSING: { label: 'Importando', tone: 'info' },
    COMPLETED: { label: 'Concluído', tone: 'success' },
    COMPLETED_WITH_ERRORS: { label: 'Concluído com erros', tone: 'warning' },
    FAILED: { label: 'Falhou', tone: 'danger' },
    CANCELLED: { label: 'Cancelado', tone: 'neutral' },
  },
  importIssue: {
    ERROR: { label: 'Erro', tone: 'danger' },
    WARNING: { label: 'Aviso', tone: 'warning' },
  },
  /** Conferência da importação (`ReviewStatus`). */
  importReview: {
    OPEN: { label: 'Em conferência', tone: 'info' },
    AWAITING_APPROVAL: { label: 'Aguardando aprovação', tone: 'warning' },
    COMMITTED: { label: 'Importação criada', tone: 'success' },
    REJECTED: { label: 'Recusada', tone: 'danger' },
    CANCELLED: { label: 'Cancelada', tone: 'neutral' },
  },
  /** Alertas de valor da conferência (`AlertStatus`). */
  importAlert: {
    OPEN: { label: 'Aberto', tone: 'warning' },
    CONFIRMED: { label: 'Confirmado', tone: 'success' },
    EXCLUDED: { label: 'Excluído do lote', tone: 'neutral' },
  },
  legalBasis: {
    CURRENT: { label: 'Vigente', tone: 'success' },
    FUTURE: { label: 'Futura', tone: 'info' },
    ENDED: { label: 'Encerrada', tone: 'neutral' },
  },
  /** `ProposalStatus` (negotiation). */
  proposal: {
    DRAFT: { label: 'Pronta para aceite', tone: 'info' },
    PENDING_APPROVAL: { label: 'Aguardando aprovação', tone: 'warning' },
    APPROVED: { label: 'Aprovada', tone: 'success' },
    REJECTED: { label: 'Reprovada', tone: 'danger' },
    ACCEPTED: { label: 'Aceita pelo devedor', tone: 'success' },
    FORMALIZED: { label: 'Formalizada', tone: 'success' },
    EXPIRED: { label: 'Expirada', tone: 'neutral' },
    HANDED_OFF: { label: 'Transferida pelo assistente', tone: 'warning' },
  },
  /** `AgreementStatus` (negotiation). */
  agreement: {
    FORMALIZED: { label: 'Formalizado', tone: 'info' },
    IN_PAYMENT: { label: 'Em pagamento', tone: 'info' },
    SETTLED: { label: 'Quitado', tone: 'success' },
    BROKEN: { label: 'Quebrado', tone: 'danger' },
    CANCELLED: { label: 'Cancelado', tone: 'neutral' },
    RENEGOTIATED: { label: 'Renegociado', tone: 'neutral' },
  },
  installment: {
    OPEN: { label: 'A vencer', tone: 'info' },
    PAID: { label: 'Paga', tone: 'success' },
    PARTIALLY_PAID: { label: 'Paga em parte', tone: 'warning' },
    OVERDUE: { label: 'Vencida', tone: 'danger' },
    CANCELLED: { label: 'Cancelada', tone: 'neutral' },
  },
  /** `ConversationStatus` (communication). */
  conversation: {
    OPEN: { label: 'Aberta', tone: 'info' },
    CLOSED: { label: 'Encerrada', tone: 'neutral' },
  },
  /** Dono do atendimento (`OwnerType`). */
  conversationOwner: {
    USER: { label: 'Com operador', tone: 'success' },
    QUEUE: { label: 'Na fila', tone: 'warning' },
    ASSISTANT: { label: 'Com o assistente', tone: 'info' },
    NONE: { label: 'Sem atendimento', tone: 'neutral' },
  },
  /** `MessageStatus` (communication). */
  message: {
    QUEUED: { label: 'Na fila de envio', tone: 'info' },
    SENT: { label: 'Enviada', tone: 'info' },
    DELIVERED: { label: 'Entregue', tone: 'success' },
    READ: { label: 'Lida', tone: 'success' },
    FAILED: { label: 'Não enviada', tone: 'danger' },
    RECEIVED: { label: 'Recebida', tone: 'neutral' },
  },
  draft: {
    PENDING: { label: 'Aguardando revisão', tone: 'warning' },
    SENT: { label: 'Enviado', tone: 'success' },
    DISCARDED: { label: 'Descartado', tone: 'neutral' },
    EXPIRED: { label: 'Expirado', tone: 'neutral' },
  },
  template: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    PUBLISHED: { label: 'Publicado', tone: 'success' },
    RETIRED: { label: 'Retirado', tone: 'neutral' },
  },
  /** Aprovação do template pelo WhatsApp (`ApprovalStatus`). */
  templateApproval: {
    NOT_REQUIRED: { label: 'Sem aprovação', tone: 'neutral' },
    PENDING: { label: 'Aguardando o WhatsApp', tone: 'warning' },
    APPROVED: { label: 'Aprovado pelo WhatsApp', tone: 'success' },
    REJECTED: { label: 'Recusado pelo WhatsApp', tone: 'danger' },
  },
  /** Fases do caso jurídico (`LegalStage`). */
  legalStage: {
    ELIGIBLE: { label: 'Indicado', tone: 'info' },
    UNDER_REVIEW: { label: 'Em triagem', tone: 'info' },
    AWAITING_AUTHORIZATION: { label: 'Aguardando o credor', tone: 'warning' },
    EXTRAJUDICIAL: { label: 'Extrajudicial', tone: 'info' },
    FILED: { label: 'Ajuizado', tone: 'info' },
    IN_PROGRESS: { label: 'Em andamento', tone: 'info' },
    ENFORCEMENT: { label: 'Constrição', tone: 'warning' },
    SETTLED_IN_COURT: { label: 'Acordo judicial', tone: 'success' },
    CLOSED: { label: 'Encerrado', tone: 'neutral' },
    NOT_VIABLE: { label: 'Inviável', tone: 'neutral' },
    RETURNED_TO_CREDITOR: { label: 'Devolvido ao credor', tone: 'neutral' },
  },
  legalAuthorization: {
    REQUESTED: { label: 'Solicitada', tone: 'warning' },
    GRANTED: { label: 'Concedida', tone: 'success' },
    DENIED: { label: 'Negada', tone: 'danger' },
    SUPERSEDED: { label: 'Substituída', tone: 'neutral' },
  },
  legalMeasure: {
    PLANNED: { label: 'Planejada', tone: 'info' },
    SUBMITTED: { label: 'Enviada', tone: 'info' },
    EFFECTIVE: { label: 'Efetivada', tone: 'success' },
    FAILED: { label: 'Sem sucesso', tone: 'danger' },
    CANCELLED: { label: 'Cancelada', tone: 'neutral' },
    REMOVED: { label: 'Baixada', tone: 'neutral' },
  },
  lawsuit: {
    ACTIVE: { label: 'Ativo', tone: 'info' },
    SUSPENDED: { label: 'Suspenso', tone: 'warning' },
    ARCHIVED: { label: 'Arquivado', tone: 'neutral' },
    TERMINATED: { label: 'Extinto', tone: 'neutral' },
  },
  legalDeadline: {
    PENDING: { label: 'Em aberto', tone: 'info' },
    MET: { label: 'Cumprido', tone: 'success' },
    MISSED: { label: 'Perdido', tone: 'danger' },
    CANCELLED: { label: 'Cancelado', tone: 'neutral' },
  },
  hearing: {
    SCHEDULED: { label: 'Agendada', tone: 'info' },
    HELD: { label: 'Realizada', tone: 'success' },
    CANCELLED: { label: 'Cancelada', tone: 'neutral' },
  },
  legalExpense: {
    RECORDED: { label: 'Registrada', tone: 'info' },
    REIMBURSED: { label: 'Reembolsada', tone: 'success' },
    CANCELLED: { label: 'Cancelada', tone: 'neutral' },
  },
  /** `CampaignStatus` (campaign). */
  campaign: {
    DRAFT: { label: 'Em preparação', tone: 'info' },
    ACTIVE: { label: 'Ativa', tone: 'success' },
    PAUSED: { label: 'Pausada', tone: 'warning' },
    FINISHED: { label: 'Encerrada', tone: 'neutral' },
  },
  /** Versões da régua (`CadenceStatus`). */
  cadence: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    PUBLISHED: { label: 'Publicada', tone: 'success' },
    RETIRED: { label: 'Retirada', tone: 'neutral' },
  },
  /** Inscrição de um caso na campanha (`EnrollmentStatus`). */
  enrollment: {
    ACTIVE: { label: 'Em andamento', tone: 'info' },
    PAUSED: { label: 'Pausada', tone: 'warning' },
    FAILED: { label: 'Com falha', tone: 'danger' },
    HOLDOUT: { label: 'Grupo de controle', tone: 'neutral' },
    ENDED: { label: 'Encerrada', tone: 'neutral' },
  },
  /** Teste A/B (`ExperimentStatus`). */
  experiment: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    RUNNING: { label: 'Em execução', tone: 'success' },
    STOPPED: { label: 'Encerrado', tone: 'neutral' },
  },
  /** Execução de etapa (`StepOutcome`). */
  stepOutcome: {
    EXECUTED: { label: 'Executada', tone: 'success' },
    SKIPPED: { label: 'Pulada', tone: 'warning' },
    FAILED: { label: 'Falhou', tone: 'danger' },
  },
  /** Sessão do devedor no portal. */
  portalSession: {
    ACTIVE: { label: 'Ativa', tone: 'success' },
    ENDED: { label: 'Encerrada pelo devedor', tone: 'neutral' },
    REVOKED: { label: 'Revogada', tone: 'warning' },
    EXPIRED: { label: 'Expirada', tone: 'neutral' },
  },
  /** Pedido de atendimento vindo do portal. */
  portalRequest: {
    OPEN: { label: 'Aguardando envio', tone: 'info' },
    FORWARDED: { label: 'Na fila de atendimento', tone: 'success' },
    UNDELIVERED: { label: 'Sem conversa aberta', tone: 'danger' },
  },
  /** Instrução de pagamento (`InstructionStatus`). */
  paymentInstruction: {
    PENDING: { label: 'Registrando', tone: 'info' },
    ACTIVE: { label: 'Aguardando pagamento', tone: 'info' },
    PAID: { label: 'Paga', tone: 'success' },
    EXPIRED: { label: 'Expirada', tone: 'neutral' },
    CANCELLED: { label: 'Cancelada', tone: 'neutral' },
    FAILED: { label: 'Recusada pelo PSP', tone: 'danger' },
  },
  /** Pagamento (`PaymentStatus`). */
  payment: {
    INFORMED: { label: 'Aguardando aprovação', tone: 'warning' },
    CONFIRMED: { label: 'Confirmado', tone: 'success' },
    RECONCILED: { label: 'Conciliado', tone: 'success' },
    REJECTED: { label: 'Recusado', tone: 'neutral' },
    REVERSED: { label: 'Estornado', tone: 'danger' },
  },
  /** Importação de extrato (`StatementImportStatus`). */
  statementImport: {
    RECEIVED: { label: 'Na fila', tone: 'info' },
    PROCESSING: { label: 'Processando', tone: 'info' },
    PROCESSED: { label: 'Processado', tone: 'success' },
    FAILED: { label: 'Com falha', tone: 'danger' },
  },
  /** Linha do extrato (`StatementLineStatus`). */
  statementLine: {
    RECONCILED: { label: 'Conciliada', tone: 'success' },
    CREATED_PAYMENT: { label: 'Confirmou pagamento', tone: 'success' },
    DIVERGENT: { label: 'Divergente', tone: 'warning' },
    ALREADY_RECONCILED: { label: 'Já conciliada', tone: 'neutral' },
    IGNORED_DUPLICATE: { label: 'Repetida (ignorada)', tone: 'neutral' },
    IGNORED_DEBIT: { label: 'Débito (ignorado)', tone: 'neutral' },
  },
  /** Divergência de conciliação (`DivergenceStatus`). */
  divergence: {
    OPEN: { label: 'Em aberto', tone: 'warning' },
    RESOLVED: { label: 'Resolvida', tone: 'success' },
    DISMISSED: { label: 'Descartada', tone: 'neutral' },
  },
  /** Versão das regras de comissão (`RuleSetStatus`). */
  commissionRuleSet: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    PUBLISHED: { label: 'Publicada', tone: 'success' },
    DISCARDED: { label: 'Descartada', tone: 'neutral' },
  },
  /** Apuração do recebimento (`RatingStatus`). */
  receiptRating: {
    RATED: { label: 'Apurado', tone: 'success' },
    PENDING_RULES: { label: 'Aguardando regra', tone: 'warning' },
  },
  /** Demonstrativo (`StatementStatus`). */
  settlementStatement: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    CALCULATED: { label: 'Calculado', tone: 'info' },
    REVIEWED: { label: 'Revisado', tone: 'warning' },
    APPROVED: { label: 'Aprovado', tone: 'success' },
    SENT: { label: 'Enviado ao credor', tone: 'success' },
    ACKNOWLEDGED: { label: 'Aceito pelo credor', tone: 'success' },
    CONTESTED: { label: 'Contestado', tone: 'danger' },
    CANCELLED: { label: 'Cancelado', tone: 'neutral' },
  },
  /** Ajuste do demonstrativo (`SettlementAdjustment.Status`). */
  settlementAdjustment: {
    APPLIED: { label: 'Aplicado', tone: 'success' },
    PENDING_SECOND_APPROVAL: { label: 'Aguardando 2ª aprovação', tone: 'warning' },
    REJECTED: { label: 'Recusado', tone: 'neutral' },
  },
  /** Repasse (`CreditorTransfer.Status`). */
  settlementTransfer: {
    REGISTERED: { label: 'Registrado', tone: 'success' },
    CANCELLED: { label: 'Cancelado', tone: 'neutral' },
  },
  /** Versão de plano de comissão do negociador (`PlanStatus` + vigência calculada na tela). */
  commissionPlan: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    CURRENT: { label: 'Vigente', tone: 'success' },
    SCHEDULED: { label: 'Programada', tone: 'info' },
    ENDED: { label: 'Encerrada', tone: 'neutral' },
    PUBLISHED: { label: 'Publicada', tone: 'success' },
    RETIRED: { label: 'Aposentada', tone: 'neutral' },
  },
  /** Período de apuração (`PeriodStatus`). */
  commissionPeriod: {
    OPEN: { label: 'Aberto', tone: 'info' },
    CLOSED: { label: 'Fechado', tone: 'success' },
  },
  /** Extrato de comissão (`StatementStatus`). */
  commissionStatement: {
    CALCULATED: { label: 'Calculado', tone: 'info' },
    DISPUTED: { label: 'Contestado', tone: 'warning' },
    ADJUSTED: { label: 'Ajustado', tone: 'info' },
    APPROVED: { label: 'Aprovado', tone: 'success' },
    EXPORTED: { label: 'Exportado para a folha', tone: 'success' },
  },
  /** Resultado do extrato (`StatementOutcome`). */
  commissionOutcome: {
    PAYABLE: { label: 'A pagar', tone: 'success' },
    NOT_ELIGIBLE: { label: 'Não elegível', tone: 'neutral' },
    NO_PLAN: { label: 'Sem plano', tone: 'neutral' },
    SUSPENDED_QUALITY_GATE: { label: 'Suspenso pela monitoria', tone: 'warning' },
    NOT_COVERED: { label: 'Fora da elegibilidade', tone: 'neutral' },
  },
  /** Contestação de extrato (`DisputeStatus`). */
  commissionDispute: {
    OPEN: { label: 'Em aberto', tone: 'warning' },
    ACCEPTED: { label: 'Aceita', tone: 'success' },
    REJECTED: { label: 'Recusada', tone: 'neutral' },
  },
  /** Ajuste de extrato (`AdjustmentStatus`). */
  commissionAdjustment: {
    APPLIED: { label: 'Aplicado', tone: 'success' },
    PENDING_SECOND_APPROVAL: { label: 'Aguardando 2ª aprovação', tone: 'warning' },
    REJECTED: { label: 'Recusado', tone: 'neutral' },
  },
  /** Lançamento do razão de comissão (`EntryStatus`). */
  commissionEntry: {
    PENDING: { label: 'Em carência', tone: 'warning' },
    CLEARED: { label: 'Liberado', tone: 'success' },
    WAIVED: { label: 'Dispensado', tone: 'neutral' },
  },
  /** Ocorrência de conduta (`ConductOccurrence.Status`). */
  conductOccurrence: {
    CONFIRMED: { label: 'Confirmada', tone: 'danger' },
    DISMISSED: { label: 'Dispensada', tone: 'neutral' },
  },
  /** Chamada (`CallStatus`). */
  call: {
    QUEUED: { label: 'Na fila', tone: 'info' },
    DIALING: { label: 'Discando', tone: 'info' },
    RINGING: { label: 'Chamando', tone: 'info' },
    ANSWERED: { label: 'Em conversa', tone: 'success' },
    COMPLETED: { label: 'Encerrada', tone: 'neutral' },
    NO_ANSWER: { label: 'Não atendeu', tone: 'neutral' },
    BUSY: { label: 'Ocupado', tone: 'neutral' },
    FAILED: { label: 'Falhou', tone: 'danger' },
    CANCELLED: { label: 'Cancelada', tone: 'neutral' },
  },
  /** Presença do operador (`AgentStatus`). */
  agentPresence: {
    OFFLINE: { label: 'Fora da telefonia', tone: 'neutral' },
    AVAILABLE: { label: 'Disponível', tone: 'success' },
    ON_CALL: { label: 'Em chamada', tone: 'info' },
    WRAP_UP: { label: 'Pós-atendimento', tone: 'warning' },
    PAUSED: { label: 'Em pausa', tone: 'warning' },
  },
  /** Campanha de discagem (`DialerCampaignStatus`). */
  dialerCampaign: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    ACTIVE: { label: 'Discando', tone: 'success' },
    PAUSED: { label: 'Pausada', tone: 'warning' },
    FINISHED: { label: 'Encerrada', tone: 'neutral' },
  },
  /** Item da lista de discagem (`DialerEntryStatus`). */
  dialerEntry: {
    PENDING: { label: 'Aguardando', tone: 'info' },
    IN_PROGRESS: { label: 'Em chamada', tone: 'info' },
    COMPLETED: { label: 'Contatado', tone: 'success' },
    EXHAUSTED: { label: 'Tentativas esgotadas', tone: 'neutral' },
    EXCLUDED: { label: 'Excluído', tone: 'neutral' },
  },
  /** Gravação de chamada (`RecordingStatus`). */
  recording: {
    PENDING: { label: 'Copiando do provedor', tone: 'info' },
    STORED: { label: 'Disponível', tone: 'success' },
    FAILED: { label: 'Falhou', tone: 'danger' },
    PURGED: { label: 'Descartada', tone: 'neutral' },
  },
  /** Versão da política do assistente (`PolicyStatus`). */
  assistantPolicy: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    PUBLISHED: { label: 'Publicada', tone: 'success' },
    RETIRED: { label: 'Aposentada', tone: 'neutral' },
  },
  /** Modo de operação do assistente (`AssistantMode`). */
  assistantMode: {
    OFF: { label: 'Desligado', tone: 'neutral' },
    SHADOW: { label: 'Sombra', tone: 'info' },
    COPILOT: { label: 'Copiloto', tone: 'info' },
    AUTONOMOUS_WITH_APPROVAL: { label: 'Autônomo com aprovação', tone: 'warning' },
    AUTONOMOUS: { label: 'Autônomo', tone: 'success' },
  },
  /** Revisão de turno do assistente (`ReviewStatus`). */
  assistantReview: {
    PENDING: { label: 'A revisar', tone: 'warning' },
    REVIEWED: { label: 'Revisado', tone: 'success' },
  },
  /** Resultado do turno do assistente (`TurnOutcome`). */
  assistantTurn: {
    REPLIED: { label: 'Respondeu', tone: 'success' },
    DRAFTED: { label: 'Sugeriu rascunho', tone: 'info' },
    HANDED_OFF: { label: 'Transferiu', tone: 'warning' },
    APPROVAL_REQUESTED: { label: 'Pediu aprovação', tone: 'warning' },
    SEND_BLOCKED: { label: 'Envio bloqueado', tone: 'danger' },
    GUARDRAIL_BLOCKED: { label: 'Bloqueado pelo guardrail', tone: 'danger' },
    SKIPPED: { label: 'Sem ação', tone: 'neutral' },
    FAILED: { label: 'Falhou', tone: 'danger' },
  },
};

export function describeStatus(domain: StatusDomain, status: string | boolean | null | undefined): StatusDescriptor {
  const key = String(status);
  return STATUS[domain][key] ?? { label: key, tone: 'neutral' };
}

export function statusOptions(domain: StatusDomain): { value: string; label: string }[] {
  return Object.entries(STATUS[domain]).map(([value, d]) => ({ value, label: d.label }));
}

/** Transições permitidas de carteira (espelho de `PortfolioStatus.allowedTransitions`). */
export const PORTFOLIO_TRANSITIONS: Record<string, readonly string[]> = {
  DRAFT: ['ACTIVE', 'CLOSED'],
  ACTIVE: ['SUSPENDED', 'RETURNED', 'CLOSED'],
  SUSPENDED: ['ACTIVE', 'RETURNED', 'CLOSED'],
  RETURNED: ['CLOSED'],
  CLOSED: [],
};

/** Transições que exigem motivo (`Portfolio.transitionTo`). */
export const PORTFOLIO_REASON_REQUIRED: readonly string[] = ['SUSPENDED', 'RETURNED'];

/** Transições manuais de título (espelho de `TitleStatus.allowedTransitions`, sem as automáticas). */
export const TITLE_MANUAL_ACTIONS: Record<string, readonly ('suspend' | 'reactivate' | 'cancel' | 'return')[]> = {
  OPEN: ['suspend', 'cancel', 'return'],
  SUSPENDED: ['reactivate', 'cancel', 'return'],
  SETTLED: [],
  CANCELLED: [],
  RETURNED: [],
};

/** Situações finais de solicitação de titular (`RequestStatus.isFinal`). */
export const DSR_FINAL_STATUSES: readonly string[] = ['COMPLETED', 'REJECTED'];
