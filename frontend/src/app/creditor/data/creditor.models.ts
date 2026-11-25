/** Contratos da API do portal do credor (`/api/creditor/v1`, ADR 0011). */

export type CreditorLoginStatus = 'AUTHENTICATED' | 'MFA_REQUIRED' | 'PASSWORD_CHANGE_REQUIRED';

export interface CreditorMe {
  userId: string;
  name: string;
  email: string;
  role: string;
  roleLabel: string;
  permissions: string[];
  creditorId: string;
  creditorName: string;
  tenantSlug: string;
  tenantName: string;
  lastLoginAt: string | null;
}

export interface CreditorLoginResponse {
  status: CreditorLoginStatus;
  accessToken: string | null;
  accessTokenExpiresAt: string | null;
  stepToken: string | null;
  me: CreditorMe | null;
}

export interface CreditorActivationStart {
  name: string;
  account: string;
  creditorName: string;
  tenantName: string;
  secret: string;
  otpAuthUri: string;
}

export interface CreditorSite {
  slug: string;
  name: string;
}

/** Permissões do portal (espelho de `Permission.CREDITOR_ONLY`). */
export const CreditorPermission = {
  OVERVIEW: 'CREDITOR_PORTAL_OVERVIEW',
  STATEMENT_RESPOND: 'CREDITOR_STATEMENT_RESPOND',
  DISPUTE_RESPOND: 'CREDITOR_DISPUTE_RESPOND',
  PAYMENT_REPORT: 'CREDITOR_PAYMENT_REPORT',
  ACTIVITY_VIEW: 'CREDITOR_ACTIVITY_VIEW',
} as const;

/** Situação do demonstrativo visível ao credor (só os já enviados). */
export type CreditorStatementStatus = 'SENT' | 'ACKNOWLEDGED' | 'CONTESTED';
/** Quem paga quem: a assessoria repassa ao credor, o credor paga à assessoria, ou nada a pagar. */
export type TransferDirection = 'AGENCY_TO_CREDITOR' | 'CREDITOR_TO_AGENCY' | 'NONE';

export interface CreditorStatementItem {
  id: string;
  sequenceNumber: number;
  periodStart: string;
  periodEnd: string;
  status: CreditorStatementStatus;
  recovered: number;
  netTransfer: number;
  direction: TransferDirection;
  sentAt: string | null;
  respondedAt: string | null;
}

export interface CreditorStatementTotals {
  receivedByAgency: number;
  receivedByCreditor: number;
  reversed: number;
  recovered: number;
  agencyHonorarium: number;
  commission: number;
  fixedFees: number;
  expenses: number;
  adjustments: number;
  netTransfer: number;
}

export interface CreditorStatementFile {
  id: string;
  format: 'CSV' | 'XLSX' | 'PDF';
  fileName: string;
  size: number;
  sha256: string;
  createdAt: string;
}

export interface CreditorStatementDetail {
  statement: CreditorStatementItem;
  totals: CreditorStatementTotals;
  files: CreditorStatementFile[];
  transferred: number;
  outstanding: number;
  responseNote: string | null;
  /** A resposta foi dada pela própria pessoa do credor no portal (e não registrada pela assessoria). */
  respondedInPortal: boolean;
}

export interface CreditorOpenPeriod {
  periodStart: string;
  asOf: string;
  expectedPeriodEnd: string | null;
  recovered: number;
  netTransfer: number;
  direction: TransferDirection;
  calculatedAt: string;
}

/** Contestações no portal do credor (fatia 5): só as em que a assessoria pediu a posição do credor. */
export type CreditorDisputeSituation = 'AWAITING_YOU' | 'ANSWERED' | 'CLOSED';
export type CreditorDisputeOutcome = 'UPHELD' | 'REJECTED' | 'PARTIALLY_UPHELD' | 'WITHDRAWN';
export type CreditorDisputePosition = 'CONFIRMS_DEBT' | 'ACCEPTS_CLAIM' | 'PARTIAL' | 'INFORMATION';
/** Quem levou a resposta: a pessoa do credor no portal, a integração do credor ou a assessoria (de outro canal). */
export type CreditorDisputeResponseOrigin = 'CREDITOR_PORTAL' | 'INTEGRATION' | 'AGENCY';

export interface CreditorDisputeItem {
  id: string;
  protocol: string;
  type: string;
  situation: CreditorDisputeSituation;
  outcome: CreditorDisputeOutcome | null;
  openedAt: string;
  disputedAmount: number;
  titleCount: number;
  requestedDueDate: string | null;
  overdue: boolean;
  lastResponseAt: string | null;
  closedAt: string | null;
}

export interface CreditorDisputeTitle {
  externalId: string;
  installmentNumber: number | null;
  dueDate: string | null;
  openBalance: number | null;
}

