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
  | 'stepOutcome';

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
