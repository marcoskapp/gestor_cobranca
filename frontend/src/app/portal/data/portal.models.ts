/** Contratos da API do portal (`/api/portal/v1`). Valores monetários chegam como número (BigDecimal). */

export type PortalChannel = 'WHATSAPP' | 'SMS' | 'EMAIL';
export type KnowledgeChallenge = 'NONE' | 'BIRTH_DATE' | 'CONTRACT_SUFFIX';
export type Money = number | string;

export interface SiteView {
  slug: string;
  displayName: string;
  primaryColor: string;
  accentColor: string;
  hasLogo: boolean;
  logoVersion: string | null;
  otpChannels: PortalChannel[];
  knowledgeChallenge: KnowledgeChallenge;
  supportText: string | null;
  privacyNoticeVersion: number;
  privacyNotice: string;
}

export interface ChallengeView {
  challengeId: string;
  expiresAt: string;
  channel: PortalChannel;
  knowledgeChallenge: KnowledgeChallenge;
  codeRequired: boolean;
  codeLength: number;
}

export interface SessionGrant {
  accessToken: string;
  expiresAt: string;
  sessionId: string;
}

export interface LinkResult {
  session: SessionGrant | null;
  knowledgeStep: ChallengeView | null;
}

export interface SessionView {
  sessionId: string;
  slug: string;
  displayName: string;
  firstName: string | null;
  method: 'OTP' | 'LINK';
  expiresAt: string;
  idleTimeoutSeconds: number;
  privacyAcknowledged: boolean;
  privacyNoticeVersion: number;
  /** Devedor pessoa jurídica: `firstName` traz o nome fantasia (ou a razão social). */
  company: boolean;
}

/** Quem negocia pela empresa (devedor pessoa jurídica). A resposta não diz se o CPF conferiu. */
export interface RepresentativeStatus {
  required: boolean;
  declared: boolean;
  declaredName: string | null;
  /** A empresa não tem representante com poder para negociar: um atendente conclui o acordo. */
  humanCompletion: boolean;
}

/** CPF e nome de quem está negociando — nunca guardar no navegador. */
export interface DeclareRepresentativeRequest {
  cpf: string;
  name: string;
}

export interface TitleSummary {
  titleId: string;
  reference: string | null;
  dueDate: string;
  daysOverdue: number;
  openBalance: Money;
  status: string;
}

export type UnavailableReason = 'AGREEMENT_ACTIVE' | 'NOT_ELIGIBLE' | 'NO_POLICY';

export interface PortfolioDebt {
  portfolioId: string;
  portfolioName: string | null;
  openBalance: Money;
  updatedAmount: Money | null;
  negotiable: boolean;
  unavailableReason: UnavailableReason | null;
  activeAgreementId: string | null;
  titles: TitleSummary[];
}

export interface DebtsView {
  creditorName: string;
  portfolios: PortfolioDebt[];
}

export interface ScheduleItem {
  number: number;
  dueDate: string;
  amount: Money;
}

export interface OfferSummary {
  level: number;
  installments: number;
  downPayment: Money;
  installmentAmount: Money;
  total: Money;
  discount: Money;
  discountPercent: Money;
  financingInterest: Money;
  firstDueDate: string;
  schedule: ScheduleItem[];
}

export interface OffersView {
  simulationId: string;
  portfolioId: string;
  updatedAmount: Money;
  referenceDate: string;
  maxInstallments: number;
  maxDaysToFirstPayment: number;
  proposalValidityDays: number;
  breakOverdueInstallments: number | null;
  breakToleranceDays: number | null;
  offers: OfferSummary[];
}

export interface SimulateRequest {
  installments: number;
  downPayment?: number | null;
  firstDueDate?: string | null;
}

export interface AcceptRequest {
  simulationId: string;
  level: number;
  installments: number;
  downPayment?: number | null;
  firstDueDate?: string | null;
  termsAccepted: boolean;
  /** Empresa: por onde o atendente fala se precisar concluir o acordo. */
  preferredChannel?: PortalChannel | null;
}

export type InstallmentStatus = 'OPEN' | 'PAID' | 'PARTIALLY_PAID' | 'OVERDUE' | 'CANCELLED';

export interface InstallmentItem {
  id: string;
  number: number;
  dueDate: string;
  amount: Money;
  paidAmount: Money;
  status: InstallmentStatus;
  pixStatus: string | null;
}

export interface AgreementDetail {
  id: string;
  portfolioId: string;
  status: string;
  updatedAmount: Money;
  discountAmount: Money;
  totalAmount: Money;
  paidAmount: Money;
  remainingAmount: Money;
  breakOverdueInstallments: number | null;
  breakToleranceDays: number | null;
  formalizedAt: string;
  acceptedAt: string | null;
  termSha256: string | null;
  termAvailable: boolean;
  installments: InstallmentItem[];
}

export interface AgreementSummary {
  id: string;
  portfolioId: string;
  status: string;
  totalAmount: Money;
  paidAmount: Money;
  installments: number;
  nextDueDate: string | null;
  nextAmount: Money | null;
  formalizedAt: string;
  closedInPortal: boolean;
}

export type PixStatus = 'PENDING' | 'ACTIVE' | 'PAID' | 'EXPIRED' | 'CANCELLED' | 'FAILED';

export interface PixView {
  instructionId: string;
  agreementId: string;
  installmentId: string;
  installmentNumber: number;
  status: PixStatus;
  amount: Money;
  expiresAt: string | null;
  copyPaste: string | null;
  paidAt: string | null;
}

export interface AcceptanceResult {
  /** Nulo quando o aceite da empresa virou atendimento (`serviceRequest`). */
  agreement: AgreementDetail | null;
  pix: PixView | null;
  pixError: string | null;
  /** Empresa sem representante confirmado: um atendente conclui o acordo. */
  serviceRequest?: ServiceRequestView | null;
}

export type ServiceReason = 'TALK_TO_AGENT' | 'DISPUTE' | 'CANNOT_AFFORD' | 'OTHER';

/** Motivo da contestação (mesmos códigos do collection). */
export type PortalDisputeType = 'NOT_RECOGNIZED' | 'ALREADY_PAID' | 'WRONG_AMOUNT' | 'OTHER';

/** Registro da contestação no sistema da assessoria (o prazo de resposta conta do pedido). */
export type DisputeIntakeStatus = 'PENDING' | 'OPENED' | 'ALREADY_OPEN' | 'NO_CASE' | 'NO_TITLES';

export interface ServiceRequestBody {
  reason: ServiceReason;
  preferredChannel: PortalChannel;
  portfolioId?: string | null;
  note?: string | null;
  disputeType?: PortalDisputeType | null;
}

export interface ServiceRequestView {
  id: string;
  protocol: string;
  /** `COMPANY_ACCEPTANCE`: aceite da empresa encaminhado a um atendente (criado pelo portal, nunca escolhido). */
  reason: ServiceReason | 'COMPANY_ACCEPTANCE';
  preferredChannel: PortalChannel;
  status: 'OPEN' | 'FORWARDED' | 'UNDELIVERED';
  createdAt: string;
  disputeType?: PortalDisputeType | null;
  disputeIntakeStatus?: DisputeIntakeStatus | null;
}
