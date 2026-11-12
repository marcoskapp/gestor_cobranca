import { agendaFilter } from '../agenda/agenda';
import { toCaseFilter } from '../cases/case-list';
import { distributionText } from '../queues/distribution-dialogs';
import { rangeValidator } from '../queues/queue-form';
import { criteriaText, rangeText } from '../queues/queue-list';
import { contactsForChannel } from '../station/interaction-panel';
import { tabulationConflict, tabulationEffects } from '../tabulations/tabulation-list';
import { FormControl, FormGroup } from '@angular/forms';
import { caseActions, eligibilityText } from './collection.labels';
import { ChannelAvailability, QueueView, SheetContact, TabulationView, TaskView } from './collection.models';
import {
  addDays,
  canWorkCase,
  daysBetween,
  formatDuration,
  followUpText,
  formatLocalDateTime,
  interactionProblems,
  isPastLocal,
  nextActionText,
  availabilityState,
  taskLate,
  toLocalDateTime,
  zonedNow,
} from './collection.rules';

const tab = (overrides: Partial<TabulationView> = {}): TabulationView => ({
  id: 'tb-1',
  code: 'CPC',
  name: 'Contato com o devedor',
  effectiveContact: true,
  promise: false,
  callback: false,
  requiresNote: false,
  followUp: 'NONE',
  disputeType: null,
  displayOrder: 1,
  active: true,
  version: 0,
  ...overrides,
});

const now = { date: '2026-10-23', time: '10:00', weekDay: 5 };

