/** Contratos dos controllers do módulo organization. */

/** `TenantView` */
export interface TenantView {
  id: string;
  slug: string;
  legalName: string;
  tradeName: string;
  cnpj: string;
  status: 'ACTIVE' | 'SUSPENDED';
  platform: boolean;
  /** `LocalTime` serializado como "HH:mm:ss" */
  contactWindowStart: string;
  contactWindowEnd: string;
  contactOnSaturday: boolean;
  contactOnSunday: boolean;
}

export interface TenantSettingsRequest {
  legalName: string;
  tradeName: string;
  contactWindowStart: string;
  contactWindowEnd: string;
  contactOnSaturday: boolean;
  contactOnSunday: boolean;
}

/** `BusinessUnitUseCases.BusinessUnitView` */
export interface BusinessUnitView {
  id: string;
  code: string;
  name: string;
  active: boolean;
}

/** `CreditorUseCases.CreditorView` */
export interface CreditorView {
  id: string;
  legalName: string;
  tradeName: string | null;
  document: string;
  externalCode: string | null;
  contactEmail: string | null;
  status: 'ACTIVE' | 'INACTIVE';
}

export interface CreditorRequest {
  legalName: string;
  tradeName: string | null;
  document: string;
  externalCode: string | null;
  contactEmail: string | null;
}

/** `ServiceContractUseCases.ContractView` */
export interface ContractView {
  id: string;
  creditorId: string;
  number: string;
  startDate: string;
  endDate: string | null;
  returnAfterDays: number | null;
  settlementDay: number;
  notes: string | null;
  status: 'ACTIVE' | 'TERMINATED';
}

export interface ContractTermsRequest {
  startDate: string;
  endDate: string | null;
  returnAfterDays: number | null;
  settlementDay: number;
  notes: string | null;
}

export interface CreateContractRequest {
  creditorId: string;
  number: string;
  terms: ContractTermsRequest;
}

export type PortfolioStatus = 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'RETURNED' | 'CLOSED';

/** `PortfolioUseCases.PortfolioView` */
export interface PortfolioView {
  id: string;
  creditorId: string;
  contractId: string;
  code: string;
  name: string;
  description: string | null;
  receivedOn: string;
  validUntil: string | null;
  status: PortfolioStatus;
  statusReason: string | null;
  operable: boolean;
}

export interface CreatePortfolioRequest {
  creditorId: string;
  contractId: string;
  code: string;
  name: string;
  description: string | null;
  receivedOn: string;
  validUntil: string | null;
}

export interface UpdatePortfolioRequest {
  name: string;
  description: string | null;
  receivedOn: string;
  validUntil: string | null;
}
