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
  /** Prazo para aceitar ou contestar (data do envio + dias da assessoria; só lembrete, sem efeito automático). */
  responseDueOn: string | null;
  /** O prazo venceu e o demonstrativo segue sem resposta (continua "Aguardando sua resposta"). */
  responseOverdue: boolean;
  /** Do líquido, quanto já foi repassado (ou pago à assessoria) e quanto falta. */
  transferred: number;
  outstanding: number;
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
  /** Acumulado por dia do período em aberto (dia 1 = início do período). */
  daily: CreditorDailyPoint[];
  /** Acumulado por dia do período anterior, para comparar no mesmo dia. */
  previousDaily: CreditorDailyPoint[];
}

export interface CreditorDailyPoint {
  day: number;
  date: string;
  recovered: number;
  netTransfer: number;
}

// ------------------------------------------------------------------ Início (fatia 7): indicadores e pendências

/** Período do Início, numa lista fechada (o servidor recusa outro valor). */
export type CreditorPeriod = 'THIS_MONTH' | 'LAST_MONTH' | 'LAST_3_MONTHS' | 'THIS_YEAR';
/** Fonte do Início fora do ar nesta resposta: a seção dela vem nula. */
export type CreditorOverviewSource = 'INDICATORS' | 'TRANSFER';

export interface CreditorRecovery {
  recovered: number;
  /** Parte do recuperado paga diretamente a você (já dentro de `recovered`). */
  paidToCreditor: number;
  previousRecovered: number;
  previousPaidToCreditor: number;
}

export interface CreditorMonthlyRecovery {
  /** `AAAA-MM` */
  month: string;
  recovered: number;
  paidToCreditor: number;
}

export interface CreditorPortfolioShare {
  /** Nulos na linha "Outros". */
  portfolioId: string | null;
  name: string | null;
  recovered: number;
  /** Soma das demais carteiras e do recebido sem carteira. */
  others: boolean;
}

export interface CreditorBalance {
  amount: number;
  titles: number;
  referenceDate: string;
}

export interface CreditorAgreements {
  onTrack: number;
  active: number;
  /** Fração de 0 a 1; nula sem acordo em vigor. */
  ratio: number | null;
  /** Abaixo de 80%. */
  alert: boolean;
  referenceDate: string;
}

export interface CreditorTransferEstimate {
  periodStart: string;
  asOf: string;
  expectedPeriodEnd: string | null;
  netTransfer: number;
  direction: TransferDirection;
  calculatedAt: string;
}

/** `GET /api/creditor/v1/overview?period=` */
export interface CreditorOverview {
  period: CreditorPeriod;
  from: string | null;
  to: string | null;
  previousFrom: string | null;
  previousTo: string | null;
  /** Instante em que o servidor montou a resposta ("Atualizado às"). */
  generatedAt: string;
  /** "Dados até" dos indicadores (nulo: ainda sem atualização). */
  dataUntil: string | null;
  recovery: CreditorRecovery | null;
  /** Sempre os 6 últimos meses, o corrente incluído. */
  monthly: CreditorMonthlyRecovery[] | null;
  /** As 5 carteiras com mais recuperado e "Outros". */
  byPortfolio: CreditorPortfolioShare[] | null;
  balance: CreditorBalance | null;
  agreements: CreditorAgreements | null;
  transfer: CreditorTransferEstimate | null;
  unavailable: CreditorOverviewSource[];
}

export type CreditorAwaitingKind = 'DISPUTE' | 'STATEMENT' | 'PAYMENT_REJECTED';
export type CreditorAwaitingSource = 'DISPUTES' | 'STATEMENTS' | 'PAYMENTS';

export interface CreditorAwaitingItem {
  kind: CreditorAwaitingKind;
  id: string;
  /** Prazo (data, sem hora); nulo no pagamento recusado. */
  dueOn: string | null;
  overdue: boolean;
  dueSoon: boolean;
  /** A pessoa da sessão pode resolver (senão o item é do time). */
  youCanAct: boolean;
  dispute: { protocol: string; type: string } | null;
  statement: { sequenceNumber: number; periodStart: string; periodEnd: string } | null;
  payment: { paidOn: string; amount: number; rejectionReason: CreditorRejectionReason | null; decidedAt: string | null } | null;
}

/** `GET /api/creditor/v1/awaiting` (sem cache; mesmos filtros das listas de destino). */
export interface CreditorAwaiting {
  /** Todos os itens que dependem do credor (pode passar dos itens devolvidos). */
  total: number;
  dueSoon: number;
  overdue: number;
  counts: { disputes: number; statements: number; rejectedPayments: number };
  /** Até 50: vencidos primeiro, depois pelo prazo, depois os sem prazo. */
  items: CreditorAwaitingItem[];
  unavailable: CreditorAwaitingSource[];
}

// ------------------------------------------------------------------ Minha atividade (fatia 7)

export type CreditorActivityScope = 'own' | 'team';
export type CreditorActivityAction =
  | 'STATEMENT_ACKNOWLEDGED'
  | 'STATEMENT_CONTESTED'
  | 'DISPUTE_RESPONDED'
  | 'PAYMENT_REPORTED'
  | 'SIGNED_IN'
  | 'SIGNED_OUT'
  | 'PASSWORD_CHANGED'
  | 'MFA_ENROLLED'
  | 'FILE_DOWNLOADED';
export type CreditorActivityReference = 'STATEMENT' | 'DISPUTE' | 'PAYMENT' | 'STATEMENT_FILE' | 'DISPUTE_DOCUMENT';

export interface CreditorActivityItem {
  occurredAt: string;
  action: CreditorActivityAction;
  /** Feita pela própria pessoa da sessão. */
  mine: boolean;
  personName: string | null;
  referenceType: CreditorActivityReference | null;
  referenceId: string | null;
}

/** `GET /api/creditor/v1/activity?scope=own|team&before=&size=` */
export interface CreditorActivityPage {
  scope: 'OWN' | 'TEAM';
  /** Janela da consulta (90 dias). */
  windowDays: number;
  nextCursor: string | null;
  items: CreditorActivityItem[];
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
