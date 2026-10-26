import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { attribution, indicatorRow, period } from '../../../testing/incentive-fixtures';
import { button, page, render, setup, text, tick } from '../../../testing/component-harness';
import { ConfirmService } from '../../shared/components/confirm-dialog/confirm.service';
import { DialogService } from '../../shared/dialog/dialog.service';
import { AttributionDetail } from './attribution/attribution-detail';
import { ExportPage } from './exports/export-page';
import { GoalList } from './goals/goal-list';
import { PerformancePanel, groupOptions, monthRange, performanceScope } from './performance/performance-panel';
import { PeriodList } from './periods/period-list';
import { weightedScore } from './quality/evaluation-list';
import { OccurrenceList } from './quality/occurrence-list';

const confirm = { confirm: vi.fn() };
const dialog = { open: vi.fn() };

describe('Telas do incentive', () => {
  afterEach(() => vi.clearAllMocks());

  it('períodos: fecha com confirmação forte quando a carência terminou', async () => {
    const { http, notifications } = setup(PeriodList, ['COMMISSION_STATEMENT_MANAGE'], 'u-1', [{ provide: ConfirmService, useValue: confirm }]);
    confirm.confirm.mockReturnValue(of(''));
    const fixture = await render(PeriodList, http, (url) =>
      url.endsWith('/incentive/periods') ? [period(), period({ id: 'pe-0', period: '2026-07', start: '2026-07-01', end: '2026-07-31', status: 'CLOSED', closedAt: '2026-08-10T06:20:00Z', closeTrigger: 'SCHEDULED', statements: 4 })] : undefined,
    );
    const t = text(fixture);
    expect(t.indexOf('agosto de 2026')).toBeLessThan(t.indexOf('julho de 2026'));
    expect(t).toContain('automático');
    button(fixture, 'Fechar período')!.click();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ confirmationText: 'FECHAR', warning: expect.stringContaining('não poderá aprovar') }));
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/incentive/periods/2026-08/close')).flush(period({ status: 'CLOSED', statements: 3 }));
    await tick();
    expect(notifications.success[0]).toContain('3 extrato(s)');
  });

  it('exportação: lista os arquivos com SHA-256 e mostra a recusa sem extratos aprovados', async () => {
    const { http } = setup(ExportPage, ['COMMISSION_STATEMENT_APPROVE'], 'u-1', [{ provide: ConfirmService, useValue: confirm }]);
    confirm.confirm.mockReturnValue(of(''));
    const fixture = await render(ExportPage, http, (url) =>
      url.endsWith('/incentive/exports')
        ? [{ id: 'x-1', period: '2026-09', format: 'CSV', fileName: 'folha-2026-09.csv', size: 1200, sha256: 'ab'.repeat(32), statements: 4, totalAmount: 9800, exportedBy: 'u-f', exportedAt: '2026-10-05T12:00:00Z' }]
        : undefined,
    );
    expect(text(fixture)).toContain('folha-2026-09.csv');
    expect(text(fixture)).toContain('SHA-256 abababab');
    button(fixture, 'Gerar arquivo')!.click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/incentive/exports'));
    expect(req.request.body).toEqual({ period: expect.stringMatching(/^\d{4}-\d{2}$/), format: 'CSV' });
    req.flush(new Blob([JSON.stringify({ code: 'export.no_approved_statements' })], { type: 'application/json' }), { status: 422, statusText: 'Unprocessable' });
    await tick();
    await tick();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Não há extratos aprovados neste período');
  });

  it('metas: mostra a unidade e remove com confirmação (DELETE)', async () => {
    const { http } = setup(GoalList, ['COMMISSION_PLAN_MANAGE'], 'u-1', [{ provide: ConfirmService, useValue: confirm }, { provide: DialogService, useValue: dialog }]);
    confirm.confirm.mockReturnValue(of(''));
    const fixture = await render(GoalList, http, (url) => {
      if (url.endsWith('/incentive/goals'))
        return [
          { id: 'g-1', period: '2026-10', subjectType: 'USER', subjectId: 'u-x', target: 80000, unit: 'CURRENCY' },
          { id: 'g-2', period: '2026-10', subjectType: 'USER', subjectId: 'u-y', target: 12.5, unit: 'PERCENT' },
          { id: 'g-3', period: '2026-10', subjectType: 'USER', subjectId: 'u-z', target: 15, unit: null },
        ];
      if (url.endsWith('/users/display-names')) return [{ id: 'u-x', name: 'Operador X' }, { id: 'u-y', name: 'Operador Y' }, { id: 'u-z', name: 'Operador Z' }];
      return undefined;
    });
    expect(text(fixture)).toContain('Operador X');
    const t = text(fixture);
    expect(t).toContain('R$\u00a080.000,00');
    expect(t).toContain('12,5%');
    expect(t).toContain('Percentual (%)');
    expect(t).toContain('Não informada');
    expect(t).toContain('Unidade da métrica do plano');
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[aria-label="Remover a meta de Operador X"]')!.click();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ details: expect.arrayContaining([{ label: 'Meta', value: 'R$\u00a080.000,00' }]) }));
    http.expectOne((r) => r.method === 'DELETE' && r.url.endsWith('/incentive/goals/g-1')).flush(null);
  });

  it('ocorrências: dispensar só para a gestão, nunca a própria nem a registrada por si', async () => {
    const rows = [
      { id: 'o-1', userId: 'u-x', type: 'SEVERE_COMPLIANCE_VIOLATION', occurredOn: '2026-10-01', description: 'Contato fora do horário permitido', referenceType: null, referenceId: null, status: 'CONFIRMED', registeredBy: 'u-q', dismissedBy: null, dismissedAt: null, dismissalReason: null },
      { id: 'o-2', userId: 'u-y', type: 'COMPLAINT', occurredOn: '2026-10-02', description: 'Reclamação no consumidor.gov', referenceType: null, referenceId: null, status: 'CONFIRMED', registeredBy: 'u-g', dismissedBy: null, dismissedAt: null, dismissalReason: null },
    ];
    const { http } = setup(OccurrenceList, ['PERFORMANCE_VIEW_ALL', 'COMMISSION_STATEMENT_MANAGE'], 'u-g');
    const fixture = await render(OccurrenceList, http, (url) => (url.endsWith('/quality/occurrences') ? page(rows) : undefined));
    expect(text(fixture)).toContain('Violação grave de compliance');
    const dismiss = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].filter((b) => b.textContent?.includes('Dispensar'));
    expect(dismiss).toHaveLength(1);
    expect(button(fixture, 'Registrar ocorrência')).toBeFalsy();
  });

  it('nota ponderada da monitoria espelha o servidor (crítico com nota 0 zera)', () => {
    const form = { id: 'f', name: 'F', description: null, active: true, criteria: [{ code: 'A', description: 'a', weight: 2, critical: false }, { code: 'B', description: 'b', weight: 1, critical: true }] };
    expect(weightedScore(form, { A: 9, B: 6 })).toEqual({ score: 8, critical: false });
    expect(weightedScore(form, { A: 10, B: 0 })).toEqual({ score: 0, critical: true });
    expect(weightedScore(form, { A: 10, B: null })).toEqual({ score: null, critical: false });
  });

  it('desempenho: escopo pela permissão e período do mês', () => {
    expect(performanceScope((p) => p === 'PERFORMANCE_VIEW_OWN')).toBe('OWN');
    expect(performanceScope((p) => p === 'PERFORMANCE_VIEW_TEAM' || p === 'PERFORMANCE_VIEW_OWN')).toBe('TEAM');
    expect(performanceScope((p) => p === 'PERFORMANCE_VIEW_ALL')).toBe('ALL');
    expect(groupOptions('OWN')).not.toContain('USER');
    expect(groupOptions('TEAM')[0]).toBe('USER');
    expect(monthRange('2026-10', '2026-10-25')).toEqual({ from: '2026-10-01', to: '2026-10-25' });
    expect(monthRange('2026-09', '2026-10-25')).toEqual({ from: '2026-09-01', to: '2026-09-30' });
  });

  it('desempenho da equipe: indicadores com base e tabela por negociador (gráficos do analytics)', async () => {
    const { http } = setup(PerformancePanel, ['PERFORMANCE_VIEW_TEAM']);
    const fixture = await render(PerformancePanel, http, (url, req) => {
      if (url.endsWith('/incentive/performance')) {
        const group = req.request.params.get('groupBy');
        return { from: '2026-10-01', to: '2026-10-25', groupBy: group, rows: group === 'NONE' ? [indicatorRow()] : [indicatorRow('u-x', 7000), indicatorRow('u-y', 3000)], unavailableMetrics: ['conversion.contactToProposal'] };
      }
      if (url.endsWith('/users/display-names')) return [{ id: 'u-x', name: 'Operador X' }, { id: 'u-y', name: 'Operador Y' }];
      return undefined;
    }, {}, 8);
    const t = text(fixture);
    expect(t).toContain('Desempenho da equipe');
    expect(t).toContain('Casos trabalhados');
    expect(t).toContain('300 tentativas');
    expect(t).toContain('15 de 20');
    expect(t).toContain('Operador X');
    expect(t).toContain('Contato → proposta');
    expect((fixture.nativeElement as HTMLElement).querySelector('app-bar-chart')).toBeTruthy();
  });

  it('meu desempenho: sem agrupamento por pessoa', async () => {
    const { http } = setup(PerformancePanel, ['PERFORMANCE_VIEW_OWN']);
    const fixture = await render(PerformancePanel, http, (url) => (url.endsWith('/incentive/performance') ? { from: '', to: '', groupBy: 'NONE', rows: [], unavailableMetrics: [] } : undefined));
    expect(text(fixture)).toContain('Meu desempenho');
    expect(text(fixture)).toContain('Sem atividade no período.');
    expect(text(fixture)).not.toContain('Por negociador');
  });

  it('atribuição do acordo: créditos, participantes e o estado sem registro', async () => {
    const { http } = setup(AttributionDetail, ['AGREEMENT_VIEW']);
    const fixture = await render(AttributionDetail, http, (url) => {
      if (url.endsWith('/attributions/ag-1')) return attribution();
      if (url.endsWith('/users/display-names')) return [{ id: 'u-x', name: 'Operador X' }];
      return undefined;
    }, { agreementId: 'ag-1' });
    const t = text(fixture);
    expect(t).toContain('Assistente preparou e o humano só confirmou');
    expect(t).toContain('30%');
    expect(t).toContain('Pré-negociou (assistente)');
    expect(t).toContain('Operador X');

    TestBed.resetTestingModule();
    const other = setup(AttributionDetail, ['AGREEMENT_VIEW']);
    const empty = TestBed.createComponent(AttributionDetail);
    empty.componentRef.setInput('agreementId', 'ag-2');
    empty.detectChanges();
    await tick();
    other.http.expectOne((r) => r.url.endsWith('/attributions/ag-2')).flush({ code: 'not_found' }, { status: 404, statusText: 'Not Found' });
    await tick();
    empty.detectChanges();
    expect(text(empty)).toContain('Sem atribuição registrada para este acordo.');
  });
});
