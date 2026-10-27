/** Contratos do módulo telephony (`telephony.application.port.in` e records de `telephony.web`). */

export type PresenceStatus = 'OFFLINE' | 'AVAILABLE' | 'ON_CALL' | 'WRAP_UP' | 'PAUSED';
export type PauseReason = 'BREAK' | 'LUNCH' | 'RESTROOM' | 'TRAINING' | 'FEEDBACK' | 'MEETING' | 'BACKOFFICE' | 'TECHNICAL_ISSUE' | 'OTHER';

/** `AgentUseCases.AgentView` */
export interface AgentView {
  id: string;
  userId: string;
  extension: string;
  active: boolean;
  status: PresenceStatus;
  pauseReason: PauseReason | null;
  campaignId: string | null;
  currentCallId: string | null;
  statusSince: string | null;
  lastAvailableAt: string | null;
  lastHeartbeatAt: string | null;
  loggedInAt: string | null;
  version: number;
}

export type CallStatus = 'QUEUED' | 'DIALING' | 'RINGING' | 'ANSWERED' | 'COMPLETED' | 'NO_ANSWER' | 'BUSY' | 'FAILED' | 'CANCELLED';
export type CallResult = 'ANSWERED' | 'NO_ANSWER' | 'BUSY' | 'VOICEMAIL' | 'FAILED' | 'CANCELLED' | 'ABANDONED';
export type InteractionState = 'NONE' | 'AWAITING_TABULATION' | 'PENDING' | 'RECORDED' | 'SKIPPED';
export type RecordingPurpose = 'QUALITY_MONITORING' | 'DISPUTE' | 'DEBTOR_REQUEST' | 'LEGAL' | 'AUDIT';

/** `CallUseCases.RecordingInfo` */
export interface RecordingInfo {
  id: string;
  status: 'PENDING' | 'STORED' | 'FAILED' | 'PURGED';
  durationSeconds: number | null;
  announced: boolean;
  storedAt: string | null;
  purgedAt: string | null;
  purgeReason: string | null;
}

/** `CallUseCases.CallView` (número sempre mascarado). */
export interface CallView {
  id: string;
  direction: 'OUTBOUND' | 'INBOUND';
  origin: 'CLICK_TO_CALL' | 'DIALER' | 'INBOUND';
  status: CallStatus;
  result: CallResult | null;
  creditorId: string | null;
  portfolioId: string | null;
  debtorId: string | null;
  caseId: string | null;
  contactId: string | null;
  operatorId: string | null;
  campaignId: string | null;
  numberMasked: string | null;
  attemptNumber: number;
  queuedAt: string | null;
  dialStartedAt: string | null;
  ringingAt: string | null;
  answeredAt: string | null;
  connectedAt: string | null;
  endedAt: string | null;
  tabulatedAt: string | null;
  ringSeconds: number | null;
  talkSeconds: number | null;
  wrapUpSeconds: number | null;
  amdResult: 'HUMAN' | 'MACHINE' | 'UNKNOWN' | null;
  abandoned: boolean;
  hangupSide: 'DEBTOR' | 'OPERATOR' | 'SYSTEM' | 'PROVIDER' | null;
  failureCode: string | null;
  /** Motivos do porteiro de contato quando negada. */
  denialReasons: string[];
  nextAllowedAt: string | null;
  recordingAnnounced: boolean;
  tabulationCode: string | null;
  suggestedTabulation: string | null;
  interactionState: InteractionState;
  interactionId: string | null;
  recording: RecordingInfo | null;
  version: number;
  /** Conta (plataforma) que discou ou recebeu. */
  accountId: string;
  provider: TelephonyProvider;
  /** Conta principal que falhou quando a chamada foi para a contingência. */
  fallbackFromAccountId: string | null;
}

