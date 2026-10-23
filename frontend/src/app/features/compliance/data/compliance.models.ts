/** Contratos dos controllers do módulo compliance (`compliance.application.port.in`). */

export type ContactChannel = 'WHATSAPP' | 'EMAIL' | 'SMS' | 'PHONE' | 'PORTAL';
export type RestrictionType =
  | 'OPT_OUT'
  | 'DEBTOR_REQUEST'
  | 'LEGAL_BLOCK'
  | 'CREDITOR_REQUEST'
  | 'DECEASED'
  | 'FRAUD_SUSPECTED'
  | 'INVALID_CONTACT_HISTORY';
export type RestrictionSource = 'OPERATOR' | 'DEBTOR' | 'PROVIDER_STOP_KEYWORD' | 'CREDITOR' | 'LEGAL';
export type RestrictionStatus = 'ACTIVE' | 'REVOKED';

/** `ContactRestrictionUseCases.RestrictionView` (valor de contato sempre mascarado). */
export interface RestrictionView {
  id: string;
  debtorId: string | null;
  creditorId: string | null;
  channel: ContactChannel | null;
  type: RestrictionType;
  contactType: 'PHONE' | 'EMAIL' | null;
  contactMasked: string | null;
  reason: string;
  source: RestrictionSource;
  startsAt: string;
  endsAt: string | null;
  status: RestrictionStatus;
  effective: boolean;
  revokedAt: string | null;
  revokedBy: string | null;
  revokedReason: string | null;
  originRequestId: string | null;
  hasEvidence: boolean;
  evidenceFileName: string | null;
  evidenceSha256: string | null;
  createdAt: string;
  createdBy: string | null;
}

export interface RestrictionFilter {
  debtorId?: string | null;
  creditorId?: string | null;
  type?: string | null;
  status?: string | null;
  channel?: string | null;
}

export interface CreateRestrictionRequest {
  debtorId: string;
  creditorId: string | null;
  channel: ContactChannel | null;
  type: RestrictionType;
  contactType: 'PHONE' | 'EMAIL' | null;
  contactValue: string | null;
  reason: string;
  source: Exclude<RestrictionSource, 'PROVIDER_STOP_KEYWORD'>;
  startsAt: string | null;
  endsAt: string | null;
}

/** `ContactAttemptQueries.AttemptView` */
export interface AttemptView {
  id: string;
  debtorId: string;
  creditorId: string;
  channel: ContactChannel;
  attemptedAt: string;
  origin: 'OPERATOR' | 'CAMPAIGN' | 'SYSTEM';
  referenceType: string | null;
  referenceId: string | null;
  recordedBy: string | null;
}

export type WeekDay = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

/** `ContactPolicyUseCases.ChannelRuleInput` (horários `LocalTime` "HH:mm"). */
export interface ChannelRuleInput {
  channel: ContactChannel;
  windowStart: string | null;
  windowEnd: string | null;
  allowedDays: WeekDay[] | null;
  maxPerDay: number | null;
  maxPerWeek: number | null;
  minIntervalMinutes: number | null;
  blockOnHolidays: boolean;
}

export interface PublishPolicyRequest {
  creditorId: string | null;
  effectiveFrom: string | null;
  notes: string | null;
  rules: ChannelRuleInput[];
}

export interface ChannelRuleView {
  channel: ContactChannel;
  inheritsWindow: boolean;
  windowStart: string | null;
  windowEnd: string | null;
  allowedDays: WeekDay[] | null;
  maxPerDay: number | null;
  maxPerWeek: number | null;
  minIntervalMinutes: number | null;
  blockOnHolidays: boolean;
}

export interface PolicyView {
  id: string;
  creditorId: string | null;
  version: number;
  effectiveFrom: string;
  notes: string | null;
  rules: ChannelRuleView[];
  publishedAt: string;
  publishedBy: string | null;
}

export interface EffectiveChannelRule {
  channel: ContactChannel;
  ruleSource: 'CREDITOR' | 'TENANT' | 'NONE';
  windowSource: 'RULE' | 'TENANT_DEFAULT';
  windowStart: string | null;
  windowEnd: string | null;
  allowedDays: WeekDay[] | null;
  maxPerDay: number | null;
  maxPerWeek: number | null;
  minIntervalMinutes: number | null;
  blockOnHolidays: boolean;
}

