import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { render, setup, text, tick } from '../../../../testing/component-harness';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { GoalUnitOptions, GoalView } from '../data/incentive.models';
import { GoalDialog, GoalDialogData } from './goal-dialog';
import { goalPercentValidator, goalTargetText, goalUnitHint } from './goal-units';

const close = vi.fn();
const currencyOnly: GoalUnitOptions = { units: ['CURRENCY'], basis: 'PLAN', metric: 'RECOVERED', planCode: 'PADRAO_MERCADO', planVersion: 1, planName: 'Padrão de mercado' };
const percentOnly: GoalUnitOptions = { units: ['PERCENT'], basis: 'PLAN', metric: 'RECOVERY_RATE', planCode: 'TAXA', planVersion: 2, planName: 'Taxa da equipe' };
const both: GoalUnitOptions = { units: ['CURRENCY', 'PERCENT'], basis: 'NO_PLAN', metric: null, planCode: null, planVersion: null, planName: null };
const goal = (patch: Partial<GoalView> = {}): GoalView => ({ id: 'g-1', period: '2026-11', subjectType: 'USER', subjectId: 'u-x', target: 80000, unit: 'CURRENCY', ...patch });

async function open(data: GoalDialogData, options: GoalUnitOptions) {
  const { http } = setup(GoalDialog, ['COMMISSION_PLAN_MANAGE'], 'u-1', [
    { provide: DIALOG_DATA, useValue: data },
    { provide: DialogRef, useValue: { close, titleId: 'gd-title' } },
  ]);
  const fixture = await render(GoalDialog, http, (url) => (url.endsWith('/incentive/goals/unit-options') ? options : undefined));
  return { http, fixture, el: fixture.nativeElement as HTMLElement };
}

const radio = (el: HTMLElement, label: string) => {
  const l = [...el.querySelectorAll('label.btn')].find((x) => x.textContent?.trim() === label) as HTMLLabelElement;
  return el.querySelector<HTMLInputElement>(`#${l.htmlFor}`)!;
};
const valueInput = (el: HTMLElement) => el.querySelector<HTMLInputElement>('input[formcontrolname="amount"], input[formcontrolname="percent"]');

function choose(fixture: ComponentFixture<unknown>, input: HTMLInputElement): void {
  input.click();
  fixture.detectChanges();
}

function typeNumber(fixture: ComponentFixture<unknown>, input: HTMLInputElement, value: string): void {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  fixture.detectChanges();
}

