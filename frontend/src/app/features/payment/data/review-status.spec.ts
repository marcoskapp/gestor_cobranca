import { breakSuspension, breakSuspensionInfo } from '../../../../testing/payment-review-fixtures';
import { breakHoldBadge, formatLocalDate, localToday, pendingBreakState, rejectionGraceUntil } from './review-status';

describe('suspensão da quebra por pagamento em revisão', () => {
  it('estado da quebra para os pendentes: suspensa (prazo mais próximo), prazo vencido, potencial ou desconhecido', () => {
    expect(pendingBreakState(null, ['a-1'])).toEqual({ kind: 'unknown' });
    expect(pendingBreakState(breakSuspensionInfo(), ['a-1'])).toEqual({ kind: 'potential', maxDays: 7 });
    const two = breakSuspensionInfo([
      breakSuspension({ agreementId: 'a-1', suspendedUntil: '2026-11-05' }),
      breakSuspension({ id: 'bs-2', agreementId: 'a-2', suspendedUntil: '2026-11-02' }),
    ]);
    expect(pendingBreakState(two, ['a-1', 'a-2', null])).toEqual({ kind: 'suspended', until: '2026-11-02' });
    expect(pendingBreakState(two, ['a-1'])).toEqual({ kind: 'suspended', until: '2026-11-05' });
    const expired = breakSuspensionInfo([breakSuspension({ status: 'ENDED', endReason: 'DEADLINE' })]);
    expect(pendingBreakState(expired, ['a-1'])).toEqual({ kind: 'deadline', until: '2026-11-02' });
    const approved = breakSuspensionInfo([breakSuspension({ status: 'ENDED', endReason: 'APPROVED' })]);
    expect(pendingBreakState(approved, ['a-1'])).toEqual({ kind: 'potential', maxDays: 7 });
  });

  it('carência depois da recusa só enquanto não passou', () => {
    const info = breakSuspensionInfo([breakSuspension({ status: 'ENDED', endReason: 'REJECTED', graceUntil: '2026-10-29' })]);
    expect(rejectionGraceUntil(info, ['a-1'], '2026-10-29')).toBe('2026-10-29');
    expect(rejectionGraceUntil(info, ['a-1'], '2026-10-30')).toBeNull();
    expect(rejectionGraceUntil(info, ['a-2'], '2026-10-27')).toBeNull();
    expect(rejectionGraceUntil(null, ['a-1'])).toBeNull();
  });

  it('selo da fila de revisão: até quando, último dia e prazo vencido', () => {
    expect(breakHoldBadge(null)).toBeNull();
    expect(breakHoldBadge(breakSuspension(), '2026-10-30')).toEqual({ tone: 'warning', text: 'Quebra suspensa até 02/11/2026' });
    expect(breakHoldBadge(breakSuspension(), '2026-11-02')).toEqual({ tone: 'danger', text: 'Quebra suspensa só até hoje' });
    expect(breakHoldBadge(breakSuspension(), '2026-11-03')?.tone).toBe('danger');
    expect(breakHoldBadge(breakSuspension({ status: 'ENDED', endReason: 'DEADLINE' }), '2026-11-03')?.text).toBe(
      'Prazo de suspensão vencido: a quebra voltou a valer',
    );
    expect(breakHoldBadge(breakSuspension({ status: 'ENDED', endReason: 'APPROVED' }), '2026-11-03')).toBeNull();
  });

  it('datas de negócio sem conversão de fuso', () => {
    expect(formatLocalDate('2026-11-02')).toBe('02/11/2026');
    expect(localToday(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05');
  });
});
