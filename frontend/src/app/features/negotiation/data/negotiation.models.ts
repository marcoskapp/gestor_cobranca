/** Contratos dos controllers do módulo negotiation (parte 1: políticas, índices, alçadas e simulação). */

import { PageResponse } from '../../../shared/models/page';

export type Amount = number;
export type { PageResponse };

/** Percentual por componente da dívida (0 a 100). Componentes ausentes valem 0. */
export interface ComponentRates {
  principal: number;
  correction: number;
  interest: number;
  fine: number;
  fees: number;
  honorarium: number;
}

export type DebtComponent = keyof ComponentRates;
export const DEBT_COMPONENTS: readonly DebtComponent[] = ['principal', 'correction', 'interest', 'fine', 'fees', 'honorarium'];

export type InterestMode = 'SIMPLE' | 'COMPOUND';
export type FeeType = 'FIXED' | 'PERCENT_OF_PRINCIPAL';
export type HonorariumBase = 'PRINCIPAL' | 'UPDATED_PRINCIPAL' | 'UPDATED_DEBT';
export type ScheduleFrequency = 'MONTHLY' | 'EVERY_N_DAYS';

export interface Fee {
  code: string;
  description: string;
  type: FeeType;
  value: number;
}

export interface Charges {
  interestMonthlyRate: number;
  interestMode: InterestMode;
  finePercent: number;
  correctionIndexCode: string | null;
  fees: Fee[];
  honorariumPercent: number;
  honorariumBase: HonorariumBase;
}

export interface DiscountTier {
  upToInstallments: number;
  maxDiscount: ComponentRates;
}

export interface Installments {
  maxInstallments: number;
  minInstallmentAmount: number;
  minDownPaymentPercent: number;
  minDownPaymentAmount: number;
  monthlyInterestRate: number;
  frequency: ScheduleFrequency;
  intervalDays: number | null;
}

export interface Validity {
  proposalValidityDays: number;
  maxDaysToFirstPayment: number;
}

export interface ConcessionLevel {
  level: number;
  name: string;
  envelopeShare: ComponentRates;
  maxInstallments: number | null;
}

export interface BreakRule {
  overdueInstallments: number;
  toleranceDays: number;
}

/** `PolicyRules` */
export interface PolicyRules {
  charges: Charges;
  discountTiers: DiscountTier[];
  installments: Installments;
  validity: Validity;
  ladder: ConcessionLevel[];
  breakRule: BreakRule;
}

/** `PolicyOverride`: grupos não nulos substituem os da base por inteiro. */
export interface PolicyOverride {
  code: string;
  portfolioId: string | null;
  minDaysOverdue: number | null;
  maxDaysOverdue: number | null;
  charges: Charges | null;
  discountTiers: DiscountTier[] | null;
  installments: Installments | null;
  validity: Validity | null;
  ladder: ConcessionLevel[] | null;
  breakRule: BreakRule | null;
}

export type OverrideGroup = 'charges' | 'discountTiers' | 'installments' | 'validity' | 'ladder' | 'breakRule';
export const OVERRIDE_GROUPS: readonly OverrideGroup[] = ['charges', 'discountTiers', 'installments', 'validity', 'ladder', 'breakRule'];

/** `PolicyTerms` */
export interface PolicyTerms {
  base: PolicyRules;
  overrides: PolicyOverride[];
}

export type PolicyStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';

/** `CollectionPolicyUseCases.PolicyView` */
export interface PolicyView {
  id: string;
  creditorId: string;
  version: number;
  name: string;
  description: string | null;
  status: PolicyStatus;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  terms: PolicyTerms;
  termsSchemaVersion: number;
  publishedAt: string | null;
  publishedBy: string | null;
  retiredAt: string | null;
  createdAt: string;
}

export interface EffectivePolicyView {
  policyId: string;
  creditorId: string;
  version: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  overrideCode: string | null;
  rules: PolicyRules;
}

export interface CreatePolicyRequest {
  creditorId: string;
  name: string;
  description: string | null;
  terms: PolicyTerms;
}

export interface UpdatePolicyRequest {
  name: string;
  description: string | null;
  terms: PolicyTerms;
}

// --------------------------------------------------------------------- índices

/** `YearMonth` serializado como "yyyy-MM". */
export interface RateView {
  competence: string;
  ratePercent: number;
}

export interface IndexView {
  id: string;
  code: string;
  name: string;
  source: string | null;
  lastCompetence: string | null;
  rates: RateView[];
}

// --------------------------------------------------------------------- alçadas

export interface ProfileView {
  id: string;
  code: string;
  name: string;
  description: string | null;
  systemProfile: boolean;
  maxConcessionLevel: number;
  maxDiscountShare: number;
  maxInstallments: number;
  maxAgreementAmount: number | null;
  minDownPaymentShare: number;
  canFormalize: boolean;
  canApprove: boolean;
  maxRenegotiations: number;
  creditorIds: string[];
  assignments: number;
}

export interface ProfileRequest {
  code: string | null;
  name: string;
  description: string | null;
  maxConcessionLevel: number;
  maxDiscountShare: number;
  maxInstallments: number;
  maxAgreementAmount: number | null;
  minDownPaymentShare: number;
  canFormalize: boolean;
  canApprove: boolean;
  maxRenegotiations: number;
  creditorIds: string[];
}