/** Digitação por centavos (como o navegador, com `beforeinput` cancelável). */
function keyIn(fixture: ComponentFixture<unknown>, input: HTMLInputElement, digits: string): void {
  input.focus();
  for (const char of digits) {
    const event = new InputEvent('beforeinput', { inputType: 'insertText', data: char, cancelable: true, bubbles: true });
    if (input.dispatchEvent(event)) {
      input.value += char;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
  fixture.detectChanges();
}

function submit(fixture: ComponentFixture<unknown>, el: HTMLElement): void {
  el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
  fixture.detectChanges();
}

function expectNoSave(http: HttpTestingController): void {
  expect(http.match((r) => r.method === 'PUT')).toHaveLength(0);
}

describe('GoalDialog (meta do período em R$ ou %)', () => {
  afterEach(() => vi.clearAllMocks());

  it('plano por valor recuperado: R$ marcado, % desabilitado com explicação, e o valor com a máscara', async () => {
    const { http, fixture, el } = await open({ period: '2026-11', goal: goal(), subjectName: 'Operador X' }, currencyOnly);
    const currency = radio(el, 'Valor (R$)');
    const percent = radio(el, 'Percentual (%)');
    expect(currency.checked).toBe(true);
    expect(percent.disabled).toBe(true);
    const group = el.querySelector<HTMLElement>('[role="group"]')!;
    expect(group.getAttribute('aria-labelledby')).toBeTruthy();
    expect(el.querySelector(`#${group.getAttribute('aria-labelledby')}`)!.textContent).toContain('Unidade da meta');
    expect(el.querySelector(`#${group.getAttribute('aria-describedby')}`)!.textContent).toContain('A meta do plano Padrão de mercado (versão 1) é por valor recuperado: só aceita valor em R$.');

    const input = valueInput(el)!;
    expect(input.getAttribute('formcontrolname')).toBe('amount');
    expect(input.type).toBe('text');
    expect(input.getAttribute('inputmode')).toBe('numeric');
    expect(input.value).toBe('80.000,00');
    expect(input.closest('.input-group')!.querySelector('.input-group-text')!.textContent).toBe('R$');
    // a unidade é anunciada pela descrição do campo
    const described = (input.getAttribute('aria-describedby') ?? '').split(' ').map((id) => el.querySelector(`#${id}`)?.textContent ?? '').join(' ');
    expect(described).toContain('Valor em reais.');

    input.select();
    keyIn(fixture, input, '9500050');
    expect(input.value).toBe('95.000,50');
    submit(fixture, el);
    const req = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/incentive/goals'));
    expect(req.request.body).toEqual({ period: '2026-11', subjectType: 'USER', subjectId: 'u-x', target: 95000.5, unit: 'CURRENCY' });
    req.flush(goal({ target: 95000.5 }));
    await tick();
    expect(close).toHaveBeenCalled();
  });

  it('percentual: número simples sem máscara de centavos, "%" ao lado e limites de 0,01 a 100', async () => {
    const { http, fixture, el } = await open({ period: '2026-11', goal: goal({ subjectType: 'TEAM', subjectId: 't-1', unit: 'PERCENT', target: 12.5 }), subjectName: 'Equipe 1' }, percentOnly);
    expect(radio(el, 'Percentual (%)').checked).toBe(true);
    expect(radio(el, 'Valor (R$)').disabled).toBe(true);
    const input = valueInput(el)!;
    expect(input.getAttribute('formcontrolname')).toBe('percent');
    expect(input.type).toBe('number');
    expect(input.hasAttribute('appmoney')).toBe(false);
    expect(input.closest('.input-group')!.querySelector('.input-group-text')!.textContent).toBe('%');
    expect(input.value).toBe('12.5');
    const described = (input.getAttribute('aria-describedby') ?? '').split(' ').map((id) => el.querySelector(`#${id}`)?.textContent ?? '').join(' ');
    expect(described).toContain('Percentual de 0,01 a 100');

    typeNumber(fixture, input, '100.5');
    submit(fixture, el);
    expectNoSave(http);
    expect(text(fixture)).toContain('Informe um percentual de 0,01 a 100, com até 2 casas.');
    typeNumber(fixture, input, '12.345');
    submit(fixture, el);
    expectNoSave(http);

    typeNumber(fixture, input, '15.25');
    submit(fixture, el);
    const req = http.expectOne((r) => r.method === 'PUT');
    expect(req.request.body).toEqual({ period: '2026-11', subjectType: 'TEAM', subjectId: 't-1', target: 15.25, unit: 'PERCENT' });
  });

  it('sem plano que defina a unidade: exige a escolha e trocar de unidade limpa o valor', async () => {
    const { http, fixture, el } = await open({ period: '2026-11' }, both);
    expect(text(fixture)).toContain('Escolha o negociador ou a equipe para conferir a unidade');
    (fixture.componentInstance as unknown as { form: { controls: { user: { setValue(v: string[]): void } } } }).form.controls.user.setValue(['u-x']);
    fixture.detectChanges();
    await tick();
    const req = http.expectOne((r) => r.url.endsWith('/incentive/goals/unit-options'));
    expect(req.request.params.get('subjectType')).toBe('USER');
    expect(req.request.params.get('subjectId')).toBe('u-x');
    expect(req.request.params.get('period')).toBe('2026-11');
    req.flush(both);
    await tick();
    fixture.detectChanges();

    expect(radio(el, 'Valor (R$)').checked).toBe(false);
    expect(radio(el, 'Percentual (%)').checked).toBe(false);
    expect(radio(el, 'Valor (R$)').disabled).toBe(false);
    expect(radio(el, 'Percentual (%)').disabled).toBe(false);
    expect(text(fixture)).toContain('Nenhum plano vigente no período cobre o negociador');
    expect(valueInput(el)).toBeNull();
    submit(fixture, el);
    expect(text(fixture)).toContain('Escolha a unidade da meta.');
    expectNoSave(http);

    choose(fixture, radio(el, 'Valor (R$)'));
    keyIn(fixture, valueInput(el)!, '1234567');
    expect(valueInput(el)!.value).toBe('12.345,67');
    // 12.345,67 não vira 12.345,67% nem 12,34%: a troca de unidade limpa o campo
    choose(fixture, radio(el, 'Percentual (%)'));
    expect(valueInput(el)!.getAttribute('formcontrolname')).toBe('percent');
    expect(valueInput(el)!.value).toBe('');
    choose(fixture, radio(el, 'Valor (R$)'));
    expect(valueInput(el)!.value).toBe('');

    choose(fixture, radio(el, 'Percentual (%)'));
    typeNumber(fixture, valueInput(el)!, '8');
    submit(fixture, el);
    expect(http.expectOne((r) => r.method === 'PUT').request.body).toEqual({ period: '2026-11', subjectType: 'USER', subjectId: 'u-x', target: 8, unit: 'PERCENT' });
  });

  it('meta gravada em unidade que o plano não aceita mais: muda de unidade e pede o valor de novo, sem converter', async () => {
    const { el, fixture } = await open({ period: '2026-11', goal: goal({ target: 50000 }), subjectName: 'Operador X' }, percentOnly);
    expect(radio(el, 'Percentual (%)').checked).toBe(true);
    expect(radio(el, 'Valor (R$)').disabled).toBe(true);
    expect(valueInput(el)!.value).toBe('');
    expect(text(fixture)).toContain('A unidade mudou para percentual (%): digite o valor de novo.');
  });

  it('meta antiga sem unidade: a unidade do plano vem marcada e o valor aparece para conferência', async () => {
    const { el, fixture } = await open({ period: '2026-11', goal: goal({ unit: null, target: 80000 }), subjectName: 'Operador X' }, currencyOnly);
    expect(radio(el, 'Valor (R$)').checked).toBe(true);
    expect(valueInput(el)!.value).toBe('80.000,00');
    expect(text(fixture)).toContain('Meta cadastrada antes da escolha de unidade: confira o valor em R$ e salve.');
  });

  it('meta antiga sem unidade e plano que aceita as duas: nada pré-selecionado', async () => {
    const { el, fixture } = await open({ period: '2026-11', goal: goal({ unit: null, target: 15 }), subjectName: 'Operador X' }, both);
    expect(radio(el, 'Valor (R$)').checked).toBe(false);
    expect(radio(el, 'Percentual (%)').checked).toBe(false);
    expect(text(fixture)).toContain('Meta cadastrada antes da escolha de unidade (15): escolha a unidade e digite o valor.');
  });

  it('falha ao consultar o plano: as duas unidades ficam disponíveis e o servidor confere', async () => {
    const { http } = setup(GoalDialog, ['COMMISSION_PLAN_MANAGE'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { period: '2026-11', goal: goal(), subjectName: 'Operador X' } },
      { provide: DialogRef, useValue: { close, titleId: 'gd-title' } },
    ]);
    const fixture = TestBed.createComponent(GoalDialog);
    fixture.detectChanges();
    await tick();
    http.expectOne((r) => r.url.endsWith('/incentive/goals/unit-options')).flush({ code: 'x' }, { status: 503, statusText: 'Unavailable' });
    await tick();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(radio(el, 'Valor (R$)').disabled).toBe(false);
    expect(radio(el, 'Percentual (%)').disabled).toBe(false);
    expect(radio(el, 'Valor (R$)').checked).toBe(true);
    expect(text(fixture)).toContain('Não foi possível conferir o plano vigente; escolha a unidade (o servidor confere ao salvar).');
  });
});

describe('Unidades da meta', () => {
  it('formata na unidade e valida o percentual', () => {
    expect(goalTargetText(80000, 'CURRENCY')).toBe('R$\u00a080.000,00');
    expect(goalTargetText(12.5, 'PERCENT')).toBe('12,5%');
    expect(goalTargetText(80000, null)).toBe('80.000');
    const v = (value: unknown) => goalPercentValidator({ value } as never);
    expect(v(null)).toBeNull();
    expect(v(0.01)).toBeNull();
    expect(v(100)).toBeNull();
    expect(v(12.75)).toBeNull();
    expect(v(0)).toEqual({ goalPercent: true });
    expect(v(100.01)).toEqual({ goalPercent: true });
    expect(v(1.005)).toEqual({ goalPercent: true });
  });

  it('explica a origem da unidade', () => {
    expect(goalUnitHint(percentOnly, 'TEAM')).toBe('A meta do plano Taxa da equipe (versão 2) é por taxa de recuperação: só aceita percentual.');
    expect(goalUnitHint({ ...both, basis: 'PLAN_WITHOUT_GOAL', planName: 'Padrão de mercado', planVersion: 1 }, 'TEAM')).toContain('não usa meta de equipe para a equipe');
    expect(goalUnitHint({ ...both, basis: 'MIXED' }, 'TEAM')).toContain('métricas diferentes');
  });
});