export interface CallFilter {
  caseId?: string | null;
  debtorId?: string | null;
  creditorId?: string | null;
  /** `me` = o próprio operador. */
  operator?: string | null;
  campaignId?: string | null;
  status?: string | null;
  result?: string | null;
  direction?: string | null;
  from?: string | null;
  to?: string | null;
}

export interface TabulationRequest {
  tabulationCode: string;
  note: string | null;
  promise: { amount: number; dueDate: string } | null;
  callback: { dueAt: string; assigneeUserId: string | null; note: string | null } | null;
  /** Chamada recebida ainda não identificada. */
  caseId: string | null;
}

// --------------------------------------------------------------------------- campanhas de discagem

export type DialerMode = 'PROGRESSIVE' | 'PREDICTIVE';
export type DialerCampaignStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'FINISHED';
export type DialerEntryStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'EXHAUSTED' | 'EXCLUDED';

/** `DialerCampaignController.SettingsRequest` / `CampaignSettings` */
export interface CampaignSettings {
  name: string;
  portfolioId: string | null;
  includeAssigned: boolean;
  mode: DialerMode;
  maxDialRatio: number | null;
  maxAbandonRate: number | null;
  maxAttempts: number | null;
  retryIntervalMinutes: number | null;
  ringTimeoutSeconds: number | null;
  /** "HH:mm:ss" no fuso de operação. */
  windowStart: string | null;
  windowEnd: string | null;
}

export interface CreateCampaignRequest {
  code: string;
  creditorId: string | null;
  queueId: string;
  settings: CampaignSettings;
}

/** `DialerCampaignUseCases.CampaignView` */
export interface CampaignView {
  id: string;
  code: string;
  name: string;
  creditorId: string | null;
  portfolioId: string | null;
  queueId: string;
  includeAssigned: boolean;
  mode: DialerMode;
  status: DialerCampaignStatus;
  maxDialRatio: number;
  maxAbandonRate: number;
  maxAttempts: number;
  retryIntervalMinutes: number;
  ringTimeoutSeconds: number;
  windowStart: string | null;
  windowEnd: string | null;
  activatedAt: string | null;
  pausedAt: string | null;
  finishedAt: string | null;
  lastLoadedAt: string | null;
  entriesLoaded: number;
  version: number;
}

/** `DialerCampaignUseCases.EntryView` */
export interface EntryView {
  id: string;
  caseId: string;
  debtorId: string;
  creditorId: string;
  portfolioId: string | null;
  status: DialerEntryStatus;
  priority: number;
  attempts: number;
  nextAttemptAt: string | null;
  lastCallId: string | null;
  lastResult: string | null;
  lastAttemptAt: string | null;
  lastDenial: string[];
  excludedReason: string | null;
  finishedAt: string | null;
}

/** `DialerCampaignUseCases.CampaignStats` (dia de operação). */
export interface CampaignStats {
  campaignId: string;
  since: string;
  dialed: number;
  answered: number;
  live: number;
  connected: number;
  abandoned: number;
  machine: number;
  /** Atendidas ÷ discadas (%). */
  asr: number | null;
  /** Abandonadas ÷ atendidas por pessoa (%). */
  abandonRate: number | null;
  maxAbandonRate: number;
  averageTalkSeconds: number | null;
  averageRingSeconds: number | null;
  averageWrapUpSeconds: number | null;
  availableAgents: number;
  callsInProgress: number;
  currentRatio: number | null;
  pacingReason: string | null;
  pending: number;
  inProgress: number;
  completed: number;
  exhausted: number;
  excluded: number;
}

// ----------------------------------------------------------------- multiprovedor

export type TelephonyProvider = 'SANDBOX' | 'ALVARIA' | 'TARIFANDO' | 'TWILIO' | 'PLIVO';
export type ProviderReadiness = 'READY' | 'DISABLED' | 'CONTRACT_PENDING' | 'NOT_IMPLEMENTED';

/** Endereço a cadastrar no console do provedor (ex.: URL de voz do número). */
export interface ConsoleEndpointView {
  purpose: string;
  label: string;
  method: string;
  url: string;
}

