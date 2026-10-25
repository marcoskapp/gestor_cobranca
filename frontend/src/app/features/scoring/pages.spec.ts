import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { button, page, render, setup, text } from '../../../testing/component-harness';
import { fakeUser } from '../../../testing/auth-fixtures';
import { FEATURES, monitoring, run, settings } from '../../../testing/scoring-fixtures';
import { AuthService } from '../../core/auth/auth.service';
import { provideBootstrapDefaults } from '../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../core/config/bootstrap-components.config';
import { NAVIGATION } from '../../core/layout/navigation';
import { ConfirmService } from '../../shared/components/confirm-dialog/confirm.service';
import { DIALOG_DATA, DialogRef } from '../../shared/dialog/dialog-ref';
import { DialogService } from '../../shared/dialog/dialog.service';
import { ExpectedRecoveryPanel } from './monitoring/expected-recovery-panel';
import { MonitoringPage } from './monitoring/monitoring-page';
import { StabilityPanel } from './monitoring/stability-panel';
import { RUN_POLL_MS, RunDetail } from './runs/run-detail';
import { RunList } from './runs/run-list';
import { SCORING_ROUTES } from './scoring.routes';
import { LegalBasisDialog } from './settings/legal-basis-dialog';
import { ScoringSettings } from './settings/scoring-settings';
import { TransparencyPage } from './transparency/transparency-page';

const MANAGE = ['SCORING_VIEW', 'SCORING_MANAGE'];
const limitedScope = (permissions: string[]) => ({
  provide: AuthService,
  useValue: {
    user: signal(fakeUser({ allCreditors: false, permissions })),
    hasPermission: (p: string) => permissions.includes(p),
    hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)),
  },
});

describe('rotas e menu do score', () => {
  it('cada item do menu exige as mesmas permissões da rota', () => {
    const routes = new Map(SCORING_ROUTES.filter((r) => r.data?.['permissions']).map((r) => [`/score/${r.path}`, r.data!['permissions'] as string[]]));
    const items = NAVIGATION.flatMap((s) => s.items).filter((i) => i.route.startsWith('/score'));
    expect(items.length).toBe(5);
    for (const item of items) {
      expect(routes.get(item.route), item.route).toEqual([...item.permissions]);
    }
  });
});

describe('ScoringSettings', () => {
  const confirm = { confirm: vi.fn() };
  const open = vi.fn();
  afterEach(() => vi.clearAllMocks());

  it('gestor altera parâmetros: resumo só com o que mudou e PUT com as frações', async () => {
    confirm.confirm.mockReturnValue(of(''));
    const h = setup(ScoringSettings, MANAGE, 'u-1', [
      { provide: ConfirmService, useValue: confirm },
      { provide: DialogService, useValue: { open } },
    ]);
    const f = await render(ScoringSettings, h.http, (url) => (url === '/api/v1/scoring/settings' ? settings() : url === '/api/v1/users/display-names' ? [{ id: 'u-9', name: 'DPO Silva' }] : undefined));
    expect(text(f)).toContain('Score habilitado');
    expect(text(f)).toContain('LIA-2026-01');
    expect(text(f)).toContain('DPO Silva');
    expect(text(f)).toContain('score ≥ 400 (chance ≥ 40,0%)');
    const form = f.componentInstance['form'];
    form.patchValue({ outcomeDays: 45 });
    form.markAsDirty();
    f.detectChanges();
    button(f, 'Salvar parâmetros')!.click();
    expect(confirm.confirm.mock.calls[0][0].details).toEqual([{ label: 'Janela de desfecho', value: '30 dias → 45 dias' }]);
    const req = h.http.expectOne({ method: 'PUT', url: '/api/v1/scoring/settings' });
    expect(req.request.body).toMatchObject({ outcomeDays: 45, validationShare: 0.3, defaultPaidShare: 0.3, bandCutoffs: [400, 250, 150, 75] });
    req.flush(settings({ outcomeDays: 45 }));
    expect(h.notifications.success).toContain('Parâmetros do score salvos.');
  });

  it('regras cruzadas bloqueiam o envio com mensagem', async () => {
    const h = setup(ScoringSettings, MANAGE, 'u-1', [{ provide: ConfirmService, useValue: confirm }]);
    const f = await render(ScoringSettings, h.http, (url) => (url === '/api/v1/scoring/settings' ? settings() : undefined));
    f.componentInstance['form'].patchValue({ cutB: 500 });
    f.componentInstance['form'].markAsDirty();
    f.detectChanges();
    button(f, 'Salvar parâmetros')!.click();
    f.detectChanges();
    expect(text(f)).toContain('Os cortes das faixas devem ser inteiros decrescentes');
    expect(confirm.confirm).not.toHaveBeenCalled();
  });

  it('sem base legal: score desabilitado e registro pelo diálogo; consulta sem gestão fica só leitura', async () => {
    open.mockReturnValue({ afterClosed: () => of(settings()) });
    const h = setup(ScoringSettings, MANAGE, 'u-1', [{ provide: DialogService, useValue: { open } }]);
    const f = await render(ScoringSettings, h.http, (url) => (url === '/api/v1/scoring/settings' ? settings({ scoringEnabled: false, legalBasis: null, legalBasisReference: null, legalBasisRegisteredAt: null, legalBasisRegisteredBy: null }) : undefined));
    expect(text(f)).toContain('Score desabilitado.');
    button(f, 'Registrar base legal')!.click();
    expect(open).toHaveBeenCalledWith(LegalBasisDialog, expect.anything());
    f.detectChanges();
    expect(text(f)).toContain('Score habilitado');

    TestBed.resetTestingModule();
    const h2 = setup(ScoringSettings, ['SCORING_VIEW'], 'u-1');
    const f2 = await render(ScoringSettings, h2.http, (url) => (url === '/api/v1/scoring/settings' ? settings() : undefined));
    expect(text(f2)).toContain('Somente leitura para o seu perfil.');
    expect(button(f2, 'Salvar parâmetros')).toBeUndefined();
    expect(f2.componentInstance['form'].disabled).toBe(true);
  });
});

