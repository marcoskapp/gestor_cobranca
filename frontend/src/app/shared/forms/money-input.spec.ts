import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { FORM_FIELD } from './field';

@Component({
  imports: [ReactiveFormsModule, FormsModule, ...FORM_FIELD],
  template: `
    <form [formGroup]="form" (ngSubmit)="0">
      <app-field label="Valor pago" hint="Valor do comprovante">
        <div class="input-group">
          <span class="input-group-text" aria-hidden="true">R$</span>
          <input appInput appMoney formControlName="amount" required [min]="0.01" [max]="max()" />
        </div>
      </app-field>
      <app-field label="Ajuste">
        <div class="input-group">
          <span class="input-group-text" aria-hidden="true">R$</span>
          <input appInput appMoney #sign="appMoney" allowNegative formControlName="adjustment" />
          <button class="btn btn-outline-secondary" type="button" (click)="sign.toggleSign()" [attr.aria-pressed]="sign.negative" aria-label="Valor negativo">+/−</button>
        </div>
      </app-field>
      <app-field label="Custo">
        <input appInput appMoney [decimals]="4" [currency]="null" moneyValue="string" formControlName="cost" />
      </app-field>
      <input class="re-cell" appMoney [(ngModel)]="cell" [ngModelOptions]="{ standalone: true }" aria-label="Faixa 1" />
      <button type="submit">Salvar</button>
    </form>
  `,
})
class Host {
  readonly max = signal<number | null>(null);
  cell: number | null = 12.5;
  readonly form = new FormGroup({
    amount: new FormControl<number | null>(null),
    adjustment: new FormControl<number | null>(null),
    cost: new FormControl<string | null>('0.0125', Validators.required),
  });
}

function setup() {
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const inputs = el.querySelectorAll<HTMLInputElement>('input');
  return { fixture, el, host: fixture.componentInstance, amount: inputs[0], adjustment: inputs[1], cost: inputs[2], cell: inputs[3] };
}

