import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { button, render, setup, text } from '../../../../testing/component-harness';
import { FEATURES, model, scorecardModel } from '../../../../testing/scoring-fixtures';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { DecisionDialog } from './decision-dialog';
import { ModelDetail } from './model-detail';
import { ModelList } from './model-list';
import { ScorecardDialog } from './scorecard-dialog';
import { signal } from '@angular/core';

const MANAGE = ['SCORING_VIEW', 'SCORING_MANAGE'];
const respond = (url: string) => {
  if (url === '/api/v1/scoring/models/m-2') return model();
  if (url === '/api/v1/scoring/models') return [model(), scorecardModel()];
  if (url === '/api/v1/scoring/features') return FEATURES;
  if (url === '/api/v1/users/display-names') return [{ id: 'u-2', name: 'Ana Estratégia' }];
  return undefined;
};

describe('ModelDetail', () => {
  const open = vi.fn();
  afterEach(() => vi.clearAllMocks());

  it('mostra validação, comparação com o campeão, calibração e coeficientes; outro gestor pode promover', async () => {
    open.mockReturnValue({ afterClosed: () => of(model({ status: 'CHAMPION' })) });
    const h = setup(ModelDetail, MANAGE, 'u-1', [{ provide: DialogService, useValue: { open } }]);
    const f = await render(ModelDetail, h.http, respond, { id: 'm-2' });
    const t = text(f);
    expect(t).toContain('Modelo v2 · Regressão logística');
    expect(t).toContain('0,720');
    expect(t).toContain('Desafiante melhor');
    expect(t).toContain('Calibração por faixa');
    expect(t).toContain('Dias de atraso do título mais antigo');
    expect(t).toContain('reduz a propensão');
    expect(t).toContain('Ana Estratégia');
    button(f, 'Promover a campeão')!.click();
    expect(open).toHaveBeenCalledWith(DecisionDialog, expect.objectContaining({ data: expect.objectContaining({ decision: 'PROMOTE', champion: expect.objectContaining({ id: 'm-1' }) }) }));
    expect(h.notifications.success[0]).toBe('Modelo v2 promovido a campeão.');
  });

  it('quem pediu o treino só rejeita e vê a explicação da segregação', async () => {
    const h = setup(ModelDetail, MANAGE, 'u-2', [{ provide: DialogService, useValue: { open } }]);
    const f = await render(ModelDetail, h.http, respond, { id: 'm-2' });
    expect(button(f, 'Promover a campeão')).toBeUndefined();
    expect(button(f, 'Rejeitar')).toBeDefined();
    expect(text(f)).toContain('segregação de funções');
    expect(text(f)).toContain('Você');
  });

  it('scorecard: regras por faixa e escala; sem métricas mostra o estado vazio', async () => {
    const h = setup(ModelDetail, ['SCORING_VIEW'], 'u-1', [{ provide: DialogService, useValue: { open } }]);
    const f = await render(ModelDetail, h.http, (url) => (url === '/api/v1/scoring/models/m-1' ? scorecardModel() : respond(url)), { id: 'm-1' });
    const t = text(f);
    expect(t).toContain('abaixo de 31');
    expect(t).toContain('+30');
    expect(t).toContain('500 pontos = 10,0% de chance; a cada 20 pontos a chance dobra');
    expect(t).toContain('Sem métricas de validação.');
    expect(button(f, 'Rejeitar')).toBeUndefined();
  });

  it('sem todos os credores explica a restrição e não chama a API', async () => {
    const h = setup(ModelDetail, MANAGE, 'u-1', [
      { provide: DialogService, useValue: { open } },
      { provide: AuthService, useValue: { user: signal(fakeUser({ allCreditors: false })), hasPermission: () => true, hasAnyPermission: () => true } },
    ]);
    const f = await render(ModelDetail, h.http, (url) => (url === '/api/v1/scoring/features' ? FEATURES : undefined), { id: 'm-2' });
    expect(text(f)).toContain('Disponível somente para quem acessa todos os credores.');
    h.http.expectNone('/api/v1/scoring/models/m-2');
  });
});

describe('ModelList', () => {
  const confirm = { confirm: vi.fn() };
  const open = vi.fn();
  afterEach(() => vi.clearAllMocks());

  it('destaca campeão e desafiante; pedir treino confirma e abre a execução', async () => {
    confirm.confirm.mockReturnValue(of(''));
    const h = setup(ModelList, MANAGE, 'u-1', [
      { provide: ConfirmService, useValue: confirm },
      { provide: DialogService, useValue: { open } },
    ]);
    const f = await render(ModelList, h.http, respond);
    expect(text(f)).toContain('Campeão em uso');
    expect(text(f)).toContain('Desafiante aguardando decisão');
    button(f, 'Pedir treino')!.click();
    h.http.expectOne({ method: 'POST', url: '/api/v1/scoring/models/training' }).flush({ runId: 'r-9', status: 'RUNNING' });
    expect(h.notifications.success[0]).toContain('Treino pedido');
    open.mockReturnValue({ afterClosed: () => of(undefined) });
    button(f, 'Criar scorecard')!.click();
    expect(open).toHaveBeenCalledWith(ScorecardDialog, { data: { templates: [expect.objectContaining({ id: 'm-1' })] }, size: 'xl' });
  });

  it('só consulta: sem os botões de gestão', async () => {
    const h = setup(ModelList, ['SCORING_VIEW'], 'u-1', [{ provide: ConfirmService, useValue: confirm }]);
    const f = await render(ModelList, h.http, respond);
    expect(button(f, 'Pedir treino')).toBeUndefined();
    expect(button(f, 'Criar scorecard')).toBeUndefined();
    expect(text(f)).toContain('v2');
  });
});