describe('regras de tela do módulo collection', () => {
  describe('fuso de operação', () => {
    it('converte o instante para data, hora e dia da semana de Brasília', () => {
      // 13:30 UTC = 10:30 em São Paulo (UTC−3), sexta-feira
      expect(zonedNow(new Date('2026-10-23T13:30:00Z'))).toEqual({ date: '2026-10-23', time: '10:30', weekDay: 5 });
      // 02:00 UTC de sábado ainda é sexta à noite em São Paulo
      expect(zonedNow(new Date('2026-10-24T02:00:00Z'))).toEqual({ date: '2026-10-23', time: '23:00', weekDay: 5 });
    });

    it('LocalDateTime do retorno sem conversão de fuso', () => {
      expect(toLocalDateTime(new Date(2026, 9, 24), '14:30')).toBe('2026-10-24T14:30:00');
      expect(toLocalDateTime(new Date(2026, 9, 24), '')).toBeNull();
      expect(toLocalDateTime('2026-10-24', '14:30')).toBe('2026-10-24T14:30:00');
      expect(toLocalDateTime('24/10/2026', '14:30')).toBeNull();
      expect(formatLocalDateTime('2026-10-24T14:30:00')).toBe('24/10/2026 14:30');
      expect(formatLocalDateTime(null)).toBe('—');
      expect(isPastLocal('2026-10-23T09:59', now)).toBe(true);
      expect(isPastLocal('2026-10-23T10:01:00', now)).toBe(false);
    });

    it('aritmética de LocalDate', () => {
      expect(addDays('2026-10-30', 3)).toBe('2026-11-02');
      expect(daysBetween('2026-10-23', '2026-11-22')).toBe(30);
      expect(formatDuration(65)).toBe('01:05');
      expect(formatDuration(3725)).toBe('1:02:05');
    });
  });

  describe('posso contatar agora?', () => {
    const at = new Date('2026-10-23T13:00:00Z'); // 10:00 em Brasília
    const ch = (overrides: Partial<ChannelAvailability> = {}): ChannelAvailability => ({
      channel: 'PHONE',
      allowed: false,
      reasons: [],
      nextAllowedAt: null,
      contactId: null,
      ...overrides,
    });

    it('permitido, aguardar com o próximo horário (hoje ou outro dia), restrito e suspenso', () => {
      expect(availabilityState(ch({ allowed: true }), true, at).kind).toBe('allowed');
      const today = availabilityState(ch({ reasons: ['MIN_INTERVAL'], nextAllowedAt: '2026-10-23T17:30:00Z' }), true, at);
      expect(today).toEqual({
        kind: 'wait',
        label: 'Aguardar',
        detail: 'a partir de 14:30',
        text: 'Intervalo mínimo entre tentativas — permitido a partir de 14:30',
      });
      const tomorrow = availabilityState(ch({ reasons: ['OUTSIDE_WINDOW', 'HOLIDAY'], nextAllowedAt: '2026-10-26T11:00:00Z' }), true, at);
      expect(tomorrow.detail).toBe('a partir de 26/10 08:00');
      expect(tomorrow.text).toContain('Fora do horário permitido · Feriado');
      const blocked = availabilityState(ch({ reasons: ['RESTRICTED_CHANNEL'] }), true, at);
      expect(blocked.kind).toBe('blocked');
      expect(blocked.text).toBe('Restrição no canal');
      expect(availabilityState(ch(), false, at).kind).toBe('suspended');
      // motivo desconhecido aparece como veio
      expect(availabilityState(ch({ reasons: ['NOVO'] }), true, at).text).toBe('NOVO');
    });
  });

  describe('registro de interação', () => {
    const limits = { openBalance: 1000, today: '2026-10-23', promiseMaxDays: 30 };
    const draft = { tabulation: tab(), note: '', promiseAmount: null, promiseDate: null, callbackAt: null };

    it('exige tabulação e observação quando a flag pede', () => {
      expect(interactionProblems({ ...draft, tabulation: null }, limits, now)).toEqual(['Escolha a tabulação (resultado do contato).']);
      expect(interactionProblems({ ...draft, tabulation: tab({ requiresNote: true }) }, limits, now)[0]).toContain('exige observação');
      expect(interactionProblems({ ...draft, tabulation: tab({ requiresNote: true }), note: 'ok' }, limits, now)).toEqual([]);
    });

    it('promessa: valor até o saldo e data entre hoje e o prazo máximo', () => {
      const promise = tab({ promise: true });
      expect(interactionProblems({ ...draft, tabulation: promise }, limits, now)).toEqual(['Informe o valor prometido.', 'Informe a data prometida.']);
      expect(interactionProblems({ ...draft, tabulation: promise, promiseAmount: 1500, promiseDate: '2026-10-30' }, limits, now)).toEqual([
        'O valor prometido não pode passar do saldo em aberto.',
      ]);
      expect(interactionProblems({ ...draft, tabulation: promise, promiseAmount: 500, promiseDate: '2026-10-22' }, limits, now)).toEqual([
        'A data prometida não pode estar no passado.',
      ]);
      expect(interactionProblems({ ...draft, tabulation: promise, promiseAmount: 500, promiseDate: '2026-11-23' }, limits, now)).toEqual([
        'A data prometida deve estar em até 30 dias.',
      ]);
      expect(interactionProblems({ ...draft, tabulation: promise, promiseAmount: 500, promiseDate: '2026-11-22' }, limits, now)).toEqual([]);
    });

    it('retorno exige data e hora futuras', () => {
      const callback = tab({ callback: true });
      expect(interactionProblems({ ...draft, tabulation: callback }, limits, now)).toEqual(['Informe data e hora do retorno.']);
      expect(interactionProblems({ ...draft, tabulation: callback, callbackAt: '2026-10-23T09:00:00' }, limits, now)).toEqual([
        'O retorno precisa ser em data e hora futuras.',
      ]);
      expect(interactionProblems({ ...draft, tabulation: callback, callbackAt: '2026-10-23T15:00:00' }, limits, now)).toEqual([]);
    });

    it('ação automática: contestação exige o tipo e invalidar exige o contato usado', () => {
      const dispute = tab({ followUp: 'OPEN_DISPUTE', requiresNote: true });
      expect(interactionProblems({ ...draft, tabulation: dispute, note: 'Não reconhece' }, limits, now)).toEqual(['Escolha o tipo da contestação.']);
      expect(interactionProblems({ ...draft, tabulation: dispute, note: 'Não reconhece', disputeType: 'FRAUD' }, limits, now)).toEqual([]);
      const wrong = tab({ followUp: 'INVALIDATE_CONTACT', effectiveContact: false, requiresNote: true });
      expect(interactionProblems({ ...draft, tabulation: wrong, note: 'Outra pessoa' }, limits, now)).toEqual([
        'Informe o contato usado: ele será marcado como inválido.',
      ]);
      expect(interactionProblems({ ...draft, tabulation: wrong, note: 'Outra pessoa', contactId: 'c-1' }, limits, now)).toEqual([]);
      const deceased = tab({ followUp: 'REPORT_DECEASED', effectiveContact: false, requiresNote: true });
      expect(interactionProblems({ ...draft, tabulation: deceased, note: 'Informado pela filha' }, limits, now)).toEqual([]);
    });

    it('resultado da ação automática na mensagem de sucesso', () => {
      expect(followUpText(null)).toBeNull();
      expect(followUpText({ action: 'OPEN_DISPUTE', outcome: 'DONE', referenceType: 'dispute', referenceId: 'd-1' })).toContain('contestação aberta');
      expect(followUpText({ action: 'OPEN_DISPUTE', outcome: 'ALREADY_IN_EFFECT', referenceType: 'dispute', referenceId: 'd-1' })).toContain('já tem contestação');
      expect(followUpText({ action: 'OPEN_DISPUTE', outcome: 'NO_TITLES', referenceType: null, referenceId: null })).toContain('sem títulos');
      expect(followUpText({ action: 'REPORT_DECEASED', outcome: 'DONE', referenceType: 'contact_restriction', referenceId: 'r-1' })).toContain('bloqueado');
      expect(followUpText({ action: 'INVALIDATE_CONTACT', outcome: 'ALREADY_IN_EFFECT', referenceType: 'debtor_contact', referenceId: 'c-1' })).toContain('já estava inválido');
    });

    it('catálogo: regras da ação automática iguais às do servidor', () => {
      const base = { effectiveContact: true, promise: false, callback: false, requiresNote: true };
      expect(tabulationConflict({ ...base, followUp: 'OPEN_DISPUTE' })).toBeNull();
      expect(tabulationConflict({ ...base, followUp: 'OPEN_DISPUTE', effectiveContact: false })).toContain('contato efetivo');
      expect(tabulationConflict({ ...base, followUp: 'REPORT_DECEASED', requiresNote: false })).toContain('exige observação');
      expect(tabulationConflict({ ...base, followUp: 'INVALIDATE_CONTACT', callback: true })).toContain('promessa nem retorno');
      expect(tabulationConflict({ ...base, followUp: 'NONE', promise: true, effectiveContact: false })).toBe('Promessa exige contato efetivo.');
      expect(tabulationEffects({ ...base, followUp: 'OPEN_DISPUTE', disputeType: 'ALREADY_PAID' })).toContain(
        'Ação automática: abrir contestação (já pagou)',
      );
    });

    it('contatos do canal: telefone para voz/SMS, só WhatsApp para WhatsApp, sem inválidos', () => {
      const c = (id: string, overrides: Partial<SheetContact>): SheetContact => ({
        contactId: id,
        type: 'PHONE',
        maskedValue: '(11) 9****-0000',
        label: 'MOBILE',
        whatsapp: false,
        status: 'VALID',
        priority: 2,
        receipt: null,
        ...overrides,
      });
      const contacts = [c('a', { priority: 3 }), c('b', { whatsapp: true, priority: 1 }), c('c', { status: 'INVALID' }), c('d', { type: 'EMAIL' })];
      expect(contactsForChannel(contacts, 'PHONE').map((x) => x.contactId)).toEqual(['b', 'a']);
      expect(contactsForChannel(contacts, 'WHATSAPP').map((x) => x.contactId)).toEqual(['b']);
      expect(contactsForChannel(contacts, 'EMAIL').map((x) => x.contactId)).toEqual(['d']);
      expect(contactsForChannel(contacts, 'LETTER')).toEqual([]);
    });
  });

  describe('caso e agenda', () => {
    it('quem pode trabalhar o caso', () => {
      expect(canWorkCase({ assigneeUserId: null, status: 'OPEN' }, 'u-1', false)).toBe(true);
      expect(canWorkCase({ assigneeUserId: 'u-1', status: 'OPEN' }, 'u-1', false)).toBe(true);
      expect(canWorkCase({ assigneeUserId: 'u-2', status: 'OPEN' }, 'u-1', false)).toBe(false);
      expect(canWorkCase({ assigneeUserId: 'u-2', status: 'OPEN' }, 'u-1', true)).toBe(true);
      expect(canWorkCase({ assigneeUserId: null, status: 'CLOSED' }, 'u-1', true)).toBe(false);
    });

    it('ações de ciclo de vida pela situação', () => {
      expect(caseActions('OPEN')).toEqual(['suspend', 'legal', 'close']);
      expect(caseActions('SUSPENDED')).toEqual(['resume', 'legal', 'close']);
      expect(caseActions('LEGAL')).toEqual(['legal-return', 'close']);
      expect(caseActions('CLOSED')).toEqual([]);
      expect(eligibilityText('DISPUTE_OPEN')).toContain('Contestação');
      expect(eligibilityText('X')).toBe('X');
    });

    it('próxima ação e retorno atrasado', () => {
      const ref = new Date('2026-10-23T13:00:00Z');
      expect(nextActionText(null, ref)).toBeNull();
      expect(nextActionText('2026-10-23T12:00:00Z', ref)).toBe('Próxima ação vencida');
      expect(nextActionText('2026-10-24T17:00:00Z', ref)).toBe('Próxima ação 24/10 14:00');
      const task = { status: 'PENDING', dueAtLocal: '2026-10-23T09:30:00' } as TaskView;
      expect(taskLate(task, now)).toBe(true);
      expect(taskLate({ ...task, dueAtLocal: '2026-10-23T11:00:00' }, now)).toBe(false);
      expect(taskLate({ ...task, status: 'MISSED', dueAtLocal: '2026-10-24T11:00:00' }, now)).toBe(true);
    });

    it('período da agenda', () => {
      const base = { from: null, to: null, status: '', user: [] as string[] };
      expect(agendaFilter({ ...base, period: 'today' }, '2026-10-23')).toEqual({ userId: null, from: '2026-10-23', to: '2026-10-23', status: null });
      expect(agendaFilter({ ...base, period: 'week', user: ['u-9'] }, '2026-10-23')).toEqual({ userId: 'u-9', from: '2026-10-23', to: '2026-10-29', status: null });
      expect(agendaFilter({ ...base, period: 'late' }, '2026-10-23')).toEqual({ userId: null, from: '2026-09-23', to: '2026-10-23', status: 'MISSED' });
      expect(agendaFilter({ ...base, period: 'custom', from: '2026-10-01', status: 'DONE' }, '2026-10-23')).toEqual({
        userId: null,
        from: '2026-10-01',
        to: '2026-10-01',
        status: 'DONE',
      });
    });
  });

  describe('gestão', () => {
    it('filtros de casos e contestações', () => {
      const v = { creditor: [], portfolio: [], debtor: [], status: 'ACTIVE', queueId: '', assignee: 'me', user: [], ownerType: '', eligible: 'false' };
      expect(toCaseFilter(v)).toEqual({
        creditorId: null,
        portfolioId: null,
        debtorId: null,
        status: 'OPEN,IN_NEGOTIATION,PROMISE,AGREEMENT,DISPUTED,SUSPENDED,LEGAL',
        queueId: null,
        assignee: 'me',
        ownerType: null,
        eligible: false,
      });
      expect(toCaseFilter({ ...v, status: '', assignee: 'user', user: ['u-3'], eligible: '' })).toMatchObject({ status: null, assignee: 'u-3', eligible: null });
      expect(toCaseFilter({ ...v, debtor: ['d-1'] }).debtorId).toBe('d-1');
      expect(toCaseFilter({ ...v, portfolio: ['p-1'] }).portfolioId).toBe('p-1');
    });

    it('critérios da fila em texto e faixas mínimo ≤ máximo', () => {
      expect(rangeText(null, null, String)).toBeNull();
      expect(rangeText(31, 90, (v) => `${v} dias`)).toBe('31 dias a 90 dias');
      expect(rangeText(null, 90, (v) => `${v} dias`)).toBe('até 90 dias');
      const q = { minDaysOverdue: 91, maxDaysOverdue: null, minBalance: null, maxBalance: 500, minScore: null, maxScore: null } as QueueView;
      expect(criteriaText(q)).toBe('a partir de 91 dias · até R$\u00a0500,00');
      expect(criteriaText({ ...q, minDaysOverdue: null, maxBalance: null })).toBe('Qualquer atraso, saldo e score');
      expect(criteriaText({ ...q, minDaysOverdue: null, maxBalance: null, personType: 'COMPANY', newContactOnly: true })).toBe(
        'Pessoa jurídica · com contato novo',
      );
      const group = new FormGroup({ a: new FormControl<number | null>(10), b: new FormControl<number | null>(5) }, { validators: rangeValidator('a', 'b') });
      expect(group.errors).toEqual({ range: 'a-b' });
      group.controls.b.setValue(null);
      expect(group.errors).toBeNull();
    });

    it('efeitos da tabulação e resultado da distribuição', () => {
      expect(tabulationEffects(tab({ promise: true, requiresNote: true }))).toEqual([
        'Contato efetivo (leva o caso a "Em negociação")',
        'Exige promessa (valor e data)',
        'Observação obrigatória',
      ]);
      expect(distributionText({ assigned: 1, unassigned: 0, assignments: [] })).toBe('1 caso distribuído.');
      expect(distributionText({ assigned: 8, unassigned: 2, assignments: [] })).toContain('2 sem operador possível');
    });
  });
});
