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
  agreement: AgreementDetail;
  pix: PixView | null;
  pixError: string | null;
}

export type ServiceReason = 'TALK_TO_AGENT' | 'DISPUTE' | 'CANNOT_AFFORD' | 'OTHER';

export interface ServiceRequestBody {
  reason: ServiceReason;
  preferredChannel: PortalChannel;
  portfolioId?: string | null;
  note?: string | null;
}

export interface ServiceRequestView {
  id: string;
  protocol: string;
  reason: ServiceReason;
  preferredChannel: PortalChannel;
  status: 'OPEN' | 'FORWARDED' | 'UNDELIVERED';
  createdAt: string;
}