export interface EffectivePolicyView {
  creditorId: string | null;
  tenantPolicyVersion: number | null;
  creditorPolicyVersion: number | null;
  channels: EffectiveChannelRule[];
}

export interface HolidayView {
  id: string;
  date: string;
  name: string;
}

export type LegalBasisCode =
  | 'EXECUCAO_CONTRATO'
  | 'EXERCICIO_REGULAR_DIREITOS'
  | 'LEGITIMO_INTERESSE'
  | 'PROTECAO_CREDITO'
  | 'CONSENTIMENTO'
  | 'OBRIGACAO_LEGAL';
export type ProcessingRole = 'OPERADOR' | 'CONTROLADOR' | 'CO_CONTROLADOR';

/** `LegalBasisUseCases.LegalBasisView` */
export interface LegalBasisView {
  id: string;
  creditorId: string;
  portfolioId: string | null;
  legalBasis: LegalBasisCode;
  processingRole: ProcessingRole;
  contractReference: string;
  validFrom: string;
  validUntil: string | null;
  legalResponsible: string;
  retentionMonths: number;
  notes: string | null;
  endReason: string | null;
  createdAt: string;
}

export interface RegisterLegalBasisRequest {
  creditorId: string;
  portfolioId: string | null;
  legalBasis: LegalBasisCode;
  processingRole: ProcessingRole | null;
  contractReference: string;
  validFrom: string;
  validUntil: string | null;
  legalResponsible: string;
  retentionMonths: number;
  notes: string | null;
}

export type RequestType =
  | 'CONFIRMATION'
  | 'ACCESS'
  | 'CORRECTION'
  | 'ANONYMIZATION'
  | 'PORTABILITY'
  | 'INFORMATION_SHARING'
  | 'OPPOSITION'
  | 'REVIEW_AUTOMATED_DECISION';
export type RequestChannel = 'EMAIL' | 'PHONE' | 'WHATSAPP' | 'PORTAL' | 'LETTER' | 'IN_PERSON' | 'CREDITOR_FORWARD';
export type RequestStatus = 'RECEIVED' | 'IDENTITY_VERIFIED' | 'IN_PROGRESS' | 'COMPLETED' | 'REJECTED';

/** `DataSubjectRequestUseCases.RequestView` (documento e contato do requerente mascarados). */
export interface DataSubjectRequestView {
  id: string;
  type: RequestType;
  channel: RequestChannel;
  status: RequestStatus;
  debtorId: string | null;
  requesterName: string;
  maskedDocument: string | null;
  maskedContact: string | null;
  details: string | null;
  receivedAt: string;
  dueAt: string;
  overdue: boolean;
  assignedTo: string | null;
  identityVerifiedAt: string | null;
  identityVerifiedBy: string | null;
  executedAt: string | null;
  completedAt: string | null;
  responseSummary: string | null;
  hasResultFile: boolean;
  resultSha256: string | null;
}

export interface DataSubjectRequestFilter {
  status?: string | null;
  type?: string | null;
  debtorId?: string | null;
  overdue?: boolean | null;
}

export interface OpenDataSubjectRequest {
  type: RequestType;
  channel: RequestChannel;
  requesterName: string;
  document: string | null;
  requesterContact: string | null;
  details: string | null;
  receivedAt: string | null;
}

export type RetentionCategory = 'DEBTOR_INACTIVE' | 'IMPORT_FILES' | 'MESSAGE_CONTENT' | 'CALL_RECORDINGS';

export interface RetentionPolicyView {
  category: RetentionCategory;
  retentionMonths: number;
  action: 'ANONYMIZE' | 'DELETE_FILE';
  enabled: boolean;
  executableHere: boolean;
  updatedAt: string | null;
}

export interface RetentionRunView {
  id: string;
  category: RetentionCategory;
  trigger: 'SCHEDULED' | 'MANUAL';
  dryRun: boolean;
  retentionMonths: number;
  cutoff: string;
  startedAt: string;
  finishedAt: string | null;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  candidateCount: number;
  eligibleCount: number;
  processedCount: number;
  retainedCount: number;
  failedCount: number;
  failureReason: string | null;
}

export interface RetentionRunItemView {
  debtorId: string;
  outcome: 'ELIGIBLE' | 'ANONYMIZED' | 'RETAINED' | 'FAILED';
  errorCode: string | null;
  detail: string | null;
  processedAt: string | null;
}
