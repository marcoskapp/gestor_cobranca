import { of } from 'rxjs';
import { plan } from '../../../../testing/incentive-fixtures';
import { button, render, setup, text, tick } from '../../../../testing/component-harness';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { PlanDetail } from './plan-detail';
import { PlanList } from './plan-list';
import { PlanRulesPage } from './plan-rules-page';

const confirm = { confirm: vi.fn() };
const dialog = { open: vi.fn() };
const published = plan();
const draft = plan({ id: 'p-2', version: 2, status: 'DRAFT', effectiveFrom: null, publishedAt: null, publishedBy: null });

function respond(current = published) {
  return (url: string) => {
    if (url.endsWith('/incentive/plans')) return [published, draft];
    if (url.endsWith(`/incentive/plans/${current.id}`)) return current;
    if (url.endsWith('/users/display-names')) return [{ id: 'u-9', name: 'Gestor G' }];
    return undefined;
  };
}

describe('Planos de remuneração', () => {
  afterEach(() => vi.clearAllMocks());

  it('lista um plano por código com a vigente e o rascunho', async () => {
    const { http } = setup(PlanList, ['COMMISSION_PLAN_MANAGE']);
    const fixture = await render(PlanList, http, respond());
    const t = text(fixture);
    expect(t).toContain('Padrão de mercado');
    expect(t).toContain('Vigente');
    expect(t).toContain('Rascunho v2');
    expect(button(fixture, 'Novo plano')).toBeTruthy();
  });

  it('versão publicada: regras legíveis, versões e sem editar (já há rascunho, então sem nova versão)', async () => {
    const { http } = setup(PlanDetail, ['COMMISSION_PLAN_MANAGE'], 'u-1', [{ provide: DialogService, useValue: dialog }]);
    const fixture = await render(PlanDetail, http, respond(), { id: 'p-1' });
    const t = text(fixture);
    expect(t).toContain('Padrão de mercado · v1');
    expect(t).toContain('Escalonado progressivo');
    expect(t).toContain('Assistente fechou sozinho');
    expect(t).toContain('Há um rascunho de nova versão');
    expect(button(fixture, 'Editar regras')).toBeFalsy();
    expect(button(fixture, 'Nova versão')).toBeFalsy();
    expect(button(fixture, 'Aposentar')).toBeTruthy();
    button(fixture, 'Simular')!.click();
    expect(dialog.open).toHaveBeenCalled();
  });

  it('rascunho: publicar abre a escolha da vigência; descartar confirma e chama DELETE', async () => {
    const { http, notifications } = setup(PlanDetail, ['COMMISSION_PLAN_MANAGE'], 'u-1', [{ provide: DialogService, useValue: dialog }, { provide: ConfirmService, useValue: confirm }]);
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
    confirm.confirm.mockReturnValue(of(''));
    const fixture = await render(PlanDetail, http, respond(draft), { id: 'p-2' });
    expect(text(fixture)).toContain('Rascunho: ainda não remunera ninguém');
    button(fixture, 'Publicar')!.click();
    expect(dialog.open).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ data: expect.objectContaining({ mode: 'publish', current: expect.objectContaining({ id: 'p-1' }) }) }));
    button(fixture, 'Descartar rascunho')!.click();
    http.expectOne((r) => r.method === 'DELETE' && r.url.endsWith('/incentive/plans/p-2')).flush(null);
    await tick();
    expect(notifications.success).toContain('Rascunho descartado.');
  });

  it('editor de regras mostra os problemas antes de salvar e envia as regras normalizadas', async () => {
    const { http } = setup(PlanRulesPage, ['COMMISSION_PLAN_MANAGE']);
    const fixture = await render(PlanRulesPage, http, respond(draft), { id: 'p-2' });
    const el = fixture.nativeElement as HTMLElement;
    expect(text(fixture)).toContain('Regras · Padrão de mercado v2');
    const rate = el.querySelector<HTMLInputElement>('#re-rate-on')!;
    const pool = el.querySelector<HTMLInputElement>('#re-pool-on')!;
    rate.click();
    pool.click();
    fixture.detectChanges();
    button(fixture, 'Salvar rascunho')!.click();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Corrija antes de salvar');
    expect(text(fixture)).toContain('ao menos um componente');
    http.expectNone((r) => r.method === 'PUT');

    rate.click();
    fixture.detectChanges();
    button(fixture, 'Salvar rascunho')!.click();
    const req = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/incentive/plans/p-2'));
    expect(req.request.body.code).toBe('PADRAO_MERCADO');
    expect(req.request.body.rules.teamPool).toBeNull();
    expect(req.request.body.rules.recoveryRate.mode).toBe('PROGRESSIVE');
    expect(req.request.body.rules.eligibility.roleCodes).toEqual(['OPERADOR', 'SUPERVISOR']);
    req.flush(draft);
  });

  it('versão publicada não abre o editor', async () => {
    const { http } = setup(PlanRulesPage, ['COMMISSION_PLAN_MANAGE']);
    const fixture = await render(PlanRulesPage, http, respond(), { id: 'p-1' });
    expect(text(fixture)).toContain('Versão publicada não muda.');
  });
});
