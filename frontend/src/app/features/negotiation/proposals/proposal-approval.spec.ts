import { proposal } from '../../../../testing/negotiation-fixtures';
import { ApprovalAssessmentView } from '../data/agreement.models';
import { authorityGapReasons, deadlineText, discountShare, unmeasuredViolations } from './proposal-approval';

const assessment = (over: Partial<ApprovalAssessmentView> = {}): ApprovalAssessmentView => ({
  proposalId: 'p-1',
  proposerProfileCode: 'JUNIOR',
  proposerProfileName: 'Negociador júnior',
  approverProfileCode: 'SUPERVISOR',
  approverProfileName: 'Supervisor',
  approverCanApprove: true,
  withinApproverAuthority: true,
  ownProposal: false,
  discountPercent: 10,
  gaps: [],
  ...over,
});

describe('proposal-approval', () => {
  it('quanto passa da alçada em valor e %, com a alçada de quem decide', () => {
    const rows = authorityGapReasons(
      assessment({
        gaps: [
          { dimension: 'LEVEL', requested: 2, proposerLimit: 1, approverLimit: 99, approverCovers: true },
          { dimension: 'DISCOUNT_SHARE', requested: 72, proposerLimit: 50, approverLimit: 100, approverCovers: true },
          { dimension: 'INSTALLMENTS', requested: 8, proposerLimit: 6, approverLimit: 6, approverCovers: false },
          { dimension: 'AMOUNT', requested: 6000, proposerLimit: 5000, approverLimit: null, approverCovers: true },
          { dimension: 'DOWN_PAYMENT_SHARE', requested: 5, proposerLimit: 10, approverLimit: 0, approverCovers: true },
        ],
      }),
    );
    expect(rows.map((r) => r.label)).toEqual(['Nível da escada', 'Desconto', 'Pagamentos', 'Valor do acordo', 'Entrada']);
    expect(rows[0]).toMatchObject({ requested: 'nível 2', limit: 'nível 1', excess: '+1 nível', approverLimit: 'toda a escada', covered: true });
    expect(rows[1]).toMatchObject({ requested: '72%', limit: '50%', excess: '+22 p.p. (44% acima)' });
    expect(rows[2]).toMatchObject({ excess: '+2 (33% acima)', covered: false });
    expect(rows[3].requested).toContain('6.000,00');
    expect(rows[3].excess).toContain('1.000,00');
    expect(rows[3].excess).toContain('(20% acima)');
    expect(rows[3].approverLimit).toBe('sem limite');
    expect(rows[4]).toMatchObject({ limit: 'mín. 10%', excess: '5 p.p. abaixo' });
  });

  it('sem aprovador no contexto, a coluna da alçada de quem decide fica vazia', () => {
    const rows = authorityGapReasons(
      assessment({ approverProfileCode: null, gaps: [{ dimension: 'LEVEL', requested: 2, proposerLimit: 1, approverLimit: null, approverCovers: false }] }),
    );
    expect(rows[0].approverLimit).toBeNull();
    expect(rows[0].covered).toBeNull();
  });

  it('motivos sem medida aparecem traduzidos (ex.: renegociação, perfil removido)', () => {
    const p = proposal({ authorityViolations: ['authority.discount_exceeded', 'authority.renegotiations_exceeded'] });
    expect(unmeasuredViolations(p, assessment({ gaps: [{ dimension: 'DISCOUNT_SHARE', requested: 72, proposerLimit: 50, approverLimit: 100, approverCovers: true }] }))).toEqual([
      'Renegociações acima do limite do perfil',
    ]);
    expect(unmeasuredViolations(p, null)).toHaveLength(2);
  });

  it('desconto em % da dívida e prazo da aprovação', () => {
    expect(discountShare({ discountAmount: 111.97, updatedAmount: 2953.22 })).toBe(3.79);
    expect(discountShare({ discountAmount: 10, updatedAmount: 0 })).toBe(0);
    expect(deadlineText('2026-11-08', '2026-11-08')).toEqual({ text: 'vence hoje', tone: 'danger' });
    expect(deadlineText('2026-11-09', '2026-11-08')).toEqual({ text: 'vence amanhã', tone: 'warning' });
    expect(deadlineText('2026-11-12', '2026-11-08')).toEqual({ text: 'vence em 4 dias', tone: null });
    expect(deadlineText('2026-11-01', '2026-11-08')).toEqual({ text: 'vencida', tone: 'danger' });
    expect(deadlineText(null, '2026-11-08').text).toBe('sem prazo');
  });
});
