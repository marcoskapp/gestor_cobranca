/**
 * Contratos do módulo payment (`payment.application.port.in` e records dos controllers em
 * `payment.web`). Valores monetários chegam como número (BigDecimal serializado) e saem como
 * número com duas casas.
 */

export type Amount = number;

// --------------------------------------------------------------------------- instruções (F10)

export type InstructionType = 'PIX_IMMEDIATE' | 'PIX_DUE' | 'BOLETO' | 'LINK';
export type InstructionStatus = 'PENDING' | 'ACTIVE' | 'PAID' | 'EXPIRED' | 'CANCELLED' | 'FAILED';

/** `PaymentInstructionController.CreateRequest` */
export interface CreateInstructionRequest {
  type: InstructionType;
  agreementId: string | null;
  installmentId: string | null;
  titleId: string | null;
  /** Nulo = saldo em aberto da parcela/título (nunca acima dele). */
  amount: Amount | null;
  /** Pix com vencimento; nulo = vencimento da parcela. */
  dueDate: string | null;
  /** Validade do Pix imediato (300 s a 30 dias); nulo = parâmetro da assessoria. */
  expiresInSeconds: number | null;
  /** Canal pelo qual será enviada (informativo, para métricas). */
  channel: string | null;
}

export interface InstructionFilter {
  creditorId: string | null;
  debtorId: string | null;
  agreementId: string | null;
  titleId: string | null;
  status: string | null;
}

/** `PaymentInstructionUseCases.InstructionView` */
export interface InstructionView {
  id: string;
  type: InstructionType;
  status: InstructionStatus;
  creditorId: string;
  portfolioId: string | null;
  debtorId: string;
  caseId: string | null;
  agreementId: string | null;
  installmentId: string | null;
  installmentNumber: number | null;
  titleId: string | null;
  amount: Amount;
  dueDate: string | null;
  expiresAt: string | null;
  txid: string | null;
  /** BR Code ("copia e cola"); também é o conteúdo do QR Code. */
  pixCopyPaste: string | null;
  location: string | null;
  failureCode: string | null;
  paymentId: string | null;
  paidAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  channel: string | null;
  createdAt: string;
  version: number;
}

// --------------------------------------------------------------------------- pagamentos (F14)

export type PaymentSource = 'PROVIDER' | 'EXTERNAL';
export type PaymentStatus = 'INFORMED' | 'CONFIRMED' | 'RECONCILED' | 'REJECTED' | 'REVERSED';
export type PaymentMethod = 'PIX' | 'BOLETO' | 'CARD' | 'TRANSFER' | 'CASH' | 'OTHER';
export type ExternalOrigin = 'AGENCY_ACCOUNT' | 'CREDITOR_DIRECT' | 'CREDITOR_REPORT' | 'OTHER';
export type ReviewMode = 'PROVIDER' | 'AUTO_LOW_RISK' | 'DUAL_CONTROL' | 'STATEMENT';
export type ReconciliationMode = 'PROVIDER_API' | 'STATEMENT' | 'MANUAL';
export type ReversalReason = 'PROVIDER_REFUND' | 'CHARGEBACK' | 'BANK_RETURN' | 'REGISTRATION_ERROR' | 'FRAUD' | 'OTHER';
export type TargetKind = 'AGREEMENT' | 'TITLES' | 'REFUND';
export type TitleStrategy = 'OLDEST_FIRST' | 'PROPORTIONAL';
export type AllocationOutcome = 'PENDING' | 'APPLIED' | 'PARTIAL' | 'UNAPPLIED' | 'NOT_APPLICABLE';

/** `PaymentController.TargetRequest` */
export interface TargetRequest {
  kind: 'AGREEMENT' | 'TITLES';
  agreementId: string | null;
  /** Parcelas (AGREEMENT) ou títulos (TITLES) em ordem de preferência; vazio = todas as parcelas em aberto. */
  itemIds: string[];
  /** Títulos: OLDEST_FIRST (padrão) ou PROPORTIONAL. */
  strategy: TitleStrategy | null;
}

/** `PaymentController.ExternalRequest` (parte `payment` do multipart). */
export interface ExternalPaymentRequest {
  creditorId: string;
  debtorId: string;
  amount: Amount;
  paidAt: string;
  method: PaymentMethod;
  origin: ExternalOrigin;
  externalReference: string | null;
  note: string | null;
  target: TargetRequest;
}

