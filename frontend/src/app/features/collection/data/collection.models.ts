/** Contratos dos controllers do módulo collection (`collection.application.port.in`). */

/** Valores monetários (`BigDecimal`) chegam como número JSON. */
export type Amount = number;

export type CaseStatus = 'OPEN' | 'IN_NEGOTIATION' | 'PROMISE' | 'AGREEMENT' | 'DISPUTED' | 'SUSPENDED' | 'LEGAL' | 'CLOSED';
export type OwnerType = 'ASSISTANT' | 'QUEUE' | 'USER' | 'NONE';
export type InteractionChannel = 'PHONE' | 'WHATSAPP' | 'EMAIL' | 'SMS' | 'IN_PERSON' | 'PORTAL' | 'LETTER';
export type InteractionDirection = 'OUTBOUND' | 'INBOUND';
export type EligibilityReason =
  | 'CASE_CLOSED'
  | 'CASE_SUSPENDED'
  | 'CASE_LEGAL'
  | 'DISPUTE_OPEN'
  | 'PORTFOLIO_NOT_OPERABLE'
  | 'NO_OPEN_TITLES'
  | 'TITLES_DISPUTED'
  | 'DEBTOR_ANONYMIZED'
  | 'CONTACT_RESTRICTED';

/** `CaseUseCases.CaseView` */
export interface CaseView {
  id: string;
  debtorId: string;
  creditorId: string;
  portfolioId: string;
  status: CaseStatus;
  resumeStatus: CaseStatus | null;
  statusReason: string | null;
  openedAt: string;
  closedAt: string | null;
  closeReason: string | null;
  openBalance: Amount;
  eligibleBalance: Amount;
  openTitles: number;
  suspendedTitles: number;
  disputedTitles: number;
  oldestDueDate: string | null;
  daysOverdue: number;
  eligible: boolean;
  eligibilityReasons: string[];
  contactableChannels: string[];
  eligibilityEvaluatedAt: string | null;
  queueId: string | null;
  ownerType: OwnerType;
  assigneeUserId: string | null;
  assigneeTeamId: string | null;
  assignedAt: string | null;
  nextActionAt: string | null;
  lastInteractionAt: string | null;
  lastEffectiveContactAt: string | null;
  interactionCount: number;
  effectiveContactCount: number;
  score: number | null;
  version: number;
}

export interface CaseFilter {
  creditorId?: string | null;
  portfolioId?: string | null;
  debtorId?: string | null;
  /** Situações separadas por vírgula (o servidor recebe `Set<String>`). */
  status?: string | null;
  queueId?: string | null;
  /** Id do responsável ou `me`. */
  assignee?: string | null;
  ownerType?: OwnerType | null;
  eligible?: boolean | null;
}

/** `CaseUseCases.CaseSheet` — devedor e contatos sempre mascarados. */
export interface CaseSheet {
  collectionCase: CaseView;
  debtor: SheetDebtor;
  titles: SheetTitle[];
  activeRestrictions: SheetRestriction[];
  openDisputes: DisputeView[];
  pendingPromise: PromiseView | null;
  openTasks: TaskView[];
  recentInteractions: InteractionView[];
  otherCases: CaseView[];
}

export interface SheetDebtor {
  id: string;
  name: string;
  maskedDocument: string | null;
  personType: 'INDIVIDUAL' | 'COMPANY';
  status: 'ACTIVE' | 'ANONYMIZED';
  contacts: SheetContact[];
}

export interface SheetContact {
  contactId: string;
  type: 'PHONE' | 'EMAIL' | 'ADDRESS';
  maskedValue: string;
  label: 'MOBILE' | 'HOME' | 'WORK' | 'OTHER';
  whatsapp: boolean;
  status: 'UNVERIFIED' | 'VALID' | 'INVALID';
  priority: number;
}

export interface SheetTitle {
  titleId: string;
  externalId: string;
  installmentNumber: number | null;
  dueDate: string;
  principalAmount: Amount;
  openBalance: Amount;
  status: 'OPEN' | 'SETTLED' | 'CANCELLED' | 'SUSPENDED' | 'RETURNED';
  disputeBlocked: boolean;
  daysOverdue: number;
}

/** `channel` nulo = todos os canais; `creditorId` nulo = todos os credores. */
export interface SheetRestriction {
  id: string;
  type: string;
  channel: string | null;
  creditorId: string | null;
  contactMasked: string | null;
  startsAt: string;
  endsAt: string | null;
}

