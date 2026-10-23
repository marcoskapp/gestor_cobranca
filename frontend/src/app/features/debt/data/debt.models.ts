/** Contratos dos controllers do módulo debt (`debt.application.port.in`). */

import { PageResponse } from '../../../shared/models/page';

/** Valores monetários (`BigDecimal`) chegam como número JSON. */
export type Amount = number;

export type PersonType = 'INDIVIDUAL' | 'COMPANY';
export type DebtorStatus = 'ACTIVE' | 'ANONYMIZED';

/** `DebtorUseCases.DebtorView` (documento sempre mascarado; nulo após anonimização). */
export interface DebtorView {
  id: string;
  personType: PersonType;
  name: string;
  maskedDocument: string | null;
  birthDate: string | null;
  status: DebtorStatus;
  notes: string | null;
  createdAt: string;
  anonymizedAt: string | null;
}

/** `DebtorUseCases.RevealedDocument` — nunca persistir nem registrar em log. */
export interface RevealedDocument {
  debtorId: string;
  documentType: string;
  document: string;
}

export interface RegisterDebtorRequest {
  document: string;
  name: string;
  birthDate: string | null;
  notes: string | null;
}

export interface UpdateDebtorRequest {
  name: string;
  birthDate: string | null;
  notes: string | null;
}

export interface DebtorFilter {
  q?: string | null;
  creditorId?: string | null;
  portfolioId?: string | null;
}

export type ContactType = 'PHONE' | 'EMAIL' | 'ADDRESS';
export type ContactLabel = 'MOBILE' | 'HOME' | 'WORK' | 'OTHER';
export type ContactSource = 'IMPORT' | 'MANUAL' | 'DEBTOR' | 'PROVIDER';
export type ContactStatus = 'UNVERIFIED' | 'VALID' | 'INVALID';

/** `DebtorContactUseCases.ContactView` */
export interface ContactView {
  id: string;
  debtorId: string;
  type: ContactType;
  maskedValue: string;
  label: ContactLabel;
  whatsapp: boolean;
  source: ContactSource;
  status: ContactStatus;
  priority: number;
  lastVerifiedAt: string | null;
  invalidReason: string | null;
}

/** `DebtorContactUseCases.RevealedContact` — nunca persistir nem registrar em log. */
export interface RevealedContact {
  contactId: string;
  debtorId: string;
  type: ContactType;
  value: string;
}

export interface AddContactRequest {
  type: ContactType;
  value: string;
  label: ContactLabel | null;
  whatsapp: boolean;
  priority: number | null;
  source: 'MANUAL' | 'DEBTOR' | null;
}

export interface UpdateContactRequest {
  label: ContactLabel;
  whatsapp: boolean;
  priority: number;
}

export type TitleStatus = 'OPEN' | 'SETTLED' | 'CANCELLED' | 'SUSPENDED' | 'RETURNED';

/** `DebtTitleUseCases.TitleView` */
export interface TitleView {
  id: string;
  creditorId: string;
  portfolioId: string;
  debtorId: string;
  originContractId: string | null;
  externalId: string;
  installmentNumber: number | null;
  dueDate: string;
  principalAmount: Amount;
  openBalance: Amount;
  paidTotal: Amount;
  status: TitleStatus;
  statusReason: string | null;
  disputeBlocked: boolean;
  disputeId: string | null;
  lastPaymentAt: string | null;
  daysOverdue: number;
  importBatchId: string | null;
}

export interface TitleFilter {
  creditorId?: string | null;
  portfolioId?: string | null;
  debtorId?: string | null;
  status?: string | null;
  dueFrom?: string | null;
  dueTo?: string | null;
  minOverdueDays?: number | null;
  maxOverdueDays?: number | null;
}

export type LedgerComponent = 'PRINCIPAL' | 'INTEREST' | 'FINE' | 'FEES' | 'HONORARIUM';

export interface ComponentBalanceView {
  component: LedgerComponent;
  charged: Amount;
  discounted: Amount;
  paid: Amount;
  reversed: Amount;
  balance: Amount;
}

export interface BalanceSnapshotView {
  referenceDate: string;
  reportedBalance: Amount;
  importBatchId: string | null;
  recordedAt: string;
}

/** `DebtTitleUseCases.TitleBalanceView` */
export interface TitleBalanceView {
  titleId: string;
  status: TitleStatus;
  components: ComponentBalanceView[];
  openBalance: Amount;
  paidTotal: Amount;
  lastPaymentAt: string | null;
  divergence: Amount | null;
  snapshots: BalanceSnapshotView[];
}

export type LedgerEntryType = 'OPENING' | 'CHARGE' | 'ADJUSTMENT' | 'DISCOUNT' | 'WRITE_OFF' | 'PAYMENT' | 'REVERSAL';

export interface LedgerEntryView {
  id: string;
  entryType: LedgerEntryType;
  component: LedgerComponent;
  amount: Amount;
  occurredAt: string;
  referenceType: string | null;
  referenceId: string | null;
  reason: string | null;
  recordedAt: string;
}