export interface ReverseRequest {
  reason: ReversalReason;
  note: string;
}

/** `PaymentController.ReallocateRequest`: novo destino **ou** referência da devolução. */
export interface ReallocateRequest {
  sourceAllocationId: string | null;
  amount: Amount;
  target: TargetRequest | null;
  refundReference: string | null;
  note: string;
}

export interface PaymentFilter {
  creditorId: string | null;
  debtorId: string | null;
  status: string | null;
  source: string | null;
  method: string | null;
  paidFrom: string | null;
  paidTo: string | null;
  withBalance: boolean | null;
}

export interface LineView {
  installmentId: string | null;
  titleId: string | null;
  amount: Amount;
}

/** `PaymentUseCases.AllocationView`: `id` é o `paymentId` publicado em `PaymentConfirmed`. */
export interface AllocationView {
  id: string;
  kind: TargetKind;
  origin: 'INITIAL' | 'REALLOCATION';
  status: 'ACTIVE' | 'REVERSED';
  agreementId: string | null;
  portfolioId: string | null;
  amount: Amount;
  releasedAmount: Amount;
  outcome: AllocationOutcome;
  appliedAmount: Amount | null;
  unappliedAmount: Amount | null;
  availableForReallocation: Amount;
  sourceAllocationId: string | null;
  refundReference: string | null;
  lines: LineView[];
  createdAt: string;
  reversedAt: string | null;
}

export interface ReversalView {
  id: string;
  amount: Amount;
  reason: ReversalReason;
  source: 'PROVIDER' | 'MANUAL';
  note: string | null;
  reversedAt: string;
  reversedBy: string | null;
}

/**
 * `PaymentUseCases.RequestedTargetView`: destino informado no registro do pagamento externo, para
 * quem aprova ver para onde o valor vai antes de a confirmação gerar as alocações.
 */
export interface RequestedTargetView {
  kind: 'AGREEMENT' | 'TITLES';
  agreementId: string | null;
  /** Parcelas (acordo) ou títulos, em ordem de preferência; vazio no acordo = todas em aberto. */
  itemIds: string[];
  strategy: TitleStrategy | null;
}

/** `PaymentUseCases.PaymentView` */
export interface PaymentView {
  id: string;
  source: PaymentSource;
  method: PaymentMethod;
  status: PaymentStatus;
  creditorId: string;
  debtorId: string;
  amount: Amount;
  paidAt: string;
  instructionId: string | null;
  providerTransactionId: string | null;
  externalReference: string | null;
  externalOrigin: ExternalOrigin | null;
  note: string | null;
  informedBy: string | null;
  informedAt: string | null;
  /** Externo acima do limite: aprovação por segunda pessoa. */
  reviewThresholdExceeded: boolean;
  reviewMode: ReviewMode | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  confirmedAt: string | null;
  reconciledAt: string | null;
  reconciliationMode: ReconciliationMode | null;
  reversedAt: string | null;
  allocatedAmount: Amount;
  /** Parte sem destino (sobra). */
  unallocatedAmount: Amount;
  /** Sobra + não aplicado pelo negotiation ainda não realocado. */
  pendingReallocation: Amount;
  evidenceAvailable: boolean;
  evidenceSha256: string | null;
  allocations: AllocationView[];
  reversal: ReversalView | null;
  /** Destino solicitado do pagamento externo (nulo nos integrados). */
  requestedTarget: RequestedTargetView | null;
  version: number;
}

// --------------------------------------------------------------------------- conciliação (F15)

export type StatementFormat = 'CSV_GENERIC' | 'CNAB_240' | 'CNAB_400';
export type StatementImportStatus = 'RECEIVED' | 'PROCESSING' | 'PROCESSED' | 'FAILED';
export type StatementLineStatus = 'RECONCILED' | 'CREATED_PAYMENT' | 'DIVERGENT' | 'ALREADY_RECONCILED' | 'IGNORED_DUPLICATE' | 'IGNORED_DEBIT';
export type DivergenceType = 'AMOUNT_MISMATCH' | 'DUPLICATE_PAYMENT' | 'UNMATCHED' | 'NOT_CONFIRMED' | 'PARTIAL_REFUND';
export type DivergenceStatus = 'OPEN' | 'RESOLVED' | 'DISMISSED';
export type DivergenceResolution = 'MATCHED_PAYMENT' | 'ACCEPTED' | 'DISMISSED';

