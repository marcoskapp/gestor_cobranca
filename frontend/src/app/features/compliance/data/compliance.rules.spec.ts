import { legalBasisPeriod } from '../legal-bases/legal-basis-list';
import { effectiveFromInstant } from '../policies/contact-policy';
import { draftFrom, draftProblems, limitsText, toRuleInputs, windowText } from '../policies/policy-draft';
import { requestActions } from '../requests/request-detail/request-detail';
import { receivedInstant } from '../requests/request-form/request-form';
import { toQueueFilter } from '../requests/request-list/request-list';
import { daysUntil, dueText, evidenceProblem, restrictionScope, weekDaysText } from './compliance.labels';
import { DataSubjectRequestView, PolicyView } from './compliance.models';

function request(overrides: Partial<DataSubjectRequestView>): DataSubjectRequestView {
  return {
    id: 'q-1',
    type: 'CONFIRMATION',
    channel: 'EMAIL',
    status: 'RECEIVED',
    debtorId: null,
    requesterName: 'Ana',
    maskedDocument: null,
    maskedContact: null,
    details: null,
    receivedAt: '2026-10-01T12:00:00Z',
    dueAt: '2026-10-16T12:00:00Z',
    overdue: false,
    assignedTo: null,
    identityVerifiedAt: null,
    identityVerifiedBy: null,
    executedAt: null,
    completedAt: null,
    responseSummary: null,
    hasResultFile: false,
    resultSha256: null,
    ...overrides,
  };
}

