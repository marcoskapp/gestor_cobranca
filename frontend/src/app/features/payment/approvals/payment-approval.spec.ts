import { ReviewAssessmentView } from '../data/payment.models';
import { paymentReviewReasons } from './payment-approval';
import { adjustmentApprovalReasons } from '../../debt/titles/title-amount/title-amount-approval';

const assessment = (over: Partial<ReviewAssessmentView> = {}): ReviewAssessmentView => ({
  paymentId: 'pay-1',
  reason: 'AMOUNT',
  limit: 500,
  consideredAmount: 900,
  excess: 400,
  windowHours: 24,
  currentPerPaymentLimit: 500,
  ownPayment: false,
  ...over,
});

describe('quanto passa do limite (pagamentos e correções de valor)', () => {
  it('pagamento sozinho, soma do devedor e soma de quem informou', () => {
    const [amount] = paymentReviewReasons({ amount: 900 }, assessment());
    expect(amount.label).toBe('Valor do pagamento');
    expect(amount.excess).toContain('400,00');
    expect(amount.excess).toContain('(80% acima)');
    const [destination] = paymentReviewReasons({ amount: 450 }, assessment({ reason: 'DESTINATION_CUMULATIVE', consideredAmount: 900 }));
    expect(destination.label).toBe('Soma do devedor neste credor');
    expect(destination.hint).toContain('últimas 24 h');
    expect(paymentReviewReasons({ amount: 450 }, assessment({ reason: 'INFORMER_CUMULATIVE' }))[0].label).toBe('Soma de quem informou');
  });

  it('pagamento antigo sem motivo gravado: compara com o limite atual por pagamento', () => {
    const [row] = paymentReviewReasons({ amount: 700 }, assessment({ reason: null, limit: null, consideredAmount: null, excess: null }));
    expect(row.hint).toContain('limite atual');
    expect(row.excess).toContain('200,00');
    expect(paymentReviewReasons({ amount: 300 }, assessment({ reason: null, limit: null, consideredAmount: null, excess: null }))[0].excess).toBeNull();
  });

  it('correção: aumento sempre passa por outra pessoa; redução acima do limite e somada às recentes', () => {
    const base = {
      adjustment: {} as never,
      increases: 0,
      decreases: 300,
      recentReductions: 0,
      threshold: 200,
      excess: 100,
      cumulativeWindowDays: 30,
      ownRequest: false,
    };
    const alone = adjustmentApprovalReasons({ ...base, reason: 'REDUCTION_ABOVE_LIMIT' });
    expect(alone).toHaveLength(1);
    expect(alone[0].label).toBe('Redução');
    expect(alone[0].excess).toContain('(50% acima)');
    const cumulative = adjustmentApprovalReasons({ ...base, reason: 'CUMULATIVE_ABOVE_LIMIT', decreases: 60, recentReductions: 150, excess: 10 });
    expect(cumulative[0].label).toBe('Reduções no título');
    expect(cumulative[0].hint).toContain('últimos 30 dias');
    const increase = adjustmentApprovalReasons({ ...base, reason: 'INCREASE', increases: 50, decreases: 0, excess: 0 });
    expect(increase).toHaveLength(1);
    expect(increase[0].label).toBe('Aumento de componente');
    expect(increase[0].excess).toBeNull();
  });
});