/** Simula o navegador: `beforeinput` cancelável; se não cancelado, aplica o texto e dispara `input`. */
function keyIn(input: HTMLInputElement, data: string, inputType = 'insertText'): void {
  input.focus();
  for (const char of inputType === 'insertText' ? [...data] : [data]) {
    const event = new InputEvent('beforeinput', { inputType, data: char, cancelable: true, bubbles: true });
    if (input.dispatchEvent(event)) {
      input.value += char;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
}

function backspace(input: HTMLInputElement, times = 1): void {
  input.focus();
  for (let i = 0; i < times; i++) {
    input.dispatchEvent(new InputEvent('beforeinput', { inputType: 'deleteContentBackward', cancelable: true, bubbles: true }));
  }
}

function paste(input: HTMLInputElement, text: string): void {
  input.focus();
  const event = new Event('paste', { bubbles: true, cancelable: true }) as Event & { clipboardData: unknown };
  Object.defineProperty(event, 'clipboardData', { value: { getData: () => text } });
  input.dispatchEvent(event);
}

describe('appMoney', () => {
  it('digitação por centavos: cada dígito entra pela direita', () => {
    const { amount, host } = setup();
    keyIn(amount, '1');
    expect(amount.value).toBe('0,01');
    expect(host.form.value.amount).toBe(0.01);
    keyIn(amount, '2345');
    expect(amount.value).toBe('123,45');
    expect(host.form.value.amount).toBe(123.45);
    keyIn(amount, '6789');
    expect(amount.value).toBe('1.234.567,89');
    expect(host.form.value.amount).toBe(1234567.89);
  });

  it('ignora o que não é dígito e mantém o cursor no fim', () => {
    const { amount, host } = setup();
    keyIn(amount, '1a,.2 3');
    expect(amount.value).toBe('1,23');
    expect(host.form.value.amount).toBe(1.23);
    expect(amount.selectionStart).toBe(amount.value.length);
    amount.setSelectionRange(0, 0);
    amount.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', cancelable: true }));
    expect(amount.selectionStart).toBe(4);
    amount.setSelectionRange(1, 1);
    amount.dispatchEvent(new MouseEvent('click'));
    expect(amount.selectionStart).toBe(4);
  });

  it('backspace remove o último dígito; apagar tudo deixa null (não 0)', () => {
    const { amount, host } = setup();
    keyIn(amount, '12345');
    backspace(amount);
    expect(amount.value).toBe('12,34');
    expect(host.form.value.amount).toBe(12.34);
    backspace(amount, 4);
    expect(amount.value).toBe('');
    expect(host.form.value.amount).toBeNull();
  });

  it('seleção + digitação substitui o valor; seleção + apagar esvazia', () => {
    const { amount, host } = setup();
    keyIn(amount, '12345');
    amount.setSelectionRange(0, amount.value.length);
    keyIn(amount, '5');
    expect(amount.value).toBe('0,05');
    amount.select();
    backspace(amount);
    expect(amount.value).toBe('');
    expect(host.form.value.amount).toBeNull();
  });

  it('colar interpreta o texto e recusa o que não é número', () => {
    const { amount, host } = setup();
    paste(amount, 'R$ 1.234,56');
    expect(amount.value).toBe('1.234,56');
    expect(host.form.value.amount).toBe(1234.56);
    paste(amount, '1234.56');
    expect(host.form.value.amount).toBe(1234.56);
    paste(amount, '1234');
    expect(amount.value).toBe('1.234,00');
    expect(host.form.value.amount).toBe(1234);
    paste(amount, 'abc');
    expect(amount.value).toBe('1.234,00');
    // campo sem negativo não aceita valor negativo colado
    paste(amount, '-10,00');
    expect(host.form.value.amount).toBe(1234);
    keyIn(amount, '987,65', 'insertFromPaste');
    expect(host.form.value.amount).toBe(987.65);
  });

  it('"-" só onde negativo é permitido; botão inverte o sinal', () => {
    const { fixture, el, amount, adjustment, host } = setup();
    keyIn(amount, '-15');
    expect(amount.value).toBe('0,15');
    keyIn(adjustment, '-15000');
    expect(adjustment.value).toBe('-150,00');
    expect(host.form.value.adjustment).toBe(-150);
    const button = el.querySelector<HTMLButtonElement>('button[aria-label="Valor negativo"]')!;
    fixture.detectChanges();
    expect(button.getAttribute('aria-pressed')).toBe('true');
    button.click();
    fixture.detectChanges();
    expect(adjustment.value).toBe('150,00');
    expect(host.form.value.adjustment).toBe(150);
    expect(button.getAttribute('aria-pressed')).toBe('false');
    keyIn(adjustment, '+');
    expect(host.form.value.adjustment).toBe(150);
  });

  it('"-" sem dígitos não vira valor e some ao sair do campo', () => {
    const { adjustment, host } = setup();
    keyIn(adjustment, '-');
    expect(adjustment.value).toBe('-');
    expect(host.form.value.adjustment).toBeNull();
    adjustment.dispatchEvent(new Event('blur'));
    expect(adjustment.value).toBe('');
  });

  it('required, mínimo e máximo com mensagens em reais', () => {
    const { fixture, el, amount, host } = setup();
    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
    expect(host.form.controls.amount.errors).toEqual({ required: true });
    expect(el.querySelector('.invalid-feedback')!.textContent).toContain('Campo obrigatório.');
    keyIn(amount, '0');
    fixture.detectChanges();
    expect(host.form.value.amount).toBe(0);
    expect(el.querySelector('.invalid-feedback')!.textContent!.replace(/\u00a0/g, ' ')).toContain('O valor mínimo é R$ 0,01.');
    keyIn(amount, '1');
    fixture.detectChanges();
    expect(host.form.controls.amount.valid).toBe(true);
    host.max.set(100);
    fixture.detectChanges();
    keyIn(amount, '0000');
    fixture.detectChanges();
    expect(host.form.value.amount).toBe(100);
    expect(host.form.controls.amount.valid).toBe(true);
    keyIn(amount, '0');
    fixture.detectChanges();
    expect(host.form.controls.amount.errors?.['moneyMax']).toBeTruthy();
    expect(el.querySelector('.invalid-feedback')!.textContent!.replace(/\u00a0/g, ' ')).toContain('O valor máximo é R$ 100,00.');
  });

  it('valor do modelo aparece formatado; desabilitar o controle desabilita o campo', () => {
    const { fixture, amount, host, cost } = setup();
    host.form.controls.amount.setValue(1234.5);
    expect(amount.value).toBe('1.234,50');
    host.form.controls.amount.setValue(null);
    expect(amount.value).toBe('');
    expect(cost.value).toBe('0,0125');
    host.form.controls.amount.disable();
    fixture.detectChanges();
    expect(amount.disabled).toBe(true);
  });

  it('modelo em texto e casas configuráveis', () => {
    const { cost, host } = setup();
    backspace(cost);
    expect(cost.value).toBe('0,0012');
    expect(host.form.value.cost).toBe('0.0012');
    keyIn(cost, '34');
    expect(host.form.value.cost).toBe('0.1234');
  });

  it('funciona com ngModel (células de tabela)', async () => {
    const { fixture, cell, host } = setup();
    await fixture.whenStable();
    expect(cell.value).toBe('12,50');
    keyIn(cell, '1');
    expect(host.cell).toBe(125.01);
  });

  it('reinterpreta o texto quando o navegador não deixa cancelar (composição)', () => {
    const { amount, host } = setup();
    amount.value = '12,345';
    amount.dispatchEvent(new Event('input', { bubbles: true }));
    expect(amount.value).toBe('123,45');
    expect(host.form.value.amount).toBe(123.45);
  });

  it('acessibilidade: teclado numérico, rótulo, moeda e ajuda anunciados', () => {
    const { el, amount, cost, cell } = setup();
    expect(amount.getAttribute('inputmode')).toBe('numeric');
    expect(amount.type).toBe('text');
    const label = el.querySelector('label')!;
    expect(label.getAttribute('for')).toBe(amount.id);
    const ids = amount.getAttribute('aria-describedby')!.split(' ');
    expect(ids.map((id) => el.querySelector(`#${id}`)!.textContent!.trim())).toEqual(['Valor em reais.', 'Valor do comprovante']);
    // a descrição fica fora do input-group (o arredondamento do Bootstrap usa o primeiro/último filho)
    expect(amount.parentElement!.lastElementChild).toBe(amount);
    expect(cost.getAttribute('aria-describedby')).toBeNull();
    expect(el.querySelector(`#${cell.getAttribute('aria-describedby')}`)!.textContent).toBe('Valor em reais.');
  });
});