export type AssignmentMode =
  | 'OPENED'
  | 'ROUTING'
  | 'NEXT_CASE'
  | 'MANUAL'
  | 'DISTRIBUTION'
  | 'REDISTRIBUTION'
  | 'RELEASE'
  | 'INTERACTION'
  | 'ASSISTANT_HANDOFF'
  | 'TAKE_OVER'
  | 'CLOSED';

/** `CaseWorkUseCases.AssignmentView` (`validTo` nulo no período vigente). */
export interface AssignmentView {
  id: string;
  caseId: string;
  mode: AssignmentMode;
  ownerType: OwnerType;
  userId: string | null;
  teamId: string | null;
  queueId: string | null;
  previousOwnerType: OwnerType | null;
  previousUserId: string | null;
  reason: string | null;
  validFrom: string;
  validTo: string | null;
  createdBy: string | null;
}

/** `CaseWorkUseCases.DistributionResult` */
export interface DistributionResult {
  assigned: number;
  unassigned: number;
  assignments: { caseId: string; userId: string }[];
}

export interface AssignRequest {
  userId: string | null;
  teamId: string | null;
  reason: string | null;
}

export interface RedistributeRequest {
  fromUserId: string;
  toUserIds: string[];
  reason: string | null;
}

// --------------------------------------------------------------------- filas

export type QueueOrdering = 'OLDEST_OVERDUE' | 'HIGHEST_BALANCE' | 'LOWEST_BALANCE' | 'LEAST_RECENTLY_WORKED' | 'HIGHEST_SCORE';
export type QueueStrategy = 'PULL' | 'ROUND_ROBIN' | 'LEAST_LOADED';

/** `QueueUseCases.QueueView` */
export interface QueueView {
  id: string;
  code: string;
  name: string;
  description: string | null;
  active: boolean;
  priority: number;
  creditorId: string | null;
  portfolioId: string | null;
  minDaysOverdue: number | null;
  maxDaysOverdue: number | null;
  minBalance: Amount | null;
  maxBalance: Amount | null;
  minScore: number | null;
  maxScore: number | null;
  ordering: QueueOrdering;
  strategy: QueueStrategy;
  maxCasesPerUser: number | null;
  teamIds: string[];
  updatedAt: string;
  version: number;
}

/** `CollectionQueueController.QueueRequest` (`code` só na criação). */
export interface QueueRequest {
  code: string | null;
  name: string;
  description: string | null;
  priority: number;
  creditorId: string | null;
  portfolioId: string | null;
  minDaysOverdue: number | null;
  maxDaysOverdue: number | null;
  minBalance: number | null;
  maxBalance: number | null;
  minScore: number | null;
  maxScore: number | null;
  ordering: QueueOrdering;
  strategy: QueueStrategy;
  maxCasesPerUser: number | null;
  teamIds: string[];
}

/** `QueueUseCases.QueueSummary` */
export interface QueueSummary {
  queueId: string;
  available: number;
  waiting: number;
  assigned: number;
  loads: { userId: string; activeCases: number }[];
}

// --------------------------------------------------------------------- tabulações e interações

/** `TabulationUseCases.TabulationView` */
export interface TabulationView {
  id: string;
  code: string;
  name: string;
  effectiveContact: boolean;
  promise: boolean;
  callback: boolean;
  requiresNote: boolean;
  displayOrder: number;
  active: boolean;
  version: number;
}

export interface TabulationRequest {
  code: string | null;
  name: string;
  effectiveContact: boolean;
  promise: boolean;
  callback: boolean;
  requiresNote: boolean;
  displayOrder: number;
}

/** `InteractionController.InteractionRequest` (registro manual pela tela). */
export interface InteractionRequest {
  channel: InteractionChannel;
  direction: InteractionDirection;
  tabulationId: string;
  note: string | null;
  contactId: string | null;
  startedAt: string | null;
  endedAt: string | null;
  promise: { amount: number; dueDate: string } | null;
  /** `dueAt` em `LocalDateTime` no fuso de operação (America/Sao_Paulo), sem deslocamento. */
  callback: { dueAt: string; assigneeUserId: string | null; note: string | null } | null;
  completesTaskId: string | null;
}

/** `InteractionUseCases.InteractionView` */
export interface InteractionView {
  id: string;
  caseId: string;
  debtorId: string;
  creditorId: string;
  portfolioId: string;
  channel: InteractionChannel;
  direction: InteractionDirection;
  tabulationId: string | null;
  outcomeCode: string | null;
  effectiveContact: boolean;
  note: string | null;
  contactId: string | null;
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
  operatorId: string | null;
  actorType: 'USER' | 'SYSTEM' | 'ASSISTANT' | 'INTEGRATION';
  source: string;
  externalRef: string | null;
  createdAt: string;
}

