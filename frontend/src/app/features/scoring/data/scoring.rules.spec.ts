import { FormBuilder } from '@angular/forms';
import { FEATURES, caseScore, evaluation, factor, model, monitoring, run, settings } from '../../../../testing/scoring-fixtures';
import {
  bandFor,
  bandRanges,
  binText,
  calibrationRows,
  compareMetrics,
  contributionText,
  contributionWidths,
  cutoffsValid,
  defaultRecoveryRange,
  duplicatedFeatures,
  featurePsiRows,
  latestCohort,
  modelActions,
  oddsFromProbability,
  probabilityFromOdds,
  psiLevel,
  alertText,
  ratioText,
  realizedAucRows,
  recoveryRangeProblem,
  recoveryRows,
  recoveryTotals,
  reviewChange,
  ruleProblems,
  runSummary,
  scorecardRequest,
  settingsChanges,
  settingsCrossValidator,
  settingsForm,
  settingsRequest,
  durationText,
  scorePsiRows,
} from './scoring.rules';

describe('regras do score', () => {
  it('faixa pelos cortes (espelho de BandCutoffs) e validade dos cortes', () => {
    const cuts = [400, 250, 150, 75];
    expect(bandFor(400, cuts)).toBe('A');
    expect(bandFor(399, cuts)).toBe('B');
    expect(bandFor(150, cuts)).toBe('C');
    expect(bandFor(75, cuts)).toBe('D');
    expect(bandFor(74, cuts)).toBe('E');
    expect(cutoffsValid(cuts)).toBe(true);
    expect(cutoffsValid([400, 400, 150, 75])).toBe(false);
    expect(cutoffsValid([1001, 250, 150, 75])).toBe(false);
    expect(cutoffsValid([400, 250, 150, 0])).toBe(false);
    expect(cutoffsValid([400, 250, null, 75])).toBe(false);
    expect(bandRanges(cuts)[0].text).toContain('40,0%');
    expect(bandRanges(cuts)[4].text).toContain('abaixo de 75');
  });

  it('formulário de parâmetros: percentuais ida e volta e só as mudanças no resumo', () => {
    const s = settings();
    const form = settingsForm(s);
    expect(form.validationSharePct).toBe(30);
    expect(form.defaultPaidSharePct).toBe(30);
    const before = settingsRequest(form);
    expect(before.validationShare).toBe(0.3);
    expect(before.bandCutoffs).toEqual([400, 250, 150, 75]);
    const after = settingsRequest({ ...form, outcomeDays: 45, autoTrain: true, cutA: 420 });
    const changes = settingsChanges(before, after);
    expect(changes.map((c) => c.label)).toEqual(['Janela de desfecho', 'Cortes das faixas (A, B, C, D)', 'Treino mensal automático']);
    expect(changes[0].value).toBe('30 dias → 45 dias');
    expect(settingsChanges(before, before)).toEqual([]);
  });

  it('validação cruzada dos parâmetros', () => {
    const fb = new FormBuilder().nonNullable;
    const group = fb.group({ ...settingsForm(settings()) }, { validators: settingsCrossValidator });
    expect(group.errors).toBeNull();
    group.patchValue({ minPositives: 600, trainingWindowDays: 40, psiWarning: 0.3, cutD: 200 });
    expect(Object.keys(group.errors ?? {}).sort()).toEqual(['cutoffs', 'positivesAboveCases', 'psiOrder', 'trainingWindow']);
  });

  it('governança: só o desafiante, com SCORING_MANAGE e todos os credores; quem treinou não promove', () => {
    const m = model();
    expect(modelActions(m, { userId: 'u-1', canManage: true, allCreditors: true })).toEqual({ promote: true, reject: true, blockedReason: null });
    const own = modelActions(m, { userId: 'u-2', canManage: true, allCreditors: true });
    expect(own.promote).toBe(false);
    expect(own.reject).toBe(true);
    expect(own.blockedReason).toContain('segregação');
    expect(modelActions(m, { userId: 'u-1', canManage: true, allCreditors: false }).promote).toBe(false);
    expect(modelActions(m, { userId: 'u-1', canManage: false, allCreditors: true })).toEqual({ promote: false, reject: false, blockedReason: null });
    expect(modelActions(model({ status: 'CHAMPION' }), { userId: 'u-1', canManage: true, allCreditors: true }).promote).toBe(false);
  });

  it('comparação de métricas: maior é melhor, salvo o Brier', () => {
    const rows = compareMetrics(evaluation({ auc: 0.72, brier: 0.11 }), evaluation({ auc: 0.65, brier: 0.1 }));
    expect(rows.find((r) => r.key === 'auc')?.verdict).toBe('better');
    expect(rows.find((r) => r.key === 'brier')?.verdict).toBe('worse');
    expect(rows.find((r) => r.key === 'auc')?.challenger).toBe('0,720');
    expect(compareMetrics(evaluation(), undefined).every((r) => r.verdict === null)).toBe(true);
  });

  it('calibração, PSI e AUC realizada em linhas de gráfico', () => {
    const cal = calibrationRows(evaluation().calibration);
    expect(cal[0]).toMatchObject({ key: 'A', label: 'Faixa A', display: ['45,0%', '45,0%'] });
    expect(cal[0].note).toBe('45 pagaram de 100 casos');
    const psi = featurePsiRows({ OPEN_BALANCE: 0.02, DAYS_OVERDUE: 0.31 }, FEATURES.allowed, 0.1, 0.25);
    expect(psi.map((r) => r.label)).toEqual(['Dias de atraso do título mais antigo', 'Saldo em aberto (R$)']);
    expect(psi[0].note).toBe('alerta');
    expect(psiLevel(0.12, 0.1, 0.25)).toBe('WARNING');
    expect(psiLevel(null, 0.1, 0.25)).toBe('STABLE');
    const m = monitoring();
    expect(realizedAucRows(m.realized)[0].note).toContain('desafiante (sombra) 0,730');
    expect(latestCohort([monitoring({ id: 'old', referenceDate: '2026-10-01', realized: [] }), m])?.referenceDate).toBe('2026-09-13');
    expect(scorePsiRows([monitoring({ id: 'a', referenceDate: '2026-10-18' }), monitoring({ id: 'b', referenceDate: '2026-10-11', scorePsi: null })], 0.1, 0.25).map((r) => r.label)).toEqual(['18/10']);
    expect(alertText(m.alerts[0], FEATURES.allowed)).toBe('Distribuição da variável (Dias de atraso do título mais antigo): PSI 0,310 ≥ 0,250');
  });

  it('recuperado × esperado: totais, razão e período', () => {
    const lines = [
      { groupKey: 'c-1', cases: 10, expectedPayers: 2.5, realizedPayers: 3, expectedAmount: 1000, realizedAmount: 1200, ratio: 1.2 },
      { groupKey: 'c-2', cases: 5, expectedPayers: '1.5', realizedPayers: 1, expectedAmount: '1000', realizedAmount: '800', ratio: '0.8' },
    ];
    const t = recoveryTotals(lines);
    expect(t).toMatchObject({ cases: 15, expectedPayers: 4, realizedPayers: 4, expectedAmount: 2000, realizedAmount: 2000, ratio: 1 });
    expect(recoveryTotals([]).ratio).toBeNull();
    expect(ratioText(1.2)).toBe('1,20×');
    expect(ratioText(null)).toBe('—');
    expect(recoveryRows(lines, (k) => k.toUpperCase())[1]).toMatchObject({ label: 'C-2', values: [1000, 800] });
    expect(defaultRecoveryRange('2026-10-25')).toEqual({ from: '2026-04-28', to: '2026-10-25' });
    expect(recoveryRangeProblem('2026-10-25', '2026-10-01')).toContain('posterior');
    expect(recoveryRangeProblem('2025-01-01', '2026-10-01')).toContain('400');
    expect(recoveryRangeProblem(null, '2026-10-01')).toContain('Informe');
    expect(recoveryRangeProblem('2026-01-01', '2026-10-01')).toBeNull();
  });

  it('resumo das execuções de escoragem e de treino', () => {
    expect(runSummary(run())).toBe('1.234 escorados · 3 ignorados · 45 mudaram de faixa');
    expect(runSummary(run({ kind: 'TRAINING', status: 'INSUFFICIENT_DATA', details: { distinctCases: 120, distinctPositiveCases: 8, minCases: 500, minPositives: 50 } }))).toBe(
      'Histórico insuficiente: 120 casos e 8 pagantes (mínimo 500 e 50)',
    );
    expect(runSummary(run({ kind: 'TRAINING', details: { failures: ['validation_auc_below_minimum'] } }))).toBe('Modelo reprovado: AUC de validação abaixo da mínima');
    expect(runSummary(run({ kind: 'TRAINING', details: { failures: [], validationAuc: 0.71 } }))).toBe('Novo desafiante · AUC de validação 0,710');
    expect(durationText('2026-10-25T05:30:00Z', '2026-10-25T05:32:05Z')).toBe('2 min 5 s');
    expect(durationText('2026-10-25T05:30:00Z', null)).toBe('em andamento');
  });

  it('fatores: pontos no scorecard, log-odds na regressão e largura relativa', () => {
    expect(contributionText(factor({ points: 40 }))).toBe('+40 pts');
    expect(contributionText(factor({ points: null, contribution: '-0.3512' }))).toBe('-0,35');
    const widths = contributionWidths(caseScore().factors);
    expect(widths.get('ACTIVE_AGREEMENT')).toBe(100);
    expect(widths.get('EFFECTIVE_CONTACTS_90D')).toBe(25);
    expect(
      reviewChange({ previousScore: 210, previousBand: 'C', newScore: 312, newBand: 'B' } as Parameters<typeof reviewChange>[0]),
    ).toBe('C (210) → B (312)');
  });

  it('scorecard: faixas, sobreposição, repetição e chance da âncora', () => {
    expect(ruleProblems({ feature: 'DAYS_OVERDUE', missingPoints: 0, bins: [{ from: null, to: 31, points: 30 }, { from: 31, to: null, points: -5 }] })).toEqual([]);
    expect(ruleProblems({ feature: 'DAYS_OVERDUE', missingPoints: 0, bins: [{ from: null, to: 40, points: 30 }, { from: 31, to: null, points: 0 }] })).toContain('Há faixas sobrepostas.');
    expect(ruleProblems({ feature: 'DAYS_OVERDUE', missingPoints: 0, bins: [{ from: 50, to: 10, points: 0 }] })).toContain('Em cada faixa o início precisa ser menor que o fim.');
    expect(ruleProblems({ feature: '', missingPoints: 900, bins: [] })).toEqual(['Escolha a variável.', 'Pontos inteiros entre −500 e 500.']);
    expect(duplicatedFeatures([{ feature: 'A', missingPoints: 0, bins: [] }, { feature: 'A', missingPoints: 0, bins: [] }])).toEqual(['A']);
    expect(oddsFromProbability(0.1)).toBeCloseTo(1 / 9, 5);
    expect(probabilityFromOdds(1 / 9)).toBeCloseTo(0.1, 5);
    expect(binText({ from: null, to: 31, points: 0 })).toBe('abaixo de 31');
    expect(binText({ from: 361, to: null, points: 0 })).toBe('361 ou mais');
    const req = scorecardRequest({ description: '  ', basePoints: 500, pdo: 20, anchorPoints: 500, anchorProbabilityPct: 10, rules: [{ feature: 'X', missingPoints: null, bins: [{ from: 1, to: 2, points: null }] }] });
    expect(req.description).toBeNull();
    expect(req.rules[0]).toEqual({ feature: 'X', missingPoints: 0, bins: [{ from: 1, to: 2, points: 0 }] });
  });
});
