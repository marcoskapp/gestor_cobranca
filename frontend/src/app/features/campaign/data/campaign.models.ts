/**
 * Contratos dos controllers do módulo campaign (`/api/v1/campaign`): segmentos, campanhas, réguas
 * versionadas, execução (inscrições e painel) e testes A/B. Espelham os records de
 * `campaign.application.port.in` e os value objects serializados de `campaign.domain.model`.
 */

export type CampaignStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'FINISHED';
export type CadenceStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';
export type CampaignPurpose = 'EXTRAJUDICIAL_COLLECTION' | 'REMINDER' | 'AGREEMENT_FOLLOW_UP' | 'INFORMATIVE';
export type EnrollmentStatus = 'ACTIVE' | 'PAUSED' | 'FAILED' | 'HOLDOUT' | 'ENDED';
export type ExperimentStatus = 'DRAFT' | 'RUNNING' | 'STOPPED';
export type ExperimentMetric = 'EFFECTIVE_CONTACT' | 'REPLY' | 'AGREEMENT' | 'PAYMENT' | 'RECOVERED_PER_DEBTOR';
export type ActionType = 'SEND_MESSAGE' | 'CREATE_TASK' | 'HAND_TO_ASSISTANT' | 'MOVE_TO_QUEUE' | 'OFFER';
export type ReplyTransition = 'STOP' | 'GOTO_STEP' | 'HAND_TO_ASSISTANT' | 'MOVE_TO_QUEUE' | 'CONTINUE';
export type MessageChannel = 'WHATSAPP' | 'EMAIL' | 'SMS';
export type ContactChannel = MessageChannel | 'PHONE';
export type TaskType = 'CALLBACK' | 'FOLLOW_UP';
export type DayOfWeek = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';
export type VariableSource =
  | 'DEBTOR_FIRST_NAME'
  | 'OPEN_BALANCE'
  | 'ELIGIBLE_BALANCE'
  | 'OLDEST_DUE_DATE'
  | 'DAYS_OVERDUE'
  | 'CREDITOR_NAME'
  | 'OFFER_AMOUNT'
  | 'OFFER_DUE_DATE'
  | 'PAYMENT_LINK'
  | 'NEXT_INSTALLMENT_AMOUNT'
  | 'NEXT_INSTALLMENT_DUE_DATE'
  | 'PORTAL_LINK'
  | 'PORTAL_LINK_HOURS'
  | 'LITERAL';

// --------------------------------------------------------------------- segmentos

/** `SegmentCriteria`: todos os filtros por E; nulo/vazio = sem filtro. */
export interface SegmentCriteria {
  portfolioIds: string[] | null;
  minDaysOverdue: number | null;
  maxDaysOverdue: number | null;
  minBalance: number | null;
  maxBalance: number | null;
  caseStatuses: string[] | null;
  /** Padrão `true` no servidor (RB02). */
  eligibleOnly: boolean | null;
  contactableChannel: ContactChannel | null;
  minInteractions: number | null;
  maxInteractions: number | null;
  everEffectiveContact: boolean | null;
  noInteractionDays: number | null;
  noEffectiveContactDays: number | null;
  pendingPromise: boolean | null;
  activeAgreement: boolean | null;
  minBrokenAgreements: number | null;
  minScore: number | null;
  maxScore: number | null;
  ownerTypes: string[] | null;
  /** Próxima parcela em aberto do acordo em vigor vence em ao menos N dias (0 = hoje). */
  minDaysToInstallment: number | null;
  /** Idem, vence em até N dias (lembrete de parcela). */
  maxDaysToInstallment: number | null;
}

export interface SegmentView {
  id: string;
  /** Nulo = segmento da assessoria (todos os credores). */
  creditorId: string | null;
  code: string;
  name: string;
  description: string | null;
  criteria: SegmentCriteria;
  active: boolean;
  createdAt: string;
  updatedAt: string | null;
  version: number;
}

export interface CreateSegmentRequest {
  creditorId: string | null;
  code: string;
  name: string;
  description: string | null;
  criteria: SegmentCriteria;
}

export interface UpdateSegmentRequest {
  name: string;
  description: string | null;
  criteria: SegmentCriteria;
}

export interface PreviewRequest {
  creditorId: string | null;
  portfolioId: string | null;
  criteria: SegmentCriteria;
}

/** Contagem prévia: casos, devedores distintos e saldo em aberto somado. */
export interface AudienceCount {
  cases: number;
  debtors: number;
  openBalance: number;
  evaluatedAt: string;
}