/** `TelephonyAccountUseCases.ConnectionTestView` */
export interface ConnectionTestView {
  accountId: string;
  supported: boolean;
  ok: boolean;
  /** `OK`, `UNSUPPORTED`, `UNREACHABLE` ou código do provedor. */
  code: string;
  testedAt: string;
}

/** `SoftphoneUseCases.SoftphoneSession`: token curto do SDK de voz do navegador do próprio operador. */
export interface SoftphoneSession {
  accountId: string;
  provider: TelephonyProvider;
  identity: string;
  token: string;
  expiresAt: string;
  options: Record<string, string>;
}
export type RouteScope = 'CAMPAIGN' | 'PORTFOLIO' | 'CREDITOR' | 'DEFAULT';

/** `TelephonyAccountUseCases.AccountView` (credenciais nunca vêm; só os nomes das chaves). */
export interface TelephonyAccountView {
  id: string;
  provider: TelephonyProvider;
  creditorId: string | null;
  name: string;
  callerId: string;
  recordingEnabled: boolean;
  recordingNotice: boolean;
  credentialKeys: string[];
  credentialsUpdatedAt: string | null;
  webhookPath: string;
  active: boolean;
  createdAt: string | null;
  version: number;
  providerLabel: string;
  readiness: ProviderReadiness;
  /** O provedor disca neste ambiente (pronto, ou contrato pendente em homologação). */
  dialable: boolean;
  ratePerMinute: number | null;
  /** Endereço público completo do webhook da conta. */
  webhookUrl: string;
  consoleEndpoints: ConsoleEndpointView[];
}

export interface CreateTelephonyAccountRequest {
  provider: TelephonyProvider;
  creditorId: string | null;
  name: string;
  callerId: string;
  recordingEnabled: boolean;
  recordingNotice: boolean;
  credentials: Record<string, string>;
  ratePerMinute: number | null;
}

export interface UpdateTelephonyAccountRequest {
  name: string;
  callerId: string;
  recordingEnabled: boolean;
  recordingNotice: boolean;
  ratePerMinute: number | null;
}

/** `TelephonyAccountUseCases.ProviderView` */
export interface TelephonyProviderView {
  provider: TelephonyProvider;
  label: string;
  requiredCredentials: string[];
  readiness: ProviderReadiness;
  dialable: boolean;
  /** Aceita conta (falso = integração indisponível ou desligada neste ambiente). */
  available: boolean;
  /** Credenciais aceitas que podem ficar em branco. */
  optionalCredentials: string[];
  /** Oferece teste de conexão. */
  verifiable: boolean;
}

/** `TelephonyAccountUseCases.AccountComparison` (taxas de 0 a 1). */
export interface AccountComparison {
  accountId: string;
  provider: TelephonyProvider;
  name: string;
  active: boolean;
  dialed: number;
  rejected: number;
  answered: number;
  connected: number;
  failed: number;
  fallbackIn: number;
  asr: number | null;
  connectRate: number | null;
  failureRate: number | null;
  averageRingSeconds: number | null;
  averageTalkSeconds: number | null;
  billableMinutes: number;
  ratePerMinute: number | null;
  estimatedCost: number | null;
}

/** `TelephonyRoutingUseCases.RouteView` */
export interface RouteView {
  id: string;
  scopeType: RouteScope;
  scopeId: string | null;
  creditorId: string | null;
  primaryAccountId: string;
  fallbackAccountId: string | null;
  version: number;
}

export interface SaveRouteRequest {
  scopeType: RouteScope;
  scopeId: string | null;
  primaryAccountId: string;
  fallbackAccountId: string | null;
}

/** `AgentUseCases.EndpointView`: ramal do operador numa conta; inativo = fora desta plataforma. */
export interface EndpointView {
  id: string;
  agentId: string;
  userId: string;
  accountId: string;
  extension: string | null;
  active: boolean;
  version: number;
}