describe('LegalBasisDialog', () => {
  it('envia base e referência e mostra o erro do servidor', async () => {
    const close = vi.fn();
    const h = setup(LegalBasisDialog, MANAGE, 'u-1', [
      provideBootstrapDefaults(),
      provideBootstrapComponentDefaults(),
      { provide: DIALOG_DATA, useValue: { current: settings({ scoringEnabled: false, legalBasis: null, legalBasisReference: null }) } },
      { provide: DialogRef, useValue: { close } },
    ]);
    const f = TestBed.createComponent(LegalBasisDialog);
    f.detectChanges();
    expect(text(f)).toContain('passa a coletar os fatos');
    f.componentInstance['form'].patchValue({ legalBasis: 'CREDIT_PROTECTION', reference: '  Parecer jurídico 12/2026  ' });
    button(f, 'Registrar e habilitar o score')!.click();
    const req = h.http.expectOne({ method: 'PUT', url: '/api/v1/scoring/settings/legal-basis' });
    expect(req.request.body).toEqual({ legalBasis: 'CREDIT_PROTECTION', reference: 'Parecer jurídico 12/2026' });
    req.flush(settings());
    expect(close).toHaveBeenCalled();
  });
});

describe('execuções', () => {
  afterEach(() => vi.useRealTimers());

  it('lista com resumo e filtro por tipo; gestor escora agora', async () => {
    const confirm = { confirm: vi.fn().mockReturnValue(of('')) };
    const h = setup(RunList, MANAGE, 'u-1', [{ provide: ConfirmService, useValue: confirm }]);
    const f = await render(RunList, h.http, (url) => (url === '/api/v1/scoring/runs' ? page([run()]) : undefined));
    expect(text(f)).toContain('1.234 escorados');
    expect(text(f)).toContain('com retrato');
    f.componentInstance['filters'].patchValue({ kind: 'TRAINING' });
    await new Promise((r) => setTimeout(r, 0));
    const req = h.http.expectOne((r) => r.url === '/api/v1/scoring/runs');
    expect(req.request.params.get('kind')).toBe('TRAINING');
    req.flush(page([]));
    button(f, 'Escorar agora')!.click();
    h.http.expectOne({ method: 'POST', url: '/api/v1/scoring/runs' }).flush(run({ status: 'RUNNING' }));
    expect(h.notifications.success).toContain('Escoragem em andamento.');
  });

  it('escopo restrito: explica e não consulta', async () => {
    const h = setup(RunList, ['SCORING_VIEW'], 'u-1', [limitedScope(['SCORING_VIEW'])]);
    const f = await render(RunList, h.http, () => undefined);
    expect(text(f)).toContain('todos os credores');
    h.http.expectNone('/api/v1/scoring/runs');
  });

  it('treino com histórico insuficiente explica os motivos; em andamento atualiza sozinho', async () => {
    const h = setup(RunDetail, ['SCORING_VIEW'], 'u-1');
    const training = run({ kind: 'TRAINING', status: 'INSUFFICIENT_DATA', snapshot: false, details: { observations: 900, distinctCases: 120, distinctPositiveCases: 8, minCases: 500, minPositives: 50, reasons: ['min_cases', 'min_positives'] } });
    const f = await render(RunDetail, h.http, (url) => (url === '/api/v1/scoring/runs/r-1' ? training : undefined), { id: 'r-1' });
    expect(text(f)).toContain('Treino de modelo de 25/10/2026');
    expect(text(f)).toContain('menos casos com desfecho conhecido do que o mínimo');
    expect(text(f)).toContain('mínimo 500');

    TestBed.resetTestingModule();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const h2 = setup(RunDetail, ['SCORING_VIEW'], 'u-1');
    const f2 = TestBed.createComponent(RunDetail);
    f2.componentRef.setInput('id', 'r-1');
    f2.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    h2.http.expectOne('/api/v1/scoring/runs/r-1').flush(run({ status: 'RUNNING', finishedAt: null }));
    await vi.advanceTimersByTimeAsync(0);
    f2.detectChanges();
    expect(text(f2)).toContain('Atualiza a cada 5 segundos.');
    await vi.advanceTimersByTimeAsync(RUN_POLL_MS);
    f2.detectChanges();
    h2.http.expectOne('/api/v1/scoring/runs/r-1').flush(run());
    await vi.advanceTimersByTimeAsync(0);
    f2.detectChanges();
    await vi.advanceTimersByTimeAsync(RUN_POLL_MS);
    h2.http.expectNone('/api/v1/scoring/runs/r-1');
    expect(text(f2)).toContain('1.234');
  });
});