describe('regras de tela do módulo compliance', () => {
  describe('rascunho da política de contato', () => {
    const published: PolicyView = {
      id: 'pol-1',
      creditorId: null,
      version: 3,
      effectiveFrom: '2026-10-01T11:00:00Z',
      notes: null,
      publishedAt: '2026-10-01T11:00:00Z',
      publishedBy: null,
      rules: [
        {
          channel: 'PHONE',
          inheritsWindow: false,
          windowStart: '08:00:00',
          windowEnd: '20:00:00',
          allowedDays: ['MONDAY', 'FRIDAY'],
          maxPerDay: 3,
          maxPerWeek: 10,
          minIntervalMinutes: 120,
          blockOnHolidays: true,
        },
        {
          channel: 'SMS',
          inheritsWindow: true,
          windowStart: null,
          windowEnd: null,
          allowedDays: null,
          maxPerDay: 1,
          maxPerWeek: null,
          minIntervalMinutes: null,
          blockOnHolidays: false,
        },
      ],
    };

    it('parte da versão publicada e converte só os canais incluídos', () => {
      const draft = draftFrom(published);
      expect(draft.map((r) => [r.channel, r.included])).toEqual([
        ['WHATSAPP', false],
        ['EMAIL', false],
        ['SMS', true],
        ['PHONE', true],
        ['PORTAL', false],
      ]);
      expect(toRuleInputs(draft)).toEqual([
        { channel: 'SMS', windowStart: null, windowEnd: null, allowedDays: null, maxPerDay: 1, maxPerWeek: null, minIntervalMinutes: null, blockOnHolidays: false },
        {
          channel: 'PHONE',
          windowStart: '08:00',
          windowEnd: '20:00',
          allowedDays: ['MONDAY', 'FRIDAY'],
          maxPerDay: 3,
          maxPerWeek: 10,
          minIntervalMinutes: 120,
          blockOnHolidays: true,
        },
      ]);
    });

    it('sem versão publicada sugere os canais de contato ativo com a janela da assessoria', () => {
      const draft = draftFrom(null);
      expect(draft.filter((r) => r.included).map((r) => r.channel)).toEqual(['WHATSAPP', 'EMAIL', 'SMS', 'PHONE']);
      expect(draft.every((r) => r.inheritWindow)).toBe(true);
    });

    it('aponta problemas antes de publicar', () => {
      const draft = draftFrom(published);
      expect(draftProblems(draft)).toEqual([]);
      expect(draftProblems(draft.map((r) => ({ ...r, included: false })))).toEqual(['Inclua a regra de ao menos um canal.']);
      const phone = draft.map((r) => (r.channel === 'PHONE' ? { ...r, allowedDays: [], maxPerDay: 5, maxPerWeek: 2 } : r));
      expect(draftProblems(phone)).toEqual([
        'Telefone: informe início, fim e ao menos um dia da janela.',
        'Telefone: o limite semanal não pode ser menor que o diário.',
      ]);
    });

    it('descreve janela, dias e limites em linguagem simples', () => {
      expect(windowText('08:00:00', '20:00:00')).toBe('08:00–20:00');
      expect(windowText('00:00', '00:00')).toBe('Dia inteiro');
      expect(windowText('22:00', '06:00')).toBe('22:00–06:00 (atravessa a meia-noite)');
      expect(windowText(null, null)).toBe('—');
      expect(weekDaysText(['MONDAY', 'WEDNESDAY'])).toBe('Seg, Qua');
      expect(limitsText({ maxPerDay: 2, maxPerWeek: null, minIntervalMinutes: 60 })).toBe('2/dia · 60 min entre tentativas');
      expect(limitsText({ maxPerDay: null, maxPerWeek: null, minIntervalMinutes: null })).toBe('Sem limite');
    });

    it('vigência sem data é imediata; com data usa a hora local escolhida', () => {
      expect(effectiveFromInstant(null, '08:00')).toBeNull();
      expect(effectiveFromInstant(new Date(2026, 10, 3), '14:30')).toBe(new Date(2026, 10, 3, 14, 30).toISOString());
    });
  });

  describe('solicitações de titulares', () => {
    it('libera apenas a próxima ação do fluxo', () => {
      expect(requestActions(request({ status: 'RECEIVED' }))).toMatchObject({ verify: true, start: false, execute: false, complete: false, reject: true });
      expect(requestActions(request({ status: 'IDENTITY_VERIFIED' }))).toMatchObject({ verify: false, start: true, reject: true });
      expect(requestActions(request({ status: 'COMPLETED' }))).toMatchObject({ reject: false, assign: false, complete: false });
    });

    it('tipos executáveis exigem a execução (e o arquivo, quando houver) antes de concluir', () => {
      const anonymization = requestActions(request({ type: 'ANONYMIZATION', status: 'IN_PROGRESS' }));
      expect(anonymization).toMatchObject({ execute: true, completeBlocker: 'Execute a ação do pedido antes de concluir.' });
      const access = requestActions(request({ type: 'ACCESS', status: 'IN_PROGRESS', executedAt: '2026-10-02T00:00:00Z' }));
      expect(access).toMatchObject({ execute: false, completeBlocker: 'O arquivo com os dados do titular ainda não foi gerado.' });
      const ready = requestActions(request({ type: 'ACCESS', status: 'IN_PROGRESS', executedAt: 'x', hasResultFile: true }));
      expect(ready.completeBlocker).toBeNull();
      expect(requestActions(request({ type: 'CORRECTION', status: 'IN_PROGRESS' }))).toMatchObject({ execute: false, completeBlocker: null });
    });

    it('prazo em dias corridos com atraso destacado', () => {
      const now = new Date(2026, 9, 23, 9);
      expect(daysUntil(new Date(2026, 9, 26, 18).toISOString(), now)).toBe(3);
      expect(daysUntil(new Date(2026, 9, 20, 1).toISOString(), now)).toBe(-3);
      expect(dueText(3, false)).toBe('Faltam 3 dias');
      expect(dueText(0, false)).toBe('Vence hoje');
      expect(dueText(-3, true)).toBe('Em atraso há 3 dias');
      expect(dueText(1, true)).toBe('Em atraso');
    });

    it('documento só entra na busca quando completo e válido (vai no corpo, nunca na URL)', () => {
      expect(toQueueFilter({ status: '', type: '', overdue: false, document: '529.982.247-25' })).toMatchObject({ document: '52998224725', incomplete: false });
      expect(toQueueFilter({ status: '', type: '', overdue: true, document: '529.98' })).toMatchObject({ document: null, incomplete: true, overdue: true });
      expect(toQueueFilter({ status: 'RECEIVED', type: 'ACCESS', overdue: false, document: '' })).toEqual({
        status: 'RECEIVED',
        type: 'ACCESS',
        overdue: null,
        document: null,
        incomplete: false,
      });
    });

    it('recebimento hoje = agora; dias anteriores ao meio-dia local', () => {
      const now = new Date(2026, 9, 23, 15);
      expect(receivedInstant(null, now)).toBeNull();
      expect(receivedInstant(new Date(2026, 9, 23), now)).toBeNull();
      expect(receivedInstant(new Date(2026, 9, 20), now)).toBe(new Date(2026, 9, 20, 12).toISOString());
    });
  });

  it('valida a evidência antes do envio', () => {
    expect(evidenceProblem({ type: 'application/pdf', size: 1024 })).toBeNull();
    expect(evidenceProblem({ type: 'application/pdf', size: 0 })).toBe('O arquivo está vazio.');
    expect(evidenceProblem({ type: 'application/pdf', size: 21 * 1024 * 1024 })).toBe('O arquivo excede 20 MB.');
    expect(evidenceProblem({ type: 'application/zip', size: 10 })).toContain('Tipo de arquivo não aceito');
  });

  it('descreve o alcance da restrição', () => {
    const names = (id: string) => (id === 'c-1' ? 'Horizonte' : '?');
    expect(restrictionScope({ creditorId: null, channel: null, contactMasked: null }, names)).toBe('Todos os credores · Todos os canais');
    expect(restrictionScope({ creditorId: 'c-1', channel: 'WHATSAPP', contactMasked: '(11) 9****-4321' }, names)).toBe(
      'Horizonte · WhatsApp · (11) 9****-4321',
    );
  });

  it('vigência da base legal', () => {
    expect(legalBasisPeriod({ validFrom: '2026-01-01', validUntil: null }, '2026-10-23')).toBe('CURRENT');
    expect(legalBasisPeriod({ validFrom: '2026-11-01', validUntil: null }, '2026-10-23')).toBe('FUTURE');
    expect(legalBasisPeriod({ validFrom: '2024-01-01', validUntil: '2025-12-31' }, '2026-10-23')).toBe('ENDED');
    expect(legalBasisPeriod({ validFrom: '2024-01-01', validUntil: '2026-10-23' }, '2026-10-23')).toBe('CURRENT');
  });
});
