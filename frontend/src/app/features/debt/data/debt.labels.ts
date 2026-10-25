import { IconName } from '../../../shared/components/icon/icon-names';
import { Option } from '../../../shared/models/lookup';
import { ContactLabel, ContactSource, ContactType, LedgerComponent, LedgerEntryType, PersonType } from './debt.models';

export const PERSON_TYPE_LABELS: Record<PersonType, string> = {
  INDIVIDUAL: 'Pessoa física',
  COMPANY: 'Pessoa jurídica',
};

export const CONTACT_TYPE_LABELS: Record<ContactType, string> = {
  PHONE: 'Telefone',
  EMAIL: 'E-mail',
  ADDRESS: 'Endereço',
};

export const CONTACT_TYPE_ICONS: Record<ContactType, IconName> = {
  PHONE: 'telephone',
  EMAIL: 'envelope',
  ADDRESS: 'house',
};

export const CONTACT_LABEL_LABELS: Record<ContactLabel, string> = {
  MOBILE: 'Celular',
  HOME: 'Residencial',
  WORK: 'Comercial',
  OTHER: 'Outro',
};

export const CONTACT_SOURCE_LABELS: Record<ContactSource, string> = {
  IMPORT: 'Carga do credor',
  MANUAL: 'Cadastro manual',
  DEBTOR: 'Informado pelo devedor',
  PROVIDER: 'Provedor externo',
};

export const LEDGER_COMPONENT_LABELS: Record<LedgerComponent, string> = {
  PRINCIPAL: 'Principal',
  INTEREST: 'Juros',
  FINE: 'Multa',
  FEES: 'Encargos',
  HONORARIUM: 'Honorários',
};

export const LEDGER_ENTRY_LABELS: Record<LedgerEntryType, string> = {
  OPENING: 'Abertura',
  CHARGE: 'Encargo reconhecido',
  ADJUSTMENT: 'Ajuste do credor',
  DISCOUNT: 'Desconto',
  WRITE_OFF: 'Baixa',
  PAYMENT: 'Pagamento',
  REVERSAL: 'Estorno',
};

/** Origem do lançamento (`referenceType` livre; valores conhecidos dos módulos que lançam no razão). */
export const REFERENCE_TYPE_LABELS: Record<string, string> = {
  import_batch: 'Carga do credor',
  agreement: 'Acordo',
  payment: 'Pagamento',
};

export const OWNERSHIP_RELEASE_LABELS: Record<string, string> = {
  TRANSFER: 'Transferido de carteira',
  RETURN: 'Devolvido ao credor',
};

/**
 * Faixas de atraso usuais na cobrança (aging). Os limites são inclusivos e vão para os filtros
 * `minOverdueDays`/`maxOverdueDays`, que só consideram títulos em cobrança.
 */
export interface OverdueBucket {
  value: string;
  label: string;
  min: number | null;
  max: number | null;
}

export const OVERDUE_BUCKETS: readonly OverdueBucket[] = [
  { value: '0-0', label: 'A vencer / em dia', min: 0, max: 0 },
  { value: '1-30', label: '1 a 30 dias', min: 1, max: 30 },
  { value: '31-60', label: '31 a 60 dias', min: 31, max: 60 },
  { value: '61-90', label: '61 a 90 dias', min: 61, max: 90 },
  { value: '91-180', label: '91 a 180 dias', min: 91, max: 180 },
  { value: '181-360', label: '181 a 360 dias', min: 181, max: 360 },
  { value: '361-', label: 'Acima de 360 dias', min: 361, max: null },
];

export function options<K extends string>(labels: Record<K, string>): Option<K>[] {
  return (Object.entries(labels) as [K, string][]).map(([value, label]) => ({ value, label }));
}

/** Tom visual do atraso (aging): quanto maior, mais crítico. */
export function overdueTone(days: number): 'neutral' | 'info' | 'warning' | 'danger' {
  if (days <= 0) {
    return 'neutral';
  }
  if (days <= 30) {
    return 'info';
  }
  if (days <= 90) {
    return 'warning';
  }
  return 'danger';
}

/** Atraso em texto curto ("Em dia", "1 dia", "37 dias"). */
export function overdueText(days: number): string {
  if (days <= 0) {
    return 'Em dia';
  }
  return days === 1 ? '1 dia' : `${days} dias`;
}

/** Identificação do título para o operador: código do credor e parcela. */
export function titleLabel(t: { externalId: string; installmentNumber: number | null }): string {
  return t.installmentNumber ? `${t.externalId} · parcela ${t.installmentNumber}` : t.externalId;
}

export const INSTRUMENT_TYPE_LABELS: Record<string, string> = {
  CONTRACT: 'Contrato',
  CCB: 'Cédula de Crédito Bancário',
  PROMISSORY_NOTE: 'Nota promissória',
  DUPLICATE: 'Duplicata',
  CHECK: 'Cheque',
  DEBT_CONFESSION: 'Confissão de dívida',
  OTHER: 'Outro instrumento',
};

export const OBLIGATION_ROLE_LABELS: Record<string, string> = {
  PRINCIPAL: 'Devedor principal',
  GUARANTOR_AVAL: 'Avalista',
  GUARANTOR_SURETY: 'Fiador',
  CO_DEBTOR: 'Codevedor solidário',
  THIRD_PARTY_COLLATERAL_PROVIDER: 'Garantidor (bem de terceiro)',
};

export const COLLATERAL_TYPE_LABELS: Record<string, string> = {
  VEHICLE_FIDUCIARY_ALIENATION: 'Alienação fiduciária de veículo',
  REAL_ESTATE_FIDUCIARY_ALIENATION: 'Alienação fiduciária de imóvel',
  MORTGAGE: 'Hipoteca',
  PLEDGE: 'Penhor',
  RECEIVABLES_FIDUCIARY_ASSIGNMENT: 'Cessão fiduciária de recebíveis',
  SURETY_INSURANCE: 'Seguro garantia',
  OTHER: 'Outra garantia',
};

export const PRESCRIPTION_CLAIM_LABELS: Record<string, string> = {
  EXECUTION: 'Execução (título executivo)',
  COLLECTION: 'Cobrança',
};

export const INTERRUPTION_TYPE_LABELS: Record<string, string> = {
  PROTEST: 'Protesto',
  CITATION: 'Citação',
  ACKNOWLEDGEMENT: 'Reconhecimento da dívida',
  OTHER: 'Outra causa',
};

/** Tom da prescrição: prescrita = perigo; até 90 dias = alerta; demais = neutro. */
export function prescriptionTone(c: { situation: string; daysRemaining: number }): 'danger' | 'warning' | 'neutral' {
  if (c.situation === 'PRESCRIBED' || c.daysRemaining < 0) {
    return 'danger';
  }
  return c.daysRemaining <= 90 ? 'warning' : 'neutral';
}
