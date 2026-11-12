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

/**
 * `ContractIntegrationPricingUseCases.IntegrationPricingView`: versão das condições de cobrança das integrações (API
 * pública) no contrato. Imutável; vale para os ciclos de consumo que começam depois de `registeredAt`.
 */
export interface IntegrationPricingView {
  id: string;
  contractId: string;
  contractNumber: string;
  creditorId: string;
  version: number;
  usageClosingDay: number;
  monthlyFee: number;
  includedRequests: number;
  pricePerRequest: number;
  includedMbIn: number;
  pricePerMbIn: number;
  includedMbOut: number;
  pricePerMbOut: number;
  includedRecords: number;
  pricePerRecord: number;
  registeredAt: string;
  registeredBy: string | null;
  reason: string | null;
}

export type IntegrationPricingRequest = Omit<
  IntegrationPricingView,
  'id' | 'contractId' | 'contractNumber' | 'creditorId' | 'version' | 'registeredAt' | 'registeredBy'
>;

export interface CreateContractRequest {
  creditorId: string;
  number: string;
  terms: ContractTermsRequest;
}

export type PortfolioStatus = 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'RETURNED' | 'CLOSED';

/** Regime da carteira (cobrança B2B §6): consumo (CDC, padrão) ou empresarial (entre empresas). */
export type PortfolioRegime = 'CONSUMER' | 'BUSINESS';

export const PORTFOLIO_REGIME_LABELS: Record<PortfolioRegime, string> = {
  CONSUMER: 'Consumo',
  BUSINESS: 'Empresarial',
};

/** Efeito de cada regime nas regras de proteção (texto curto da dica e do diálogo). */
export const PORTFOLIO_REGIME_EFFECTS: Record<PortfolioRegime, string> = {
  CONSUMER:
    'Relação de consumo (CDC). A revisão da carga alerta encargos acima do teto de multa de 2% e o superendividamento pode ser registrado.',
  BUSINESS:
    'Relação entre empresas (contrato). Sem o alerta de multa do CDC na revisão da carga e sem superendividamento; os demais cuidados com pessoa física continuam.',
};

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
  regime: PortfolioRegime;
}

export interface CreatePortfolioRequest {
  creditorId: string;
  contractId: string;
  code: string;
  name: string;
  description: string | null;
  receivedOn: string;
  validUntil: string | null;
  regime: PortfolioRegime;
}

export interface UpdatePortfolioRequest {
  name: string;
  description: string | null;
  receivedOn: string;
  validUntil: string | null;
}

/** Contato do credor para os pedidos de contestação (canal formal). Contatos da carteira substituem os do credor. */
export interface DisputeContactView {
  id: string;
  creditorId: string;
  portfolioId: string | null;
  name: string;
  email: string;
  phone: string | null;
  role: 'PRIMARY' | 'COPY';
  active: boolean;
  version: number;
}

export interface DisputeContactRequest {
  portfolioId: string | null;
  name: string;
  email: string;
  phone: string | null;
  role: 'PRIMARY' | 'COPY';
}