/** `InteractionUseCases.RecordResult` */
export interface RecordResult {
  interaction: InteractionView;
  duplicate: boolean;
  promise: PromiseView | null;
  callback: TaskView | null;
  collectionCase: CaseView;
}

// --------------------------------------------------------------------- tarefas, promessas

export type TaskType = 'CALLBACK' | 'FOLLOW_UP';
export type TaskStatus = 'PENDING' | 'MISSED' | 'DONE' | 'CANCELLED';

/** `TaskUseCases.TaskView` */
export interface TaskView {
  id: string;
  caseId: string;
  creditorId: string;
  type: TaskType;
  status: TaskStatus;
  dueAt: string;
  /** Horário combinado no fuso de operação ("yyyy-MM-ddTHH:mm[:ss]"). */
  dueAtLocal: string;
  toleranceUntil: string;
  assigneeUserId: string | null;
  note: string | null;
  originInteractionId: string | null;
  completedAt: string | null;
  completedBy: string | null;
  completionInteractionId: string | null;
  completedOnTime: boolean | null;
  missedAt: string | null;
  cancelReason: string | null;
  version: number;
}

export interface ScheduleTaskRequest {
  type: TaskType;
  dueAt: string;
  assigneeUserId: string | null;
  note: string | null;
}

export interface AgendaFilter {
  userId?: string | null;
  from?: string | null;
  to?: string | null;
  status?: string | null;
}

export type PromiseStatus = 'PENDING' | 'KEPT' | 'BROKEN' | 'CANCELLED';

/** `PromiseUseCases.PromiseView` */
export interface PromiseView {
  id: string;
  caseId: string;
  debtorId: string;
  creditorId: string;
  portfolioId: string;
  amount: Amount;
  dueDate: string;
  breaksAfter: string;
  promisedAt: string;
  status: PromiseStatus;
  paidAmount: Amount;
  originInteractionId: string | null;
  operatorId: string | null;
  resolvedAt: string | null;
  resolutionReason: string | null;
  version: number;
}

// --------------------------------------------------------------------- contestações

export type DisputeType = 'NOT_RECOGNIZED' | 'ALREADY_PAID' | 'WRONG_AMOUNT' | 'FRAUD' | 'PRESCRIBED' | 'PRODUCT_OR_SERVICE' | 'OTHER';
export type DisputeOrigin = 'OPERATOR' | 'DEBTOR' | 'CREDITOR' | 'LEGAL';
export type DisputeStatus = 'OPEN' | 'UNDER_REVIEW' | 'RESOLVED' | 'WITHDRAWN';
export type DisputeOutcome = 'UPHELD' | 'REJECTED' | 'PARTIALLY_UPHELD';

/** `DisputeUseCases.DisputeView` */
export interface DisputeView {
  id: string;
  caseId: string;
  debtorId: string;
  creditorId: string;
  portfolioId: string;
  titleIds: string[];
  type: DisputeType;
  origin: DisputeOrigin;
  status: DisputeStatus;
  description: string | null;
  blocksCollection: boolean;
  disputedAmount: Amount | null;
  openedAt: string;
  responseDueDate: string;
  overdue: boolean;
  outcome: DisputeOutcome | null;
  resolutionNote: string | null;
  resolvedAt: string | null;
  resolvedBy: string | null;
  evidenceCount: number;
  version: number;
}

export interface OpenDisputeRequest {
  titleIds: string[];
  type: DisputeType;
  origin: DisputeOrigin;
  description: string;
  blocksCollection: boolean;
  responseDueDate: string | null;
}

export interface DisputeFilter {
  creditorId?: string | null;
  status?: string | null;
  overdue?: boolean | null;
}

/** `DisputeUseCases.EvidenceView` */
export interface EvidenceView {
  id: string;
  disputeId: string;
  fileName: string;
  contentType: string;
  size: number;
  sha256: string;
  createdAt: string;
  createdBy: string | null;
}

// --------------------------------------------------------------------- parâmetros

/** `CollectionSettingsUseCases.SettingsView` */
export interface SettingsView {
  promiseToleranceDays: number;
  promiseMaxDays: number;
  callbackToleranceMinutes: number;
  disputeResponseDays: number;
  customized: boolean;
  updatedAt: string | null;
}

export interface SettingsRequest {
  promiseToleranceDays: number;
  promiseMaxDays: number;
  callbackToleranceMinutes: number;
  disputeResponseDays: number;
}
