import { adjustmentShortcut, canWorkDispute, disputeActions, dueTone, formFromQuery, nearestDue, protocolOf, suggestedAdjustment, toDisputeFilter, unresolvedPlaceholders } from './dispute.rules';

describe('regras de tela das contestações', () => {
  const base = { internalDueDate: '2026-11-10', responseDueDate: '2026-11-15', overdue: false, status: 'OPEN' as const };

  it('ações pela situação', () => {
    expect(disputeActions('OPEN')).toEqual(['review', 'resolve', 'withdraw']);
    expect(disputeActions('UNDER_REVIEW')).toEqual(['resolve', 'withdraw']);
    expect(disputeActions('RESOLVED')).toEqual([]);
  });

  it('ajuste sugerido pelo tipo e resultado (espelha o servidor)', () => {
    expect(suggestedAdjustment('ALREADY_PAID', 'UPHELD')).toBe('SETTLE_BY_PAYMENT');
    expect(suggestedAdjustment('WRONG_AMOUNT', 'PARTIALLY_UPHELD')).toBe('ADJUST_AMOUNT');
    expect(suggestedAdjustment('FRAUD', 'UPHELD')).toBe('CANCEL_TITLES');
    expect(suggestedAdjustment('FRAUD', 'PARTIALLY_UPHELD')).toBe('ADJUST_AMOUNT');
    expect(suggestedAdjustment('FRAUD', 'REJECTED')).toBeNull();
    expect(suggestedAdjustment('OTHER', '')).toBeNull();
  });

  it('prazo mais próximo e destaque', () => {
    expect(nearestDue(base)).toBe('2026-11-10');
    expect(dueTone(base, '2026-11-01')).toBe('ok');
    expect(dueTone(base, '2026-11-09')).toBe('soon');
    expect(dueTone(base, '2026-11-11')).toBe('late');
    expect(dueTone({ ...base, overdue: true }, '2026-11-01')).toBe('late');
    expect(dueTone({ ...base, status: 'RESOLVED' }, '2026-11-20')).toBe('ok');
  });

  it('quem trabalha a contestação', () => {
    expect(canWorkDispute({ assigneeId: 'u1' }, 'u2', true, false)).toBe(true);
    expect(canWorkDispute({ assigneeId: 'u1' }, 'u1', false, true)).toBe(true);
    expect(canWorkDispute({ assigneeId: null }, 'u2', false, true)).toBe(true);
    expect(canWorkDispute({ assigneeId: 'u1' }, 'u2', false, true)).toBe(false);
    expect(canWorkDispute({ assigneeId: null }, 'u2', false, false)).toBe(false);
  });

  it('filtros da tela e da URL', () => {
    expect(toDisputeFilter({ creditor: ['c-1'], status: 'PENDING', overdue: true })).toEqual({ creditorId: 'c-1', status: 'OPEN,UNDER_REVIEW', overdue: true });
    expect(
      toDisputeFilter({ creditor: [], status: '', type: 'FRAUD', assignee: 'none', due: 'week', awaiting: true, noticePending: false, adjustmentPending: true }, '2026-11-05'),
    ).toEqual({ creditorId: null, status: null, overdue: null, type: 'FRAUD', unassigned: true, dueUntil: '2026-11-12', awaitingCreditor: true, adjustmentPending: true });
    expect(toDisputeFilter({ creditor: [], status: 'PENDING', assignee: 'supervision' }).supervision).toBe(true);
    expect(toDisputeFilter({ creditor: [], status: 'PENDING', assignee: 'me' }).assignee).toBe('me');
    const q = (map: Record<string, string>) => ({ get: (k: string) => map[k] ?? null });
    expect(formFromQuery(q({ responsavel: 'me' }))).toMatchObject({ assignee: 'me', status: 'PENDING' });
    expect(formFromQuery(q({ fila: 'supervisao' })).assignee).toBe('supervision');
    expect(formFromQuery(q({ resposta: 'pendente' }))).toMatchObject({ status: 'RESOLVED', noticePending: true });
    expect(formFromQuery(q({ ajuste: 'pendente', vencidas: 'true' }))).toMatchObject({ adjustmentPending: true, overdue: true });
  });

  it('atalho do ajuste leva ao fluxo existente já preenchido', () => {
    expect(adjustmentShortcut('CANCEL_TITLES', ['t1', 't2'], 'AB12CD34', 'd-1')).toEqual({
      kind: 'title',
      titleId: 't1',
      queryParams: { acao: 'cancelar', motivo: 'Contestação AB12CD34 procedente' },
    });
    expect(adjustmentShortcut('SETTLE_BY_PAYMENT', ['t1'], 'X', 'd-1')).toEqual({ kind: 'payment', titleIds: ['t1'] });
    expect(adjustmentShortcut('ADJUST_AMOUNT', ['t9'], 'X', 'd-1')).toEqual({
      kind: 'title',
      titleId: 't9',
      queryParams: { acao: 'corrigir-valor', contestacao: 'd-1', motivo: 'Contestação X procedente' },
    });
    expect(adjustmentShortcut('ADJUST_AMOUNT', [], 'X', 'd-1')).toBeNull();
  });

  it('protocolo e variáveis por preencher', () => {
    expect(protocolOf('0a1b2c3d-0000-0000-0000-000000000000')).toBe('0A1B2C3D');
    expect(unresolvedPlaceholders('Ver {{link_evidencias}} até {{validade_link}}')).toEqual([]);
    expect(unresolvedPlaceholders('Olá {{credor}} {{ titulos }}')).toEqual(['credor', 'titulos']);
  });
});