export interface SegmentFilter {
  creditorId: string | null;
  active: boolean | null;
}

// --------------------------------------------------------------------- campanhas

export interface CampaignSettings {
  name: string;
  description: string | null;
  segmentId: string;
  purpose: CampaignPurpose | null;
  priority: number | null;
  exclusivityGroup: string | null;
  reenrollAfterDays: number | null;
  attributionWindowDays: number | null;
  paymentPauseDays: number | null;
  maxDeferralHours: number | null;
  exitWhenOutOfSegment: boolean | null;
}

export interface CreateCampaignRequest {
  code: string;
  creditorId: string;
  portfolioId: string | null;
  settings: CampaignSettings;
}

export interface CampaignView {
  id: string;
  code: string;
  name: string;
  description: string | null;
  creditorId: string;
  portfolioId: string | null;
  segmentId: string;
  purpose: CampaignPurpose;
  status: CampaignStatus;
  priority: number;
  exclusivityGroup: string | null;
  reenrollAfterDays: number | null;
  attributionWindowDays: number;
  paymentPauseDays: number | null;
  maxDeferralHours: number;
  exitWhenOutOfSegment: boolean;
  publishedCadenceId: string | null;
  publishedCadenceVersion: number | null;
  hasDraft: boolean;
  activatedAt: string | null;
  pausedAt: string | null;
  pauseNote: string | null;
  finishedAt: string | null;
  createdAt: string;
  version: number;
}

export interface CampaignFilter {
  creditorId: string | null;
  status: string | null;
}

// --------------------------------------------------------------------- régua

/** `SendWindow`: horários `HH:mm[:ss]` no fuso de operação; início = fim = o dia todo; dias vazios = todos. */
export interface SendWindow {
  start: string | null;
  end: string | null;
  days: DayOfWeek[] | null;
}

export interface StepCondition {
  criteria: SegmentCriteria | null;
  requireNoReply: boolean | null;
  requireNoEffectiveContact: boolean | null;
  requirePreviousMessageUnread: boolean | null;
}

export interface OfferSpec {
  installments: number | null;
  concessionLevel: number | null;
  validityDays: number | null;
}

export interface StepAction {
  type: ActionType;
  channel: MessageChannel | null;
  templateCode: string | null;
  /** Variável do template → origem (`OPEN_BALANCE`, `LITERAL:texto`). */
  variables: Record<string, string> | null;
  taskType: TaskType | null;
  taskDueInMinutes: number | null;
  taskNote: string | null;
  teamId: string | null;
  offer: OfferSpec | null;
}

export interface CadenceStep {
  code: string;
  name: string | null;
  waitMinutes: number | null;
  window: SendWindow | null;
  condition: StepCondition | null;
  action: StepAction;
  /** Nulo = transição padrão da régua. */
  onReply: ReplyTransition | null;
  onReplyStepCode: string | null;
}

export interface CadenceDefinition {
  defaultOnReply: ReplyTransition | null;
  defaultOnReplyStepCode: string | null;
  /** Equipe de destino de `MOVE_TO_QUEUE` por resposta (nula = fila do roteamento). */
  replyTeamId: string | null;
  steps: CadenceStep[];
}

export interface CadenceView {
  id: string;
  campaignId: string;
  versionNumber: number;
  status: CadenceStatus;
  definition: CadenceDefinition;
  publishedAt: string | null;
  publishedBy: string | null;
  retiredAt: string | null;
  createdAt: string;
  version: number;
}

// --------------------------------------------------------------------- execução

export interface CampaignExecutionResult {
  campaignId: string;
  status: CampaignStatus;
  /** Inscrições afetadas (reprocessadas, encerradas…). */
  affected: number;
}

export interface EnrollmentView {
  id: string;
  campaignId: string;
  cadenceId: string;
  caseId: string;
  debtorId: string;
  creditorId: string;
  portfolioId: string | null;
  status: EnrollmentStatus;
  experimentId: string | null;
  variantCode: string | null;
  control: boolean;
  enrolledAt: string;
  nextStepCode: string | null;
  nextStepIteration: number;
  nextRunAt: string | null;
  lastStepCode: string | null;
  lastStepAt: string | null;
  stepsExecuted: number;
  stepsSkipped: number;
  pauseReason: string | null;
  pausedAt: string | null;
  resumeAt: string | null;
  endReason: string | null;
  endedAt: string | null;
  observeUntil: string | null;
  repliedAt: string | null;
  effectiveContactAt: string | null;
  lastErrorCode: string | null;
  lastErrorAt: string | null;
  version: number;
}