export type SubjectType = 'USER' | 'ASSISTANT_POLICY';

export interface AuthorityAssignmentView {
  id: string;
  subjectType: SubjectType;
  subjectId: string;
  creditorId: string | null;
  profileId: string;
  profileCode: string;
}

export interface AssignAuthorityRequest {
  subjectType: SubjectType;
  subjectId: string;
  creditorId: string | null;
  profileId: string;
}

export interface EffectiveAuthorityView {
  subjectType: string;
  subjectId: string;
  creditorId: string;
  source: 'ASSIGNED_CREDITOR' | 'ASSIGNED_ALL' | 'DEFAULT';
  profile: ProfileView;
}

// --------------------------------------------------------------------- simulação

export type ConcessionReason =
  | 'PAYMENT_CAPACITY'
  | 'INCOME_LOSS'
  | 'HEALTH_OR_FAMILY'
  | 'COMPETING_OFFER'
  | 'LOW_RECOVERY_PROSPECT'
  | 'IMMEDIATE_PAYMENT'
  | 'OTHER';

export interface Justification {
  reason: ConcessionReason;
  note: string | null;
}

export interface PlanRequest {
  installments: number;
  downPayment: number | null;
  firstDueDate: string | null;
}

export interface SimulateRequest {
  creditorId: string;
  debtorId: string;
  titleIds: string[] | null;
  referenceDate: string | null;
  requestedLevel: number | null;
  justification: Justification | null;
  plans: PlanRequest[] | null;
  channel: string | null;
}

export interface ValidateRequest {
  creditorId: string;
  debtorId: string;
  titleIds: string[] | null;
  referenceDate: string | null;
  policyId: string | null;
  level: number;
  justification: Justification | null;
  discounts: ComponentRates | null;
  plan: PlanRequest;
  channel: string | null;
}

export interface PolicyRef {
  policyId: string;
  version: number;
  overrideCode: string | null;
}

export interface ActorRef {
  type: string;
  id: string;
  profileId: string | null;
  profileCode: string | null;
}

export interface LimitsView {
  maxLevel: number;
  maxDiscountShare: number;
  maxInstallments: number;
  maxAgreementAmount: number | null;
  minDownPaymentShare: number;
  canFormalize: boolean;
  canApprove: boolean;
}

export interface CalculationLine {
  titleId: string | null;
  component: string;
  rule: string;
  base: number | null;
  rate: number | null;
  days: number | null;
  factor: number | null;
  amount: number;
  note: string | null;
}

export interface TitleComposition {
  titleId: string;
  portfolioId: string;
  dueDate: string;
  daysOverdue: number;
  components: ComponentRates;
  total: Amount;
  paidTotal: Amount;
  correctionFactor: number | null;
  lines: CalculationLine[];
}

export interface CompositionView {
  referenceDate: string;
  titles: TitleComposition[];
  total: ComponentRates;
  totalAmount: Amount;
  paidTotal: Amount;
  maxDaysOverdue: number;
}

export interface EnvelopeTier {
  upToInstallments: number;
  maxDiscountRates: ComponentRates;
  maxDiscountAmount: Amount;
  minimumNegotiated: Amount;
}

export interface PaymentView {
  number: number;
  dueDate: string;
  amount: Amount;
  interest: Amount;
  amortization: Amount;
  balanceAfter: Amount;
}

export type ViolationKind = 'POLICY' | 'AUTHORITY' | 'ELIGIBILITY';

export interface ViolationView {
  kind: ViolationKind;
  code: string;
  message: string;
  component: string | null;
  titleId: string | null;
}

export interface OfferView {
  level: number;
  levelName: string;
  installments: number;
  valid: boolean;
  withinAuthority: boolean;
  discountRates: ComponentRates;
  discount: ComponentRates;
  discountTotal: Amount;
  net: ComponentRates;
  negotiated: Amount;
  downPayment: Amount | null;
  total: Amount;
  financingInterest: Amount;
  periodRate: number | null;
  schedule: PaymentView[];
  discountLines: CalculationLine[];
  violations: ViolationView[];
}

export interface ExcludedTitle {
  titleId: string;
  reasons: string[];
}

export interface SimulationResult {
  creditorId: string;
  debtorId: string;
  portfolioId: string | null;
  policy: PolicyRef;
  referenceDate: string;
  actor: ActorRef;
  limits: LimitsView;
  requestedLevel: number;
  grantedLevel: number;
  levelCapped: boolean;
  nextLevelAvailable: boolean;
  justification: Justification | null;
  channel: string | null;
  composition: CompositionView;
  envelope: EnvelopeTier[] | null;
  offers: OfferView[];
  excludedTitles: ExcludedTitle[];
  notes: string[];
  engineVersion: number;
}

export interface SimulationView {
  id: string;
  createdAt: string;
  result: SimulationResult;
}

export type ValidationOutcome = 'WITHIN_AUTHORITY' | 'REQUIRES_APPROVAL' | 'REJECTED';

export interface ValidationView {
  outcome: ValidationOutcome;
  policy: PolicyRef | null;
  actor: ActorRef | null;
  limits: LimitsView | null;
  offer: OfferView | null;
  violations: ViolationView[];
  excludedTitles: ExcludedTitle[];
}
