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

export const CONTACT_TYPE_ICONS: Record<ContactType, string> = {
  PHONE: 'call',
  EMAIL: 'mail',
  ADDRESS: 'home',
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
