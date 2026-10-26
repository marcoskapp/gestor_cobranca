/** Contratos do módulo assistant (`assistant.application.port.in` e records de `assistant.web`). */

export type AssistantMode = 'OFF' | 'SHADOW' | 'COPILOT' | 'AUTONOMOUS_WITH_APPROVAL' | 'AUTONOMOUS';
/**
 * `ModelRouting`: `BY_STAGE` usa o modelo avançado só em negociação, proposta, aceite e Pix; `ADVANCED_ONLY`
 * usa o avançado em todas as chamadas (piloto).
 */
export type ModelRouting = 'BY_STAGE' | 'ADVANCED_ONLY';
export type PolicyStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';
export type DayOfWeek = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

/** `PolicySettings.ServiceWindow` (fim exclusivo; "HH:mm:ss"). */
export interface ServiceWindow {
  days: DayOfWeek[];
  start: string;
  end: string;
}

export interface ExtraTrigger {
  keyword: string;
  reasonCode: string | null;
}

export interface PolicyLimits {
  maxOutputTokens: number | null;
  maxTokensPerTurn: number | null;
  maxToolCallsPerTurn: number | null;
  timeoutSeconds: number | null;
  maxCostPerConversation: number | null;
}

/**
 * `PolicySettings.PixInConversation` — Pix gerado pelo assistente na conversa (ADR-0009). Desligado por
 * padrão; os critérios de "conversa amigável e conciliação tranquila" são avaliados no servidor.
 */
export interface PixInConversation {
  enabled: boolean | null;
  maxAmount: number | null;
  maxPerConversationPerDay: number | null;
  expirationMinutes: number | null;
  riskLookbackDays: number | null;
}

/** `PolicySettings` (jsonb esquema v1; nulos recebem o padrão no servidor). */
export interface PolicySettings {
  mode: AssistantMode;
  channels: string[] | null;
  serviceHours: ServiceWindow[] | null;
  authorityProfileId: string | null;
  promptCode: string | null;
  promptVersion: number | null;
  tone: 'FORMAL' | 'PROXIMO' | null;
  maxTurns: number | null;
  maxMisunderstandings: number | null;
  minConfidence: number | null;
  requireIdentityVerification: boolean | null;
  maxIdentityAttempts: number | null;
  allowedTopics: string[] | null;
  forbiddenTopics: string[] | null;
  extraTriggers: ExtraTrigger[] | null;
  requiredProfiles: Record<string, string> | null;
  identificationMessage: string | null;
  handoffMessage: string | null;
  fallbackMessage: string | null;
  approvalMessage: string | null;
  limits: PolicyLimits | null;
  reviewSampleRate: number | null;
  allowCreditBureauMention: boolean | null;
  pixInConversation?: PixInConversation | null;
  /** Nível de modelo por chamada (`ModelRouting`); ausente nas políticas gravadas antes da opção = `BY_STAGE`. */
  modelRouting?: ModelRouting | null;
}

/** `AssistantPolicyUseCases.PolicyView` */
export interface PolicyView {
  id: string;
  code: string;
  name: string;
  creditorId: string | null;
  portfolioId: string | null;
  policyVersion: number;
  status: PolicyStatus;
  mode: AssistantMode;
  settings: PolicySettings;
  promptCode: string;
  promptVersion: number;
  publishedAt: string | null;
  publishedBy: string | null;
  retiredAt: string | null;
  retireReason: string | null;
  createdAt: string;
  version: number;
}

export interface PromptView {
  code: string;
  version: number;
  description: string;
  sha256: string;
  length: number;
}

export interface CreatePolicyRequest {
  creditorId: string | null;
  portfolioId: string | null;
  code: string;
  name: string;
  settings: PolicySettings;
}

// --------------------------------------------------------------------------- trilha e revisão

export type ReviewVerdict = 'ADEQUATE' | 'NEEDS_IMPROVEMENT' | 'OUT_OF_POLICY';

