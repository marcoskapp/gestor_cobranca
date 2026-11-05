import { adjustmentPreview, canDecide, canWithdraw } from './title-amount.rules';

describe('regras da correção de valor', () => {
  const current = [
    { component: 'PRINCIPAL' as const, balance: 1000 },
    { component: 'FINE' as const, balance: 0 },
  ];

  it('redução até o limite é aplicada já; acima ou aumento vai para aprovação', () => {
    expect(adjustmentPreview(current, { PRINCIPAL: 850 }, 1000, 200)).toMatchObject({ changed: true, delta: -150, mode: 'WITHIN_LIMIT', newOpenBalance: 850 });
    expect(adjustmentPreview(current, { PRINCIPAL: 799.99 }, 1000, 200).mode).toBe('DUAL_CONTROL');
    expect(adjustmentPreview(current, { FINE: 20 }, 1000, 200)).toMatchObject({ delta: 20, mode: 'DUAL_CONTROL' });
    expect(adjustmentPreview(current, { PRINCIPAL: 1000 }, 1000, 200).changed).toBe(false);
    // limite zero: tudo passa por aprovação
    expect(adjustmentPreview(current, { PRINCIPAL: 999 }, 1000, 0).mode).toBe('DUAL_CONTROL');
  });

  it('segregação: quem pediu não decide, só retira', () => {
    const a = { status: 'PENDING_APPROVAL' as const, requestedBy: 'u-1' };
    expect(canDecide(a, 'u-1', true)).toBe(false);
    expect(canDecide(a, 'u-2', true)).toBe(true);
    expect(canDecide(a, 'u-2', false)).toBe(false);
    expect(canWithdraw(a, 'u-1')).toBe(true);
    expect(canWithdraw({ ...a, status: 'APPLIED' }, 'u-1')).toBe(false);
  });
});
