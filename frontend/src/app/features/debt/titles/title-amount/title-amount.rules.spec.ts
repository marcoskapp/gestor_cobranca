import { adjustmentPreview, canConfirmThreshold, canDecide, canWithdraw } from './title-amount.rules';

describe('regras da correção de valor', () => {
  const current = [
    { component: 'PRINCIPAL' as const, balance: 1000 },
    { component: 'FINE' as const, balance: 0 },
  ];

  it('redução até o limite é aplicada já; acima ou aumento vai para aprovação', () => {
    expect(adjustmentPreview(current, { PRINCIPAL: 850 }, 1000, 200, 0)).toMatchObject({ changed: true, delta: -150, mode: 'WITHIN_LIMIT', newOpenBalance: 850 });
    expect(adjustmentPreview(current, { PRINCIPAL: 799.99 }, 1000, 200, 0).mode).toBe('DUAL_CONTROL');
    expect(adjustmentPreview(current, { FINE: 20 }, 1000, 200, 0)).toMatchObject({ delta: 20, mode: 'DUAL_CONTROL' });
    expect(adjustmentPreview(current, { PRINCIPAL: 1000 }, 1000, 200, 0).changed).toBe(false);
    // limite zero: tudo passa por aprovação
    expect(adjustmentPreview(current, { PRINCIPAL: 999 }, 1000, 0, 0).mode).toBe('DUAL_CONTROL');
  });

  it('dentro do limite só a redução pura: troca entre componentes ou soma das reduções acima do limite vai para aprovação', () => {
    const three = [
      { component: 'PRINCIPAL' as const, balance: 10000 },
      { component: 'FINE' as const, balance: 300 },
      { component: 'HONORARIUM' as const, balance: 0 },
    ];
    // principal para baixo e honorários para cima: líquido −0,01, mas um componente sobe
    expect(adjustmentPreview(three, { PRINCIPAL: 1000, HONORARIUM: 8999.99 }, 10300, 200, 0)).toMatchObject({ delta: -0.01, mode: 'DUAL_CONTROL' });
    // aumento de um componente com redução maior em outro
    expect(adjustmentPreview(three, { PRINCIPAL: 9850, FINE: 350 }, 10300, 200, 0)).toMatchObject({ delta: -100, mode: 'DUAL_CONTROL' });
    // duas reduções de 150 (soma 300 > 200)
    expect(adjustmentPreview(three, { PRINCIPAL: 9850, FINE: 150 }, 10300, 200, 0).mode).toBe('DUAL_CONTROL');
    // duas reduções cuja soma cabe no limite
    expect(adjustmentPreview(three, { PRINCIPAL: 9900, FINE: 200 }, 10300, 200, 0).mode).toBe('WITHIN_LIMIT');
  });

  it('limite acumulado por título: reduções recentes sem segunda pessoa somam com a nova', () => {
    // 150 já aplicados na janela: 50 ainda cabem no limite de 200
    expect(adjustmentPreview(current, { PRINCIPAL: 950 }, 1000, 200, 150)).toMatchObject({ mode: 'WITHIN_LIMIT', cumulative: false });
    // 100 caberiam sozinhos, mas 150 + 100 passa do limite: aprovação por causa do acumulado
    expect(adjustmentPreview(current, { PRINCIPAL: 900 }, 1000, 200, 150)).toMatchObject({ mode: 'DUAL_CONTROL', cumulative: true });
    // acima do limite sozinha: não é o acumulado que manda
    expect(adjustmentPreview(current, { PRINCIPAL: 700 }, 1000, 200, 150)).toMatchObject({ mode: 'DUAL_CONTROL', cumulative: false });
    // aumento nunca é marcado como acumulado
    expect(adjustmentPreview(current, { FINE: 20 }, 1000, 200, 150).cumulative).toBe(false);
  });

  it('aumento do limite: confirma outra pessoa que aprova e vê todos os credores, enquanto a proposta não vence', () => {
    const proposal = { proposedThreshold: 5000, proposedBy: 'u-1', proposalExpired: false };
    expect(canConfirmThreshold(proposal, 'u-1', true)).toBe(false);
    expect(canConfirmThreshold(proposal, 'u-2', true)).toBe(true);
    expect(canConfirmThreshold(proposal, 'u-2', false)).toBe(false);
    expect(canConfirmThreshold({ ...proposal, proposalExpired: true }, 'u-2', true)).toBe(false);
    expect(canConfirmThreshold({ proposedThreshold: null, proposedBy: null, proposalExpired: false }, 'u-2', true)).toBe(false);
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