/** `AssistantReviewUseCases.ReviewView` */
export interface ReviewView {
  id: string;
  turnId: string;
  conversationId: string;
  creditorId: string;
  portfolioId: string | null;
  policyId: string | null;
  sampleReason: 'RANDOM' | 'GUARDRAIL' | 'FAILURE';
  status: 'PENDING' | 'REVIEWED';
  score: number | null;
  verdict: ReviewVerdict | null;
  tags: string[];
  comment: string | null;
  reviewerId: string | null;
  reviewedAt: string | null;
  createdAt: string;
}

export interface ReviewRequest {
  score: number;
  verdict: ReviewVerdict;
  tags: string[];
  comment: string | null;
}

/** `AssistantTrailUseCases.TurnView` */
export interface TurnView {
  id: string;
  conversationId: string;
  policyId: string | null;
  policyVersion: number | null;
  promptCode: string | null;
  promptVersion: number | null;
  mode: AssistantMode;
  episode: number;
  turnNumber: number;
  outcome: string;
  handoffReason: string | null;
  triggerSource: string | null;
  guardrails: string[];
  intent: string | null;
  confidence: number | null;
  provider: string | null;
  model: string | null;
  modelCalls: number;
  toolCalls: number;
  inputTokens: number;
  outputTokens: number;
  cost: number | null;
  costCurrency: string | null;
  modelLatencyMs: number;
  totalLatencyMs: number;
  outboundMessageId: string | null;
  draftId: string | null;
  proposalId: string | null;
  agreementId: string | null;
  errorCode: string | null;
  contextSha256: string | null;
  contextVersion: number;
  startedAt: string;
  finishedAt: string | null;
  contentPurged: boolean;
}

export interface ToolCallView {
  sequence: number;
  tool: string;
  arguments: string | null;
  result: string | null;
  outcome: 'OK' | 'REJECTED' | 'ERROR';
  errorCode: string | null;
  referenceType: string | null;
  referenceId: string | null;
  durationMs: number;
}

/** `AssistantTrailUseCases.TurnDetail` (conteúdo nulo após retenção/anonimização). */
export interface TurnDetail {
  turn: TurnView;
  inputText: string | null;
  context: string | null;
  outputText: string | null;
  toolCalls: ToolCallView[];
  pixRequests?: PixRequestView[];
}

/** `AssistantTrailUseCases.CriterionView` — critério do Pix avaliado no servidor. */
export interface PixCriterionView {
  criterion: string;
  passed: boolean;
  detail: string | null;
}

/** `AssistantTrailUseCases.PixRequestView` — pedido de Pix na conversa. */
export interface PixRequestView {
  id: string;
  decision: 'ISSUED' | 'DENIED' | 'SUGGESTED';
  denialCode: string | null;
  criteria: PixCriterionView[];
  agreementId: string | null;
  installmentNumber: number | null;
  amount: number | null;
  instructionId: string | null;
  reusedInstruction: boolean;
  expiresAt: string | null;
  deliveryStatus: 'AWAITING' | 'DELIVERED' | 'CANCELLED' | 'NOT_DELIVERED' | null;
  deliveryCode: string | null;
  deliveredAt: string | null;
  paidAt: string | null;
  requestedAt: string;
}

/** `AssistantReviewUseCases.PixQuality` */
export interface PixQuality {
  issued: number;
  delivered: number;
  denied: number;
  deniedByReason: Record<string, number>;
  suggested: number;
  paid: number;
  paidWithin24h: number;
  conversionRate: number | null;
  paidWithin24hRate: number | null;
}

/** `AssistantReviewUseCases.QualityView` */
export interface QualityView {
  from: string;
  to: string;
  turns: number;
  conversations: number;
  outcomes: Record<string, number>;
  handoffsByReason: Record<string, number>;
  handoffRate: number | null;
  guardrails: Record<string, number>;
  agreementsByOrigin: Record<string, number>;
  agreementsByGroup: Record<string, number>;
  earlyBreakRate: Record<string, number>;
  earlyBreakDays: number;
  complaints: number;
  cost: number | null;
  costCurrency: string | null;
  costPerAgreement: number | null;
  inputTokens: number;
  outputTokens: number;
  avgLatencyMs: number;
  reviewed: number;
  avgScore: number | null;
  outOfPolicy: number;
  pendingReviews: number;
  pix?: PixQuality | null;
}
