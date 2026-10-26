import { PendingWorkItem, PendingWorkSummary } from './pending-work.models';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** Destaque de prazo do item ("2 vencidos", "1 vence hoje", "próximo prazo 30/10") ou nulo. */
export function severityText(item: PendingWorkItem): string | null {
  if (item.severity === 'OVERDUE') {
    return plural(item.overdue, 'vencido', 'vencidos');
  }
  if (item.severity === 'DUE_TODAY') {
    return item.dueToday === 1 ? '1 vence hoje' : `${item.dueToday} vencem hoje`;
  }
  if (item.nextDueDate) {
    const [, month, day] = item.nextDueDate.split('-');
    return `próximo prazo ${day}/${month}`;
  }
  return null;
}

/** Há quanto tempo o item mais antigo espera ("há 3 horas", "há 2 dias") ou nulo. */
export function ageText(oldestAt: string | null, now: Date = new Date()): string | null {
  if (!oldestAt) {
    return null;
  }
  const elapsed = now.getTime() - new Date(oldestAt).getTime();
  if (Number.isNaN(elapsed)) {
    return null;
  }
  if (elapsed < HOUR) {
    return 'há menos de 1 hora';
  }
  if (elapsed < DAY) {
    return `há ${plural(Math.floor(elapsed / HOUR), 'hora', 'horas')}`;
  }
  return `há ${plural(Math.floor(elapsed / DAY), 'dia', 'dias')}`;
}

/**
 * Linha de apoio do item: área, próximo prazo (quando nada vence hoje nem está vencido — esses viram
 * selo) e idade do mais antigo.
 */
export function itemMeta(item: PendingWorkItem, now: Date = new Date()): string {
  const age = ageText(item.oldestAt, now);
  const due = item.severity === 'NORMAL' ? severityText(item) : null;
  return [item.area, due, age ? `mais antigo ${age}` : null].filter(Boolean).join(' · ');
}

/** Rótulo acessível do indicador da barra superior. */
export function indicatorLabel(summary: PendingWorkSummary | null): string {
  if (!summary) {
    return 'Suas pendências';
  }
  if (summary.total === 0) {
    return 'Suas pendências: nada pendente';
  }
  const parts = [plural(summary.total, 'item', 'itens')];
  if (summary.overdue > 0) {
    parts.push(plural(summary.overdue, 'vencido', 'vencidos'));
  }
  if (summary.dueToday > 0) {
    parts.push(summary.dueToday === 1 ? '1 vence hoje' : `${summary.dueToday} vencem hoje`);
  }
  return `Suas pendências: ${parts.join(', ')}`;
}

/** Contagem compacta para o selo do indicador. */
export function badgeText(total: number): string {
  return total > 99 ? '99+' : String(total);
}

/** Frase do anúncio polido quando as contagens mudam numa atualização (nunca na primeira carga). */
export function changeAnnouncement(previous: PendingWorkSummary | null, next: PendingWorkSummary): string | null {
  if (!previous || (previous.total === next.total && previous.overdue === next.overdue)) {
    return null;
  }
  return `${indicatorLabel(next)}.`;
}

/** Filtro da tela de destino, ou nulo (o `routerLink` não acrescenta `?`). */
export function queryParamsOf(item: PendingWorkItem): Record<string, string> | null {
  return item.queryParams && Object.keys(item.queryParams).length > 0 ? item.queryParams : null;
}