export interface LedgerPage {
  titleId: string;
  entries: PageResponse<LedgerEntryView>;
}

/** `DebtTitleUseCases.OwnershipView` (`releaseType` nulo no período atual). */
export interface OwnershipView {
  creditorId: string;
  portfolioId: string;
  assignedAt: string;
  releasedAt: string | null;
  releaseType: 'TRANSFER' | 'RETURN' | null;
  releaseReason: string | null;
}

/** `DebtorSheetQueries.DebtorSummary` */
export interface DebtorSummary {
  debtorId: string;
  name: string;
  maskedDocument: string | null;
  personType: PersonType;
  status: DebtorStatus;
  contacts: ContactSummary[];
  creditors: CreditorPosition[];
  totalOpenBalance: Amount;
  maxDaysOverdue: number;
  titleCount: number;
  /** Contratos em que o devedor é coobrigado vigente (credores do escopo). */
  coObligations: CoObligationSummary[];
  /** Garantias dos contratos em que o devedor é principal ou coobrigado. */
  collateral: CollateralSummary[];
}

/** `DebtorSheetQueries.CoObligationSummary` */
export interface CoObligationSummary {
  contractId: string;
  creditorId: string;
  contractExternalId: string;
  role: ObligationRole;
  sinceDate: string | null;
  principalDebtorId: string;
  openBalance: Amount;
  maxDaysOverdue: number;
  titleCount: number;
}

/** `DebtorSheetQueries.CollateralSummary` */
export interface CollateralSummary {
  collateralId: string;
  contractId: string;
  creditorId: string;
  debtorRole: ObligationRole;
  type: string;
  assetSummary: string | null;
  status: string;
  estimatedValue: Amount | null;
  lastAppraisalDate: string | null;
}

export interface ContactSummary {
  contactId: string;
  type: ContactType;
  maskedValue: string;
  label: ContactLabel;
  whatsapp: boolean;
  status: ContactStatus;
  priority: number;
}

export interface CreditorPosition {
  creditorId: string;
  openBalance: Amount;
  maxDaysOverdue: number;
  titles: TitleSummary[];
}

export interface TitleSummary {
  titleId: string;
  portfolioId: string;
  externalId: string;
  installmentNumber: number | null;
  dueDate: string;
  principalAmount: Amount;
  openBalance: Amount;
  status: TitleStatus;
  disputeBlocked: boolean;
  daysOverdue: number;
}

export type InstrumentType = 'CONTRACT' | 'CCB' | 'PROMISSORY_NOTE' | 'DUPLICATE' | 'CHECK' | 'DEBT_CONFESSION' | 'OTHER';
export type ObligationRole = 'PRINCIPAL' | 'GUARANTOR_AVAL' | 'GUARANTOR_SURETY' | 'CO_DEBTOR' | 'THIRD_PARTY_COLLATERAL_PROVIDER';

/** `OriginContractUseCases.ContractView` — contrato de origem informado na carga do credor. */
export interface OriginContractView {
  id: string;
  creditorId: string;
  portfolioId: string;
  debtorId: string;
  externalId: string;
  product: string | null;
  signedAt: string | null;
  originalAmount: Amount | null;
  instrumentType: InstrumentType | null;
  executiveTitle: boolean;
}

/** `ObligationPartyUseCases.PartyView` — coobrigados do contrato (documento mascarado). */
export interface ObligationPartyView {
  partyId: string;
  contractId: string;
  debtorId: string;
  name: string;
  maskedDocument: string | null;
  role: ObligationRole;
  sinceDate: string | null;
  endedOn: string | null;
  endReason: string | null;
  active: boolean;
}

/** `TitlePrescriptionUseCases.PrescriptionView` — prescrição estimada por pretensão. */
export interface PrescriptionView {
  titleId: string;
  originContractId: string | null;
  instrumentType: InstrumentType | null;
  executiveTitle: boolean;
  claims: PrescriptionClaimView[];
  interruptions: PrescriptionInterruptionView[];
}

export interface PrescriptionClaimView {
  claim: 'EXECUTION' | 'COLLECTION';
  situation: 'RUNNING' | 'PRESCRIBED';
  ruleId: string | null;
  ruleValidated: boolean;
  legalBasis: string | null;
  baseDate: string | null;
  countingStart: string | null;
  termMonths: number;
  estimatedDate: string | null;
  daysRemaining: number;
  lastAlertedFor: string | null;
}

export interface PrescriptionInterruptionView {
  id: string;
  type: 'PROTEST' | 'CITATION' | 'ACKNOWLEDGEMENT' | 'OTHER';
  date: string;
  reference: string | null;
  notes: string | null;
  origin: 'MANUAL' | 'LEGAL';
  originReferenceId: string | null;
  effectiveFor: string[];
  evidence: { fileName: string; contentType: string; size: number; sha256: string } | null;
  recordedAt: string;
  recordedBy: string | null;
}
