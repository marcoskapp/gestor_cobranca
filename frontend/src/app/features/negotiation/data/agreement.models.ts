/** Contratos do módulo negotiation, parte 2: propostas, aprovação, aceite, acordos e parcelas. */

import { ActorRef, Amount, ComponentRates, OfferView, PlanRequest, PolicyRef } from './negotiation.models';

export type ProposalStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'ACCEPTED' | 'FORMALIZED' | 'EXPIRED' | 'HANDED_OFF';
export type AgreementStatus = 'FORMALIZED' | 'IN_PAYMENT' | 'SETTLED' | 'BROKEN' | 'CANCELLED' | 'RENEGOTIATED';
export type InstallmentStatus = 'OPEN' | 'PAID' | 'PARTIALLY_PAID' | 'OVERDUE' | 'CANCELLED';
export type AgreementOrigin =
  | 'HUMAN'
  | 'HUMAN_COPILOT'
  | 'ASSISTANT_HANDOFF_NEGOTIATED'
  | 'ASSISTANT_HANDOFF_CONFIRMED'
  | 'ASSISTANT_AUTONOMOUS'
  | 'PORTAL_SELF_SERVICE'
  | 'CAMPAIGN';

/** Canais aceitos na evidência de aceite (`AcceptanceEvidence.CHANNELS`). */
export type AcceptanceChannel = 'PHONE' | 'WHATSAPP' | 'EMAIL' | 'SMS' | 'CHAT' | 'PORTAL' | 'IN_PERSON';

/** `ProposalController.CreateProposalRequest` */
export interface CreateProposalRequest {
  simulationId: string;
  level: number;
  discounts: ComponentRates | null;
  plan: PlanRequest;
  replacedAgreementId: string | null;
  handoffProposalId: string | null;
  copilot: boolean | null;
  channel: string | null;
}

export interface AcceptRequest {
  channel: AcceptanceChannel;
  reference: string | null;
}

export interface DecisionView {
  decidedBy: string;
  profileCode: string | null;
  decidedAt: string;
  note: string | null;
}

export interface AcceptanceView {
  channel: string;
  acceptedAt: string;
  actorType: string;
  actorId: string | null;
  reference: string | null;
  termSha256: string | null;
  termSize: number | null;
}

/** `ProposalUseCases.ProposalView` */
export interface ProposalView {
  id: string;
  status: ProposalStatus;
  creditorId: string;
  debtorId: string;
  portfolioId: string;
  caseId: string | null;
  simulationId: string;
  policy: PolicyRef;
  referenceDate: string;
  actor: ActorRef;
  level: number;
  justificationReason: string | null;
  copilot: boolean;
  channel: string | null;
  titleIds: string[];
  discountRates: ComponentRates;
  installments: number;
  requestedDownPayment: Amount | null;
  firstDueDate: string | null;
  updatedAmount: Amount;
  discountAmount: Amount;
  negotiatedAmount: Amount;
  downPayment: Amount | null;
  financingInterest: Amount;
  totalAmount: Amount;
  envelopeUsage: number | null;
  validUntil: string | null;
  authorityViolations: string[];
  replacedAgreementId: string | null;
  renegotiationNumber: number;
  handoffProposalId: string | null;
  decision: DecisionView | null;
  acceptance: AcceptanceView | null;
  agreementId: string | null;
  formalizedAt: string | null;
  createdAt: string;
  offer: OfferView;
  version: number;
}

export interface ProposalFilter {
  creditorId: string | null;
  debtorId: string | null;
  /** Propostas de um caso de cobrança (aba Negociação da estação). */
  caseId?: string | null;
  status: string | null;
  actorType: string | null;
  /** Só as propostas registradas pelo próprio usuário. */
  mine?: boolean;
}

export interface ParticipantView {
  actorType: string;
  actorId: string | null;
  role: string;
}

export interface InstallmentView {
  id: string;
  number: number;
  dueDate: string;
  amount: Amount;
  paidAmount: Amount;
  status: InstallmentStatus;
  paidAt: string | null;
}

export interface BreakRuleView {
  overdueInstallments: number;
  toleranceDays: number;
}

/** `AgreementUseCases.AgreementView` */
export interface AgreementView {
  id: string;
  status: AgreementStatus;
  statusReason: string | null;
  proposalId: string;
  creditorId: string;
  debtorId: string;
  portfolioId: string;
  caseId: string | null;
  policyId: string;
  policyVersion: number;
  concessionLevel: number;
  envelopeUsage: number | null;
  channel: string | null;
  origin: AgreementOrigin;
  participants: ParticipantView[];
  updatedAmount: Amount;
  discountAmount: Amount;
  negotiatedAmount: Amount;
  financingInterest: Amount;
  totalAmount: Amount;
  downPayment: Amount | null;
  paidAmount: Amount;
  remainingAmount: Amount;
  titleIds: string[];
  installments: InstallmentView[];
  breakRule: BreakRuleView;
  formalizedAt: string;
  inPaymentAt: string | null;
  settledAt: string | null;
  endedAt: string | null;
  replacedAgreementId: string | null;
  replacedByAgreementId: string | null;
  renegotiationNumber: number;
  incrementalAmount: Amount | null;
  acceptance: AcceptanceView | null;
  termAvailable: boolean;
  version: number;
}

export interface AgreementFilter {
  creditorId: string | null;
  debtorId: string | null;
  caseId: string | null;
  status: string | null;
}

/** `AgreementUseCases.PaymentView` (pagamento aplicado ao acordo). */
export interface AgreementPaymentView {
  paymentId: string;
  amount: Amount;
  appliedAmount: Amount;
  unappliedAmount: Amount;
  paidAt: string;
  status: 'APPLIED' | 'UNAPPLIED' | 'REVERSED';
  ledgerStatus: string;
  reversedAt: string | null;
}
