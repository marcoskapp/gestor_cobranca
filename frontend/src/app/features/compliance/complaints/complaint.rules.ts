/**
 * Regras de tela das reclamações (funções puras, testadas em `complaint.rules.spec.ts`). Prazos, escopo e transições
 * são decididos pelo servidor; aqui só se traduz o formulário e se escolhe o que mostrar.
 */
import { UrlCodec, UrlCodecs, urlParam } from '../../../shared/data/url-state';
import { CHANNEL_LABELS, ComplaintChannel, ComplaintFilter, ComplaintStatus, ComplaintView, STATUS_LABELS } from './complaint.data';

/** Situações "em aberto" (aguardando resposta ao canal). */
export const OPEN_STATUSES = 'RECEIVED,IN_PROGRESS';

export interface ComplaintListForm {
  creditor: string[];
  /** `OPEN` (padrão), '' (todas) ou uma situação. */
  status: string;
  channel: string;
  /** '' (todos), `me` ou `none` (sem responsável). */
  assignee: string;
  /** '' (qualquer), `soon`, `overdue` ou `on_time`. */
  deadline: string;
  protocol: string;
}

export const COMPLAINT_FORM_DEFAULTS: ComplaintListForm = {
  creditor: [],
  status: 'OPEN',
  channel: '',
  assignee: '',
  deadline: '',
  protocol: '',
};

/** Formulário da fila → filtro da API. */
export function toComplaintFilter(v: ComplaintListForm): ComplaintFilter {
  const filter: ComplaintFilter = {
    creditorId: v.creditor[0] ?? null,
    status: v.status === 'OPEN' ? OPEN_STATUSES : v.status || null,
    channel: v.channel || null,
    deadline: v.deadline || null,
    protocol: v.protocol.trim() || null,
  };
  if (v.assignee === 'me') {
    filter.assignee = 'me';
  } else if (v.assignee === 'none') {
    filter.unassigned = true;
  }
  return filter;
}

const ALL = 'todas';

/**
 * Filtros na URL, compatíveis com "Minhas pendências" (`responsavel=me`, `sem-responsavel=1`, `prazo=soon`):
 * `?credor=&situacao=&canal=&responsavel=me|nenhum&sem-responsavel=1&prazo=soon|overdue|on_time&protocolo=`.
 * "Todas" as situações vira `situacao=todas`; o padrão (em aberto) fica fora da URL.
 */
export const COMPLAINT_URL_CODECS: UrlCodecs<ComplaintListForm> = {
  creditor: urlParam.ids('credor'),
  status: {
    param: 'situacao',
    parse: (raw) => (raw === ALL ? '' : raw === 'OPEN' || raw in STATUS_LABELS ? raw : undefined),
    format: (value) => value || ALL,
  },
  channel: urlParam.choice('canal', Object.keys(CHANNEL_LABELS) as ComplaintChannel[]),
  assignee: assigneeCodec(),
  deadline: urlParam.choice('prazo', ['soon', 'overdue', 'on_time']),
  protocol: urlParam.text('protocolo'),
};

/**
 * Responsável: `responsavel=me` ou `responsavel=nenhum`. O parâmetro `sem-responsavel=1` (usado por "Minhas
 * pendências") é lido por {@link unassignedAlias}.
 */
function assigneeCodec(): UrlCodec<string> {
  return {
    param: 'responsavel',
    parse: (raw) => (raw === 'me' ? 'me' : raw === 'nenhum' ? 'none' : raw === '' ? '' : undefined),
    format: (value) => (value === 'me' ? 'me' : value === 'none' ? 'nenhum' : null),
  };
}

/** `sem-responsavel=1` (link de "Minhas pendências") equivale a `responsavel=nenhum` quando não há responsável na URL. */
export function unassignedAlias(get: (param: string) => string | null): boolean {
  return get('sem-responsavel') === '1' && !get('responsavel');
}

export type ComplaintAction = 'start' | 'answer' | 'close' | 'assign' | 'dueDate' | 'reclassify';

/** Ações de tratamento pela situação (espelho das guardas do agregado `Complaint`). */
export function complaintActions(status: ComplaintStatus): ComplaintAction[] {
  switch (status) {
    case 'RECEIVED':
      return ['start', 'answer', 'assign', 'dueDate', 'reclassify'];
    case 'IN_PROGRESS':
      return ['answer', 'assign', 'dueDate', 'reclassify'];
    case 'ANSWERED':
      return ['close', 'assign', 'reclassify'];
    default:
      return [];
  }
}

/** Ação primária do cabeçalho (§12.2): a próxima natural da situação. */
export function primaryAction(status: ComplaintStatus): 'start' | 'answer' | 'close' | null {
  return status === 'RECEIVED' ? 'start' : status === 'IN_PROGRESS' ? 'answer' : status === 'ANSWERED' ? 'close' : null;
}

export function isOpen(c: Pick<ComplaintView, 'status'>): boolean {
  return c.status === 'RECEIVED' || c.status === 'IN_PROGRESS';
}

/** Tipos aceitos como evidência (conferidos pelo conteúdo no servidor) e tamanho máximo. */
export const COMPLAINT_EVIDENCE_ACCEPT = '.pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg';
export const COMPLAINT_EVIDENCE_MAX_BYTES = 10 * 1024 * 1024;

export function complaintEvidenceProblem(file: Pick<File, 'type' | 'size'>): string | null {
  if (file.size <= 0) {
    return 'O arquivo está vazio.';
  }
  if (file.size > COMPLAINT_EVIDENCE_MAX_BYTES) {
    return 'O arquivo excede 10 MB.';
  }
  if (!['application/pdf', 'image/png', 'image/jpeg'].includes(file.type)) {
    return 'Envie PDF, PNG ou JPEG.';
  }
  return null;
}

export const EVIDENCE_STATUS_LABELS: Record<'PENDING_SCAN' | 'AVAILABLE' | 'REJECTED' | 'FAILED', string> = {
  PENDING_SCAN: 'Em verificação',
  AVAILABLE: 'Disponível',
  REJECTED: 'Recusada (malware)',
  FAILED: 'Sem veredito',
};

/** Primeiro e último dia do mês da data ISO (período padrão do indicador). */
export function monthRange(today: string): { from: string; to: string } {
  const [y, m] = today.split('-').map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const mm = String(m).padStart(2, '0');
  return { from: `${y}-${mm}-01`, to: `${y}-${mm}-${String(last).padStart(2, '0')}` };
}