export interface StatementImportView {
  id: string;
  format: StatementFormat;
  status: StatementImportStatus;
  creditorId: string | null;
  fileName: string | null;
  fileSize: number;
  fileSha256: string;
  requestedBy: string;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  totalLines: number;
  reconciledLines: number;
  createdPayments: number;
  divergentLines: number;
  ignoredLines: number;
  failureCode: string | null;
}

export interface StatementLineView {
  id: string;
  lineNumber: number;
  paidOn: string;
  amount: Amount;
  direction: 'CREDIT' | 'DEBIT' | string;
  transactionId: string | null;
  reference: string | null;
  /** Histórico do banco com documentos e contatos mascarados. */
  description: string | null;
  status: StatementLineStatus;
  paymentId: string | null;
}

export interface DivergenceFilter {
  status: string | null;
  type: string | null;
}

export interface ResolveRequest {
  resolution: DivergenceResolution;
  paymentId: string | null;
  note: string;
}

export interface DivergenceView {
  id: string;
  type: DivergenceType;
  source: 'STATEMENT' | 'WEBHOOK';
  status: DivergenceStatus;
  creditorId: string | null;
  paymentId: string | null;
  instructionId: string | null;
  statementLineId: string | null;
  providerReference: string | null;
  amount: Amount | null;
  expectedAmount: Amount | null;
  occurredOn: string | null;
  /** Descrição da linha do extrato (documentos e contatos mascarados). */
  description: string | null;
  resolution: DivergenceResolution | null;
  resolutionNote: string | null;
  resolvedPaymentId: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
  createdAt: string;
}

// --------------------------------------------------------------------------- contas Pix

export type PixProvider = 'BACEN_API' | 'SANDBOX';
export type WebhookAuth = 'MTLS' | 'HMAC';

/**
 * Segredos da conta. Vão só na criação e na rotação (substitui o conjunto inteiro); a API nunca os
 * devolve. `certificateBase64` nulo mantém o certificado atual.
 */
export interface CredentialsRequest {
  clientId: string | null;
  clientSecret: string | null;
  webhookSecret: string | null;
  certificateBase64: string | null;
  certificatePassword: string | null;
}

export interface CreatePixAccountRequest {
  creditorId: string | null;
  name: string;
  provider: PixProvider;
  pixKey: string;
  apiBaseUrl: string;
  tokenUrl: string;
  webhookAuth: WebhookAuth;
  webhookFingerprints: string[];
  credentials: CredentialsRequest;
}

export interface UpdatePixAccountRequest {
  name: string;
  pixKey: string;
  apiBaseUrl: string;
  tokenUrl: string;
  webhookAuth: WebhookAuth;
  webhookFingerprints: string[];
}

/** `PixAccountUseCases.AccountView`: sem segredos — só os nomes gravados e a impressão digital. */
export interface PixAccountView {
  id: string;
  creditorId: string | null;
  name: string;
  provider: PixProvider;
  pixKey: string;
  apiBaseUrl: string;
  tokenUrl: string;
  webhookAuth: WebhookAuth;
  webhookFingerprints: string[];
  /** URL a configurar no PSP (o PSP acrescenta `/pix`). */
  webhookUrl: string;
  credentialKeys: string[];
  certificateSha256: string | null;
  certificateExpiresAt: string | null;
  credentialsUpdatedAt: string | null;
  webhookRegisteredAt: string | null;
  active: boolean;
  version: number;
}

// --------------------------------------------------------------------------- parâmetros

export interface PaymentSettingsView {
  externalReviewThreshold: Amount;
  pixExpirationSeconds: number;
  pixDueValidityDays: number;
  reconciliationDateToleranceDays: number;
  /** Falso enquanto valem os padrões da plataforma. */
  customized: boolean;
  updatedAt: string | null;
}

export interface PaymentSettingsRequest {
  externalReviewThreshold: Amount;
  pixExpirationSeconds: number;
  pixDueValidityDays: number;
  reconciliationDateToleranceDays: number;
}