describe('monitoramento', () => {
  it('estabilidade: alerta de deriva por variável, PSI, AUC realizada e calibração', async () => {
    const h = setup(StabilityPanel, ['SCORING_VIEW'], 'u-1');
    const f = await render(StabilityPanel, h.http, (url) => {
      if (url === '/api/v1/scoring/monitoring') return page([monitoring(), monitoring({ id: 'mo-0', referenceDate: '2026-10-18', status: 'STABLE', alerts: [] })]);
      if (url === '/api/v1/scoring/settings') return settings();
      if (url === '/api/v1/scoring/features') return FEATURES;
      return undefined;
    });
    const t = text(f);
    expect(t).toContain('Alerta');
    expect(t).toContain('Distribuição da variável (Dias de atraso do título mais antigo): PSI 0,310 ≥ 0,250');
    expect(t).toContain('PSI do score por retrato');
    expect(t).toContain('AUC realizada');
    expect(t).toContain('0,700');
    expect(t).toContain('Calibração da safra de 13/09/2026');
  });

  it('estabilidade sem relatórios mostra o estado vazio', async () => {
    const h = setup(StabilityPanel, ['SCORING_VIEW'], 'u-1');
    const f = await render(StabilityPanel, h.http, (url) => (url === '/api/v1/scoring/monitoring' ? page([]) : undefined));
    expect(text(f)).toContain('Nenhum relatório de monitoramento ainda.');
  });

  it('recuperado × esperado por credor, com a razão e os nomes', async () => {
    const h = setup(ExpectedRecoveryPanel, ['SCORING_VIEW'], 'u-1');
    const f = await render(ExpectedRecoveryPanel, h.http, (url, req) => {
      if (url === '/api/v1/scoring/expected-recovery') {
        expect(req.request.params.get('from')).toBeTruthy();
        return {
          from: req.request.params.get('from'),
          to: req.request.params.get('to'),
          groupBy: req.request.params.get('groupBy'),
          lines: [{ groupKey: 'c-1', cases: 10, expectedPayers: 2.5, realizedPayers: 3, expectedAmount: 1000, realizedAmount: 1200, ratio: 1.2 }],
        };
      }
      if (url === '/api/v1/creditors/c-1') return { id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' };
      return undefined;
    });
    f.componentInstance['filters'].patchValue({ groupBy: 'CREDITOR' });
    await new Promise((r) => setTimeout(r, 300));
    f.detectChanges();
    for (let i = 0; i < 3; i++) {
      for (const req of h.http.match(() => true)) {
        if (req.request.url === '/api/v1/scoring/expected-recovery') {
          expect(req.request.params.get('groupBy')).toBe('CREDITOR');
          req.flush({ from: '2026-01-01', to: '2026-10-25', groupBy: 'CREDITOR', lines: [{ groupKey: 'c-1', cases: 10, expectedPayers: 2.5, realizedPayers: 3, expectedAmount: 1000, realizedAmount: 1200, ratio: 1.2 }] });
        } else if (req.request.url === '/api/v1/creditors/c-1') {
          req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
        } else {
          req.flush({});
        }
      }
      f.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
    }
    f.detectChanges();
    expect(text(f)).toContain('1,20×');
    expect(text(f)).toContain('Horizonte');
    expect(text(f)).toContain('Esperado × recuperado por credor');
  });

  it('período inválido não consulta e explica', async () => {
    const h = setup(ExpectedRecoveryPanel, ['SCORING_VIEW'], 'u-1');
    const f = await render(ExpectedRecoveryPanel, h.http, () => ({ lines: [] }));
    f.componentInstance['filters'].patchValue({ from: '2026-10-25', to: '2026-10-01' });
    await new Promise((r) => setTimeout(r, 300));
    f.detectChanges();
    expect(text(f)).toContain('O fim do período precisa ser igual ou posterior ao início.');
    h.http.expectNone('/api/v1/scoring/expected-recovery');
  });

  it('escopo restrito abre direto no recuperado × esperado', async () => {
    const h = setup(MonitoringPage, ['SCORING_VIEW'], 'u-1', [limitedScope(['SCORING_VIEW'])]);
    const f = await render(MonitoringPage, h.http, (url) => (url === '/api/v1/scoring/expected-recovery' ? { groupBy: 'NONE', lines: [] } : undefined));
    expect(text(f)).toContain('Nenhuma safra apurada no período.');
    h.http.expectNone('/api/v1/scoring/monitoring');
  });
});

describe('TransparencyPage', () => {
  it('finalidade, variáveis, o que nunca é usado e as decisões com a comparação', async () => {
    const open = vi.fn();
    const h = setup(TransparencyPage, ['SCORING_VIEW'], 'u-1', [{ provide: DialogService, useValue: { open } }]);
    const f = await render(TransparencyPage, h.http, (url) => {
      if (url === '/api/v1/scoring/features') return FEATURES;
      if (url === '/api/v1/scoring/decisions')
        return [
          {
            id: 'dc-1',
            modelId: 'm-2',
            decision: 'PROMOTE',
            previousChampionId: 'm-1',
            justification: 'AUC melhor e calibração estável.',
            comparison: { challenger: { modelVersion: 2, validation: { auc: 0.72 } }, champion: { modelVersion: 1 }, championOnSameValidation: { auc: 0.65 } },
            decidedBy: 'u-3',
            decidedAt: '2026-10-21T10:00:00Z',
          },
        ];
      if (url === '/api/v1/users/display-names') return [{ id: 'u-3', name: 'Carlos Gestor' }];
      return undefined;
    });
    const t = text(f);
    expect(t).toContain('Score usado somente para priorizar o atendimento.');
    expect(t).toContain('Logaritmo, ln(1 + x)');
    expect(t).toContain('endereço, CEP');
    expect(t).toContain('Promovido a campeão');
    expect(t).toContain('campeão anterior: v1');
    expect(t).toContain('Carlos Gestor');
    button(f, 'Ver comparação')!.click();
    expect(open).toHaveBeenCalled();
    const detail = open.mock.calls[0][1].data;
    expect(detail.fields.find((x: { label: string }) => x.label === 'AUC').value).toBe('desafiante 0,720 · campeão 0,650');
  });
});
