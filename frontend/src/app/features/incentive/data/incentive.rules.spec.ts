import { adjustment, dispute, plan, statementDetail } from '../../../../testing/incentive-fixtures';
import {
  addMonths,
  canClosePeriod,
  canDecide,
  canResolve,
  fraction,
  groupPlans,
  linesTotal,
  monthEnd,
  monthLabel,
  monthOptions,
  needsSecondApproval,
  parseDecimal,
  pct,
  planActions,
  publishStartOptions,
  retireEndOptions,
  segmentText,
  signedMoney,
  statementActions,
  versionState,
} from './incentive.rules';

describe('regras do incentive', () => {
  it('meses: soma, último dia, rótulo e opções do mais recente ao mais antigo', () => {
    expect(addMonths('2026-12', 1)).toBe('2027-01');
    expect(addMonths('2026-01', -1)).toBe('2025-12');
    expect(monthEnd('2028-02')).toBe('2028-02-29');
    expect(monthLabel('2026-08')).toBe('agosto de 2026');
    expect(monthOptions('2026-10-25', 2, 1).map((m) => m.value)).toEqual(['2026-11', '2026-10', '2026-09', '2026-08']);
  });

  it('vigência: publicação no dia 1º a partir do mês corrente; aposentadoria no último dia, nunca antes da véspera do início', () => {
    expect(publishStartOptions('2026-10-25', 2).map((o) => o.value)).toEqual(['2026-10-01', '2026-11-01', '2026-12-01']);
    const ends = retireEndOptions('2026-10-25', '2026-10-01', 1).map((o) => o.value);
    expect(ends).toEqual(['2026-09-30', '2026-10-31', '2026-11-30']);
    expect(retireEndOptions('2026-10-25', '2026-11-01', 1).map((o) => o.value)).toEqual(['2026-10-31', '2026-11-30']);
  });

  it('números em pt-BR: frações, percentuais, valores com sinal', () => {
    expect(fraction(0.255)).toMatch(/25,5\s?%/);
    expect(fraction(null)).toBe('—');
    expect(pct(1.5)).toBe('1,5%');
    expect(parseDecimal('1.234,5')).toBe(1234.5);
    expect(parseDecimal('abc')).toBeNull();
    expect(signedMoney('−150,00')).toBe(-150);
    expect(signedMoney('10,555')).toBeNull();
    expect(signedMoney('')).toBeNull();
    expect(needsSecondApproval(-500.01)).toBe(true);
    expect(needsSecondApproval(500)).toBe(false);
    expect(linesTotal(statementDetail().lines)).toBe(2494.8);
  });

  it('agrupa versões por código com a vigente e o rascunho', () => {
    const v1 = plan({ id: 'p-1', version: 1, effectiveTo: '2026-09-30' });
    const v2 = plan({ id: 'p-2', version: 2, effectiveFrom: '2026-10-01' });
    const v3 = plan({ id: 'p-3', version: 3, status: 'DRAFT', effectiveFrom: null, publishedAt: null });
    const other = plan({ id: 'o-1', code: 'CAMPANHA', name: 'Campanha', priority: 50, status: 'DRAFT', effectiveFrom: null });
    const groups = groupPlans([v1, v3, other, v2], '2026-10-25');
    expect(groups.map((g) => g.code)).toEqual(['CAMPANHA', 'PADRAO_MERCADO']);
    const standard = groups[1];
    expect(standard.versions.map((v) => v.version)).toEqual([3, 2, 1]);
    expect(standard.current?.id).toBe('p-2');
    expect(standard.draft?.id).toBe('p-3');
    expect(versionState(v1, '2026-10-25')).toBe('ENDED');
    expect(versionState(plan({ effectiveFrom: '2026-12-01' }), '2026-10-25')).toBe('SCHEDULED');
  });

  it('ações da versão: rascunho editável; nova versão só sem rascunho; aposentar só publicada vigente', () => {
    const published = plan();
    const draft = plan({ id: 'p-2', version: 2, status: 'DRAFT' });
    expect(planActions(draft, [published, draft], true, '2026-10-25')).toEqual({ edit: true, discard: true, publish: true, newVersion: false, retire: false, simulate: true });
    expect(planActions(published, [published, draft], true, '2026-10-25').newVersion).toBe(false);
    expect(planActions(published, [published], true, '2026-10-25')).toMatchObject({ newVersion: true, retire: true, edit: false });
    expect(planActions(plan({ effectiveTo: '2026-09-30' }), [], true, '2026-10-25').retire).toBe(false);
    expect(Object.values(planActions(draft, [draft], false, '2026-10-25')).some(Boolean)).toBe(false);
  });

  it('extrato: beneficiário contesta e não aprova; quem ajustou não aprova; contestação aberta bloqueia', () => {
    const own = statementActions(statementDetail(), { userId: 'u-x', canManage: false, canApprove: true });
    expect(own).toMatchObject({ dispute: true, approve: false, own: true });
    expect(own.approveBlocked).toContain('beneficiário');

    const finance = statementActions(statementDetail(), { userId: 'u-f', canManage: false, canApprove: true });
    expect(finance).toMatchObject({ approve: true, approveBlocked: null, dispute: false, adjust: false });

    const adjuster = statementActions(statementDetail({ adjustments: [adjustment({ status: 'APPLIED', requestedBy: 'u-f' })] }), { userId: 'u-f', canManage: true, canApprove: true });
    expect(adjuster.approve).toBe(false);
    expect(adjuster.approveBlocked).toContain('ajuste');

    const disputed = statementActions(statementDetail({ disputes: [dispute()] }, { status: 'DISPUTED' }), { userId: 'u-f', canManage: false, canApprove: true });
    expect(disputed.approveBlocked).toContain('contestação');

    const approved = statementActions(statementDetail({}, { status: 'APPROVED' }), { userId: 'u-x', canManage: true, canApprove: true });
    expect(approved).toMatchObject({ dispute: false, recalculate: false, adjust: false, approve: false, approveBlocked: null });
  });

  it('resolução e segunda aprovação são de outra pessoa da gestão', () => {
    expect(canResolve(dispute(), 'DISPUTED', 'u-g', true)).toBe(true);
    expect(canResolve(dispute(), 'DISPUTED', 'u-x', true)).toBe(false);
    expect(canResolve(dispute(), 'DISPUTED', 'u-g', false)).toBe(false);
    expect(canDecide(adjustment(), 'CALCULATED', 'u-h', true)).toBe(true);
    expect(canDecide(adjustment(), 'CALCULATED', 'u-g', true)).toBe(false);
    expect(canDecide(adjustment({ status: 'APPLIED' }), 'CALCULATED', 'u-h', true)).toBe(false);
  });

  it('período fecha a partir do fim da carência', () => {
    expect(canClosePeriod({ status: 'OPEN', closeableFrom: '2026-09-08' }, '2026-09-08')).toBe(true);
    expect(canClosePeriod({ status: 'OPEN', closeableFrom: '2026-09-08' }, '2026-09-07')).toBe(false);
    expect(canClosePeriod({ status: 'CLOSED', closeableFrom: '2026-09-08' }, '2026-10-01')).toBe(false);
  });

  it('segmento legível', () => {
    expect(segmentText({ creditorIds: [], portfolioIds: [], delayBuckets: [] })).toBe('Qualquer segmento');
    expect(segmentText({ creditorIds: ['c-1'], portfolioIds: [], delayBuckets: ['D361_PLUS'] }, () => 'Horizonte')).toBe('Credor: Horizonte · Mais de 360 dias');
  });
});
