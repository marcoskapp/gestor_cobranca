/** Destaque de uma pendência: algum item vencido, algum vencendo hoje ou nenhum prazo próximo. */
export type PendingWorkSeverity = 'OVERDUE' | 'DUE_TODAY' | 'NORMAL';

/** Uma pendência agregada por tipo (`GET /api/v1/me/pending-work`). Só contagens, sem dado pessoal. */
export interface PendingWorkItem {
  /** Código estável `<modulo>.<pendencia>`. */
  type: string;
  source: string;
  /** Área (ex.: "Pagamentos"). */
  area: string;
  label: string;
  hint: string | null;
  count: number;
  overdue: number;
  dueToday: number;
  severity: PendingWorkSeverity;
  /** Desde quando o item mais antigo espera (ISO-8601). */
  oldestAt: string | null;
  /** Prazo mais próximo (`yyyy-MM-dd`), quando a pendência tem prazo. */
  nextDueDate: string | null;
  /** Rota da tela de ação (com um único item, a do próprio item). */
  route: string;
  queryParams: Record<string, string>;
}

/** @param reason TIMEOUT, ERROR ou BUSY */
export interface UnavailableSource {
  source: string;
  area: string;
  reason: string;
}

export interface PendingWorkSummary {
  generatedAt: string;
  total: number;
  overdue: number;
  dueToday: number;
  items: PendingWorkItem[];
  unavailable: UnavailableSource[];
}