export interface CreditorDisputeDocument {
  id: string;
  fileName: string;
  size: number;
  sha256: string;
}

export interface CreditorDisputeRequest {
  id: string;
  sequenceNumber: number;
  sentAt: string | null;
  requestedDueDate: string;
  awaiting: boolean;
  overdue: boolean;
  subject: string;
  body: string;
  documents: CreditorDisputeDocument[];
}

export interface CreditorDisputeResponse {
  id: string;
  requestId: string | null;
  position: CreditorDisputePosition;
  origin: CreditorDisputeResponseOrigin;
  /** Só o resumo escrito no portal; o que a assessoria registrou de outro canal fica com ela. */
  note: string | null;
  receivedAt: string;
  documents: CreditorDisputeDocument[];
  documentsInVerification: number;
  documentsRejected: number;
}

export interface CreditorDisputeDetail {
  dispute: CreditorDisputeItem;
  titles: CreditorDisputeTitle[];
  requests: CreditorDisputeRequest[];
  responses: CreditorDisputeResponse[];
}

export const CREDITOR_DISPUTE_TYPE_LABELS: Record<string, string> = {
  NOT_RECOGNIZED: 'Dívida não reconhecida',
  ALREADY_PAID: 'Alegação de pagamento',
  WRONG_AMOUNT: 'Valor incorreto',
  FRAUD: 'Suspeita de fraude',
  PRESCRIBED: 'Alegação de prescrição',
  PRODUCT_OR_SERVICE: 'Produto ou serviço',
  OTHER: 'Outro motivo',
};

export const CREDITOR_DISPUTE_POSITION_LABELS: Record<CreditorDisputePosition, string> = {
  CONFIRMS_DEBT: 'Confirmo a dívida',
  ACCEPTS_CLAIM: 'O devedor tem razão',
  PARTIAL: 'O devedor tem razão em parte',
  INFORMATION: 'Só envio informação ou documentos',
};

export const CREDITOR_DISPUTE_OUTCOME_LABELS: Record<CreditorDisputeOutcome, string> = {
  UPHELD: 'Encerrada a favor do devedor',
  REJECTED: 'Encerrada: a cobrança segue',
  PARTIALLY_UPHELD: 'Encerrada: devedor com razão em parte',
  WITHDRAWN: 'Encerrada: o devedor desistiu',
};

/** Pagamento direto informado pela pessoa do credor (fatia 6): sempre em análise pela assessoria. */
export type CreditorPaymentStatus = 'IN_REVIEW' | 'CONFIRMED' | 'REJECTED' | 'REVERSED';
export type CreditorPaymentMethod = 'PIX' | 'BOLETO' | 'CARD' | 'TRANSFER' | 'CASH' | 'OTHER';
export type CreditorRejectionReason = 'PROOF_INVALID' | 'AMOUNT_OR_DATE_MISMATCH' | 'WRONG_TITLES' | 'DUPLICATE' | 'OTHER';

export interface CreditorTitleMatch {
  number: string;
  installmentNumber: number | null;
  dueDate: string | null;
  openBalance: number;
  /** O título está num acordo: o valor vai para as parcelas em aberto do acordo. */
  underAgreement: boolean;
}

export interface CreditorReportedPayment {
  id: string;
  status: CreditorPaymentStatus;
  amount: number;
  paidOn: string;
  method: CreditorPaymentMethod;
  titleNumbers: string[];
  reference: string | null;
  informedAt: string;
  decidedAt: string | null;
  rejectionReason: CreditorRejectionReason | null;
}

export interface CreditorPaymentReportRequest {
  titleNumbers: string[];
  amount: number;
  paidOn: string;
  method: CreditorPaymentMethod;
  reference: string | null;
  note: string | null;
  code: string;
}

export const CREDITOR_PAYMENT_METHOD_LABELS: Record<CreditorPaymentMethod, string> = {
  PIX: 'Pix',
  BOLETO: 'Boleto',
  CARD: 'Cartão',
  TRANSFER: 'Transferência',
  CASH: 'Dinheiro',
  OTHER: 'Outro',
};

export const CREDITOR_REJECTION_REASON_LABELS: Record<CreditorRejectionReason, string> = {
  PROOF_INVALID: 'O comprovante não permite confirmar o pagamento (ilegível, incompleto ou de outra operação).',
  AMOUNT_OR_DATE_MISMATCH: 'O valor ou a data do comprovante não conferem com o informado.',
  WRONG_TITLES: 'Os títulos informados não correspondem ao pagamento.',
  DUPLICATE: 'Este pagamento já estava registrado.',
  OTHER: 'A assessoria não pôde confirmar o pagamento. Fale com a assessoria para os detalhes.',
};