describe('DecisionDialog', () => {
  const close = vi.fn();
  afterEach(() => vi.clearAllMocks());

  function make(decision: 'PROMOTE' | 'REJECT') {
    const h = setup(DecisionDialog, MANAGE, 'u-1', [
      provideBootstrapDefaults(),
      provideBootstrapComponentDefaults(),
      { provide: DIALOG_DATA, useValue: { decision, model: model(), champion: scorecardModel() } },
      { provide: DialogRef, useValue: { close } },
    ]);
    const f = TestBed.createComponent(DecisionDialog);
    f.detectChanges();
    return { h, f };
  }

  it('promoção exige justificativa de 10+ caracteres e a palavra PROMOVER', () => {
    const { h, f } = make('PROMOTE');
    expect(text(f)).toContain('o campeão v1 é aposentado');
    expect(text(f)).toContain('Desafiante melhor');
    const submit = button(f, 'Promover a campeão')!;
    expect(submit.disabled).toBe(true);
    f.componentInstance['form'].patchValue({ justification: 'curta', confirmation: 'promover' });
    f.detectChanges();
    submit.click();
    h.http.expectNone('/api/v1/scoring/models/m-2/promote');
    f.componentInstance['form'].patchValue({ justification: 'AUC melhor em 0,07 e calibração estável.' });
    f.detectChanges();
    submit.click();
    const req = h.http.expectOne('/api/v1/scoring/models/m-2/promote');
    expect(req.request.body).toEqual({ justification: 'AUC melhor em 0,07 e calibração estável.' });
    req.flush(model({ status: 'CHAMPION' }));
    expect(close).toHaveBeenCalled();
  });

  it('rejeição sem palavra de confirmação; erro de segregação explicado', () => {
    const { h, f } = make('REJECT');
    f.componentInstance['form'].patchValue({ justification: 'Calibração ruim na faixa E.' });
    f.detectChanges();
    button(f, 'Rejeitar desafiante')!.click();
    h.http.expectOne('/api/v1/scoring/models/m-2/reject').flush({ code: 'scoring.model.not_challenger' }, { status: 409, statusText: 'Conflict' });
    f.detectChanges();
    expect(text(f)).toContain('não é mais o desafiante');
    expect(close).not.toHaveBeenCalled();
  });
});

describe('ScorecardDialog', () => {
  const close = vi.fn();
  afterEach(() => vi.clearAllMocks());

  it('copia um scorecard, valida as faixas e envia a chance da âncora como odds', async () => {
    const h = setup(ScorecardDialog, MANAGE, 'u-1', [
      provideBootstrapDefaults(),
      provideBootstrapComponentDefaults(),
      { provide: DIALOG_DATA, useValue: { templates: [scorecardModel()] } },
      { provide: DialogRef, useValue: { close } },
    ]);
    const f = await render(ScorecardDialog, h.http, (url) => (url === '/api/v1/scoring/features' ? FEATURES : undefined));
    const c = f.componentInstance;
    // regra inicial sem variável: não envia
    button(f, 'Criar scorecard desafiante')!.click();
    f.detectChanges();
    expect(text(f)).toContain('Escolha a variável.');
    h.http.expectNone('/api/v1/scoring/models/scorecards');
    c['template'].setValue('m-1');
    c['applyTemplate']();
    f.detectChanges();
    expect(c['rules'].length).toBe(1);
    expect(c['form'].getRawValue().anchorProbabilityPct).toBe(10);
    // faixa sobreposta
    c['addBin'](0);
    c['binsOf'](0).at(2).patchValue({ from: 50, to: 100, points: 5 });
    f.detectChanges();
    expect(c['ruleIssues']()[0]).toContain('Há faixas sobrepostas.');
    c['removeBin'](0, 2);
    f.detectChanges();
    button(f, 'Criar scorecard desafiante')!.click();
    const req = h.http.expectOne('/api/v1/scoring/models/scorecards');
    expect(req.request.body.anchorOdds).toBeCloseTo(1 / 9, 5);
    expect(req.request.body.rules[0].bins).toEqual([{ from: null, to: 31, points: 30 }, { from: 31, to: 91, points: 15 }]);
    req.flush(scorecardModel({ id: 'm-3', modelVersion: 3, status: 'CHALLENGER' }));
    expect(close).toHaveBeenCalled();
  });
});
