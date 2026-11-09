import { countActiveFilters } from '../../../shared/data/filter-count';
import { convertToParamMap } from '@angular/router';
import { readUrlValues, urlParamsOf } from '../../../shared/data/url-state';
import { deadlineText, deadlineTone, onTimeRate } from './complaint.data';
import { receivedInstant } from './complaint-form';
import {
  COMPLAINT_FORM_DEFAULTS,
  COMPLAINT_URL_CODECS,
  complaintActions,
  complaintEvidenceProblem,
  monthRange,
  primaryAction,
  toComplaintFilter,
  unassignedAlias,
} from './complaint.rules';

describe('regras de tela das reclamações', () => {
  it('texto e tom do prazo', () => {
    expect(deadlineText({ deadlineState: 'ON_TIME', daysToDue: 5 })).toBe('Faltam 5 dias');
    expect(deadlineText({ deadlineState: 'DUE_SOON', daysToDue: 1 })).toBe('Vence amanhã');
    expect(deadlineText({ deadlineState: 'DUE_SOON', daysToDue: 0 })).toBe('Vence hoje');
    expect(deadlineText({ deadlineState: 'OVERDUE', daysToDue: -1 })).toBe('Vencida há 1 dia');
    expect(deadlineText({ deadlineState: 'OVERDUE', daysToDue: -4 })).toBe('Vencida há 4 dias');
    expect(deadlineText({ deadlineState: 'ANSWERED_LATE', daysToDue: null })).toBe('Respondida fora do prazo');
    expect(deadlineTone('OVERDUE')).toBe('late');
    expect(deadlineTone('ANSWERED_LATE')).toBe('late');
    expect(deadlineTone('DUE_SOON')).toBe('soon');
    expect(deadlineTone('ANSWERED_ON_TIME')).toBe('ok');
  });

  it('taxa no prazo só com respostas no período', () => {
    const base = { creditorId: 'c', received: 3, open: 1, openOverdue: 0 };
    expect(onTimeRate({ ...base, answeredOnTime: 3, answeredLate: 1 })).toBe(0.75);
    expect(onTimeRate({ ...base, answeredOnTime: 0, answeredLate: 0 })).toBeNull();
  });

  it('formulário da fila para o filtro da API', () => {
    expect(toComplaintFilter(COMPLAINT_FORM_DEFAULTS)).toEqual({
      creditorId: null,
      status: 'RECEIVED,IN_PROGRESS',
      channel: null,
      deadline: null,
      protocol: null,
    });
    const f = toComplaintFilter({ creditor: ['c1'], status: '', channel: 'PROCON', assignee: 'none', deadline: 'soon', protocol: ' P-1 ' });
    expect(f).toEqual({ creditorId: 'c1', status: null, channel: 'PROCON', deadline: 'soon', protocol: 'P-1', unassigned: true });
    expect(toComplaintFilter({ ...COMPLAINT_FORM_DEFAULTS, assignee: 'me' }).assignee).toBe('me');
  });

  it('conta os filtros aplicados', () => {
    // contagem padrão da aplicação (shared/data/filter-count) sobre o padrão do formulário
    const count = (v: typeof COMPLAINT_FORM_DEFAULTS) => countActiveFilters(v, COMPLAINT_FORM_DEFAULTS);
    expect(count(COMPLAINT_FORM_DEFAULTS)).toBe(0);
    expect(count({ ...COMPLAINT_FORM_DEFAULTS, creditor: ['c'], deadline: 'overdue' })).toBe(2);
  });

  it('URL em português compatível com "Minhas pendências"', () => {
    const fromPending = readUrlValues(
      convertToParamMap({ responsavel: 'me', prazo: 'soon' }),
      COMPLAINT_URL_CODECS,
      COMPLAINT_FORM_DEFAULTS,
    );
    expect(fromPending).toEqual({ ...COMPLAINT_FORM_DEFAULTS, assignee: 'me', deadline: 'soon' });
    expect(readUrlValues(convertToParamMap({ situacao: 'todas', canal: 'XPTO' }), COMPLAINT_URL_CODECS, COMPLAINT_FORM_DEFAULTS))
      .toEqual({ ...COMPLAINT_FORM_DEFAULTS, status: '' });
    expect(urlParamsOf({ ...COMPLAINT_FORM_DEFAULTS, assignee: 'none', channel: 'BACEN' }, COMPLAINT_URL_CODECS, COMPLAINT_FORM_DEFAULTS))
      .toEqual(expect.objectContaining({ responsavel: 'nenhum', canal: 'BACEN' }));
    const params = (p: Record<string, string>) => (k: string) => p[k] ?? null;
    expect(unassignedAlias(params({ 'sem-responsavel': '1' }))).toBe(true);
    expect(unassignedAlias(params({ 'sem-responsavel': '1', responsavel: 'me' }))).toBe(false);
    expect(unassignedAlias(params({}))).toBe(false);
  });

  it('ações pela situação', () => {
    expect(primaryAction('RECEIVED')).toBe('start');
    expect(primaryAction('IN_PROGRESS')).toBe('answer');
    expect(primaryAction('ANSWERED')).toBe('close');
    expect(primaryAction('CLOSED')).toBeNull();
    expect(complaintActions('ANSWERED')).not.toContain('dueDate');
    expect(complaintActions('CLOSED')).toEqual([]);
  });

  it('evidência e período', () => {
    expect(complaintEvidenceProblem({ type: 'application/pdf', size: 100 })).toBeNull();
    expect(complaintEvidenceProblem({ type: 'audio/mpeg', size: 100 })).toContain('PDF');
    expect(complaintEvidenceProblem({ type: 'image/png', size: 11 * 1024 * 1024 })).toContain('10 MB');
    expect(monthRange('2026-02-15')).toEqual({ from: '2026-02-01', to: '2026-02-28' });
    const now = new Date(2026, 10, 8, 9, 0);
    expect(receivedInstant('2026-11-08', now)).toBeNull();
    expect(receivedInstant('2026-11-01', now)).toBe(new Date(2026, 10, 1, 12, 0).toISOString());
  });
});
