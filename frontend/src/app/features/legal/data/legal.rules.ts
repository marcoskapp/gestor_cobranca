/**
 * Regras de tela do jurídico (funções puras). Espelham validações do domínio para orientar antes do
 * envio (número CNJ, urgência de prazos); o servidor valida tudo de novo.
 */
import { FINAL_STAGES } from './legal.labels';
import { DeadlineView, LegalStage, MatterView } from './legal.models';

/** Somente dígitos do número CNJ (NNNNNNN-DD.AAAA.J.TR.OOOO = 20 dígitos). */
export function cnjDigits(raw: string): string {
  return (raw ?? '').replace(/\D/g, '');
}

/** Dígito verificador pelo módulo 97 (ISO 7064), como em `CnjNumber`. */
export function cnjValid(raw: string): boolean {
  const d = cnjDigits(raw);
  if (d.length !== 20) {
    return false;
  }
  const reordered = d.slice(0, 7) + d.slice(9) + d.slice(7, 9);
  let rest = 0;
  for (const ch of reordered) {
    rest = (rest * 10 + Number(ch)) % 97;
  }
  return rest === 1;
}

/** Formata 20 dígitos como NNNNNNN-DD.AAAA.J.TR.OOOO. */
export function formatCnj(raw: string): string {
  const d = cnjDigits(raw);
  return d.length === 20 ? `${d.slice(0, 7)}-${d.slice(7, 9)}.${d.slice(9, 13)}.${d.slice(13, 14)}.${d.slice(14, 16)}.${d.slice(16)}` : raw;
}

export function isFinalStage(stage: LegalStage): boolean {
  return FINAL_STAGES.includes(stage);
}

export type DeadlineTone = 'late' | 'soon' | 'ok' | 'done';

/**
 * Urgência do prazo: perdido/vencido, próximo (dentro do maior nível de alerta, padrão 5 dias úteis)
 * ou com folga. Usa os dias úteis restantes calculados no servidor.
 */
export function deadlineTone(d: Pick<DeadlineView, 'status' | 'dueDate' | 'businessDaysRemaining'>, today: string, alertDays = 5): DeadlineTone {
  if (d.status === 'MET' || d.status === 'CANCELLED') {
    return 'done';
  }
  if (d.status === 'MISSED' || d.dueDate < today) {
    return 'late';
  }
  const left = d.businessDaysRemaining;
  return left !== null && left !== undefined && left <= alertDays ? 'soon' : 'ok';
}

export function deadlineText(d: Pick<DeadlineView, 'status' | 'dueDate' | 'businessDaysRemaining'>, today: string): string {
  if (d.status === 'MISSED') {
    return 'Prazo perdido';
  }
  if (d.status === 'MET') {
    return 'Cumprido';
  }
  if (d.status === 'CANCELLED') {
    return 'Cancelado';
  }
  if (d.dueDate < today) {
    return 'Vencido';
  }
  if (d.dueDate === today) {
    return 'Vence hoje';
  }
  const left = d.businessDaysRemaining;
  return left === null || left === undefined ? 'Em aberto' : left === 1 ? '1 dia útil' : `${left} dias úteis`;
}

/** Uso do orçamento autorizado (0–100+, nulo sem orçamento). */
export function budgetUsage(m: Pick<MatterView, 'authorizedBudget' | 'expensesTotal'>): number | null {
  return m.authorizedBudget && m.authorizedBudget > 0 ? Math.round((m.expensesTotal / m.authorizedBudget) * 1000) / 10 : null;
}


/** `LocalDate` a partir de hoje + n dias (fuso local). */
export function addDays(today: string, days: number): string {
  const [y, m, d] = today.split('-').map(Number);
  const date = new Date(y, m - 1, d + days);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