export interface StepExecutionView {
  id: string;
  stepCode: string;
  iteration: number;
  actionType: ActionType | null;
  channel: string | null;
  outcome: 'EXECUTED' | 'SKIPPED' | 'FAILED';
  reason: string | null;
  reasonDetail: string | null;
  messageId: string | null;
  messageStatus: string | null;
  taskId: string | null;
  variantCode: string | null;
  executedAt: string;
}

export interface EnrollmentDetail {
  enrollment: EnrollmentView;
  steps: StepExecutionView[];
}

export interface EnrollmentFilter {
  status: string | null;
  debtorId: string | null;
  caseId: string | null;
}

export interface StepTotals {
  executed: number;
  skipped: number;
  failed: number;
}

export interface StepBreakdown {
  stepCode: string;
  actionType: ActionType | null;
  channel: string | null;
  executed: number;
  skipped: number;
  failed: number;
}

export interface MessageTotals {
  sent: number;
  delivered: number;
  read: number;
  failed: number;
  /** Negadas pelo porteiro (verificação prévia ou no envio). */
  blocked: number;
}

export interface OutcomeTotals {
  repliedEnrollments: number;
  effectiveContacts: number;
  agreements: number;
  agreementAmount: number;
  payingEnrollments: number;
  /** Pagamentos confirmados atribuídos, líquidos de estornos. */
  recoveredAmount: number;
}

export interface CampaignPanel {
  campaignId: string;
  computedAt: string;
  enrollments: number;
  enrollmentsByStatus: Record<string, number>;
  pausedByReason: Record<string, number>;
  endedByReason: Record<string, number>;
  suppressedByConcurrency: number;
  steps: StepTotals;
  byStep: StepBreakdown[];
  skippedByReason: Record<string, number>;
  failedByReason: Record<string, number>;
  messages: MessageTotals;
  outcomes: OutcomeTotals;
}

// --------------------------------------------------------------------- testes A/B

/** Alteração de uma etapa numa variante; campos nulos mantêm a régua. */
export interface StepOverride {
  stepCode: string;
  channel: MessageChannel | null;
  templateCode: string | null;
  window: SendWindow | null;
  offer: OfferSpec | null;
}

export interface ExperimentVariant {
  code: string;
  name: string;
  /** Peso relativo entre os braços que não são controle (1–100). */
  weight: number | null;
  overrides: StepOverride[];
}

export interface ExperimentInput {
  name: string;
  hypothesis: string | null;
  primaryMetric: ExperimentMetric | null;
  /** % de devedores no grupo de controle (0–50). */
  controlShare: number | null;
  variants: ExperimentVariant[];
  observationDays: number | null;
  minSamplePerArm: number | null;
}

export interface ExperimentView {
  id: string;
  campaignId: string;
  name: string;
  hypothesis: string | null;
  primaryMetric: ExperimentMetric;
  status: ExperimentStatus;
  controlShare: number;
  variants: ExperimentVariant[];
  observationDays: number;
  minSamplePerArm: number;
  startedAt: string | null;
  stoppedAt: string | null;
  stopReason: string | null;
  createdAt: string;
  version: number;
}

/** Intervalo de 95% da diferença para o braço de referência. */
export interface Difference {
  estimate: number;
  lower: number;
  upper: number;
  /** O intervalo não contém o zero. */
  significant: boolean;
}

export interface MetricResult {
  metric: ExperimentMetric;
  /** Devedores com o resultado (nulo na métrica de valor). */
  successes: number | null;
  denominator: number;
  /** Taxa (0–1) ou valor médio por devedor. */
  estimate: number | null;
  lower: number | null;
  upper: number | null;
  difference: Difference | null;
  warnings: string[];
}

export interface ArmResult {
  arm: string;
  control: boolean;
  /** Denominador de todas as métricas do braço. */
  debtors: number;
  metrics: MetricResult[];
}

export interface ExperimentResults {
  experimentId: string;
  status: ExperimentStatus;
  primaryMetric: ExperimentMetric;
  observationDays: number;
  matureOnly: boolean;
  computedAt: string;
  /** `CONTROL` ou, sem controle, a primeira variante. */
  baselineArm: string | null;
  arms: ArmResult[];
  warnings: string[];
}
