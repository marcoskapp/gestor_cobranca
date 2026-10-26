import { referenceSummary } from '../review/alerts-step';
import { mixedReview, previewBatch } from '../review/review.fixtures';
import { impactDetails } from '../review/summary-step';
import { totalsRequest } from '../review/totals-step';
import { needsPolling, parseCount, pendingColumns, reachableSteps, scaleText, stepState, suggestedAmount } from './review.rules';

describe('regras da conferência da importação', () => {
  it('quantidade digitada no padrão brasileiro', () => {
    expect(parseCount('1.250')).toBe(1250);
    expect(parseCount('1250')).toBe(1250);
    expect(parseCount('12,5')).toBeNull();
  });

  it('pedido de totais só com os totais marcados e válidos', () => {
    const base = { rows: { use: true, value: '1.250' }, principal: { use: true, value: 98765.43 }, balance: { use: false, value: 12 }, contract: { use: false, value: null } };
    expect(totalsRequest(base)).toEqual({ rows: 1250, principalTotal: 98765.43, balanceTotal: null, contractAmountTotal: null });
    expect(totalsRequest({ ...base, principal: { use: true, value: null } })).toBeNull();
    expect(totalsRequest({ ...base, rows: { use: true, value: '12,5' } })).toBeNull();
    expect(totalsRequest({ ...base, rows: { use: false, value: '' }, principal: { use: false, value: null } })).toBeNull();
  });

  it('passos alcançáveis e situação de cada passo', () => {
    const paused = mixedReview();
    expect(reachableSteps(paused)).toEqual([1, 2]);
    expect(stepState(paused, 2)).toBe('attention');
    expect(reachableSteps(mixedReview({ format: { ...paused.format, available: false } }))).toEqual([1]);
    const validating = mixedReview({ batch: previewBatch({ status: 'VALIDATING' }), format: { ...paused.format, awaitingDecision: false, confirmed: true } });
    expect(reachableSteps(validating)).toEqual([1, 2, 3]);
    expect(stepState(validating, 3)).toBe('running');
    const validated = mixedReview({ batch: previewBatch({ status: 'VALIDATED' }), format: { ...paused.format, awaitingDecision: false, confirmed: true } });
    expect(reachableSteps(validated)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(stepState(validated, 4)).toBe('pending');
    expect(stepState({ ...validated, totals: { checked: true, matched: false, checks: 1, mismatches: 1, lastCheck: null } }, 4)).toBe('attention');
    expect(stepState(validated, 3)).toBe('done');
    expect(stepState(validated, 6)).toBe('pending');
    expect(stepState({ ...validated, status: 'COMMITTED' }, 6)).toBe('done');
    expect(stepState(validated, 5)).toBe('running');
  });

  it('consulta o servidor só enquanto algo está processando', () => {
    expect(needsPolling(mixedReview())).toBe(false);
    expect(needsPolling(mixedReview({ batch: previewBatch({ status: 'VALIDATING' }) }))).toBe(true);
    expect(needsPolling(mixedReview({ batch: previewBatch({ status: 'VALIDATED' }) }))).toBe(true);
    const done = mixedReview({ batch: previewBatch({ status: 'VALIDATED' }) });
    expect(needsPolling({ ...done, analysis: { ...done.analysis, status: 'DONE' } })).toBe(false);
    expect(needsPolling({ ...done, status: 'AWAITING_APPROVAL' })).toBe(false);
  });

  it('textos de escala, sugestão e pendências', () => {
    expect(scaleText(-2)).toBe('o arquivo soma 100 vezes a planilha');
    expect(scaleText(3)).toBe('o arquivo soma 1/1000 da planilha');
    expect(suggestedAmount(12000, -2)).toBe(120);
    expect(suggestedAmount(null, -2)).toBeNull();
    expect(pendingColumns(mixedReview().format.columns, {})).toEqual(['valor_principal']);
    expect(pendingColumns(mixedReview().format.columns, { valor_principal: 'COMMA' })).toEqual([]);
    expect(referenceSummary({ references: { HISTORY_PORTFOLIO: 1200, FILE: 3 } })).toBe('histórico desta carteira (1.200 linhas); este arquivo (3 linhas)');
    expect(referenceSummary({})).toBeNull();
  });

  it('resumo do impacto para a confirmação', () => {
    const details = impactDetails({
      rows: 10, rowsToImport: 8, rowsWithError: 1, rowsExcluded: 1, newTitles: 6, existingTitles: 2, holdBySituation: 1,
      closedBySituation: 0, principalToImport: 12345.67, balanceToImport: 0, approvalRequired: false, approvalThreshold: null, duplicateFile: false,
    });
    expect(details.map((d) => d.label)).toContain('Linhas excluídas na conferência');
    expect(details.find((d) => d.label === 'Soma do valor principal')?.value.replace(/\u00a0/g, ' ')).toBe('R$ 12.345,67');
  });
});
