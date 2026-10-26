import { DOCUMENT } from '@angular/common';
import { DestroyRef, Directive, ElementRef, Injector, OnInit, Renderer2, booleanAttribute, effect, forwardRef, inject, input, numberAttribute } from '@angular/core';
import { AbstractControl, ControlValueAccessor, NG_VALIDATORS, NG_VALUE_ACCESSOR, ValidationErrors, Validator } from '@angular/forms';
import { formatAmount } from '../components/amount/amount';
import { FieldControl } from './field-control';
import {
  EMPTY_MONEY,
  MONEY_MAX_DIGITS,
  MoneyState,
  fitsDigits,
  formatMoneyState,
  fromModel,
  limitUnits,
  parseMoneyText,
  popDigit,
  pushDigit,
  signedUnits,
  toModel,
} from './money';

const NAVIGATION_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp', 'PageDown']);
const DECIMAL_FORMATS = new Map<number, Intl.NumberFormat>();

let nextId = 0;

/**
 * Máscara de digitação monetária por centavos (padrão de bancos e maquininhas): cada dígito entra pela
 * direita ("1" → 0,01; "12345" → 123,45), backspace apaga o último, e o usuário nunca digita os
 * separadores. O texto do campo fica em pt-BR ("1.234.567,89") **sem** "R$": o símbolo vem de um
 * `input-group-text` do Bootstrap ao lado (com `aria-hidden`), e o leitor de tela recebe "Valor em
 * reais." na descrição do campo. O valor do controle é `number` (ou texto decimal canônico com
 * `moneyValue="string"`), calculado de um inteiro de centavos — sem erro de ponto flutuante — e vazio
 * vira `null` (nunca 0), para `required` e campos opcionais funcionarem como esperado.
 *
 * ```html
 * <app-field label="Valor pago">
 *   <div class="input-group">
 *     <span class="input-group-text" aria-hidden="true">R$</span>
 *     <input appInput appMoney formControlName="amount" required [min]="0.01" />
 *   </div>
 * </app-field>
 *
 * <!-- valor com sinal (ajustes): "-" no teclado ou o botão, que existe também para teclados sem "-" -->
 * <div class="input-group">
 *   <span class="input-group-text" aria-hidden="true">R$</span>
 *   <input appInput appMoney #m="appMoney" allowNegative formControlName="amount" />
 *   <button class="btn btn-outline-secondary" type="button" (click)="m.toggleSign()" [attr.aria-pressed]="m.negative" aria-label="Valor negativo">+/−</button>
 * </div>
 * ```
 *
 * Também: colar "1.234,56", "1234.56", "R$ 1.234,56" ou "1234" interpreta o texto (ver
 * `parseMoneyText`); `[min]`/`[max]` validam com mensagens em reais; `[decimals]` muda as casas (0 a 6).
 * Não use junto de `type="number"` (o `npm run money:check` recusa).
 */
@Directive({
  selector: 'input[appMoney]',
  exportAs: 'appMoney',
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => MoneyInput), multi: true },
    { provide: NG_VALIDATORS, useExisting: forwardRef(() => MoneyInput), multi: true },
  ],
  host: {
    type: 'text',
    inputmode: 'numeric',
    autocomplete: 'off',
    autocorrect: 'off',
    spellcheck: 'false',
    class: 'app-numeric',
    '(beforeinput)': 'onBeforeInput($event)',
    '(input)': 'onInput()',
    '(paste)': 'onPaste($event)',
    '(drop)': 'onDrop($event)',
    '(keydown)': 'onKeydown($event)',
    '(focus)': 'keepCaretAtEnd()',
    '(click)': 'keepCaretAtEnd()',
    '(blur)': 'touched()',
  },
})
export class MoneyInput implements ControlValueAccessor, Validator, OnInit {
  /** Casas decimais (padrão 2; 4 para custos em moeda do provedor, por exemplo). */
  readonly decimals = input(2, { transform: (v: unknown) => Math.min(6, Math.max(0, numberAttribute(v, 2))) });
  /** Aceita valor negativo ("-" no teclado ou `toggleSign()`). */
  readonly allowNegative = input(false, { transform: booleanAttribute });
  /** Limites inclusivos, na mesma unidade do modelo (reais). */
  readonly min = input<number | string | null | undefined>(null);
  readonly max = input<number | string | null | undefined>(null);
  /** Tipo do valor do controle: `number` (padrão) ou texto decimal ("1234.56"), para modelos em texto. */
  readonly moneyValue = input<'number' | 'string'>('number');
  /** Moeda anunciada ao leitor de tela; `null` quando o símbolo não é o real (ex.: moeda do provedor). */
  readonly currency = input<'BRL' | null>('BRL');
  /** Dígitos significativos aceitos (padrão 15, o que o `number` representa sem perda). */
  readonly maxDigits = input(MONEY_MAX_DIGITS, { transform: (v: unknown) => Math.min(MONEY_MAX_DIGITS, Math.max(1, numberAttribute(v, MONEY_MAX_DIGITS))) });

  private readonly element = inject<ElementRef<HTMLInputElement>>(ElementRef).nativeElement;
  private readonly renderer = inject(Renderer2);
  private readonly document = inject(DOCUMENT);
  // o FieldControl injeta o NgControl, que injeta este value accessor: buscado só no ngOnInit (sem ciclo)
  private readonly injector = inject(Injector);

  private state: MoneyState = EMPTY_MONEY;
  private model: number | string | null = null;
  private onChange: (value: number | string | null) => void = () => undefined;
  private onTouched: () => void = () => undefined;
  private onValidatorChange: () => void = () => undefined;

  constructor() {
    effect(() => {
      this.min();
      this.max();
      this.decimals();
      this.onValidatorChange();
    });
    inject(DestroyRef).onDestroy(() => this.description?.remove());
  }

  /** O valor atual é negativo (ou o "-" foi digitado e espera os dígitos)? */
  get negative(): boolean {
    return this.state.negative;
  }

  /** Inverte o sinal (botão "+/−" ao lado do campo, para teclados de toque sem a tecla "-"). */
  toggleSign(): void {
    if (!this.allowNegative() || this.element.disabled || this.element.readOnly) {
      return;
    }
    this.commit({ units: this.state.units, negative: !this.state.negative });
    this.element.focus();
  }

  // ------------------------------------------------------------------ ControlValueAccessor

  writeValue(value: unknown): void {
    const state = fromModel(value, this.decimals());
    this.state = this.allowNegative() ? state : { units: state.units, negative: false };
    this.model = toModel(this.state, this.decimals(), this.moneyValue());
    this.render();
  }

  registerOnChange(fn: (value: number | string | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.renderer.setProperty(this.element, 'disabled', disabled);
  }

  // ------------------------------------------------------------------ Validator

  validate(control: AbstractControl): ValidationErrors | null {
    const state = fromModel(control.value, this.decimals());
    const value = signedUnits(state);
    if (value === null) {
      return null;
    }
    const min = limitUnits(this.min(), this.decimals());
    if (min !== null && value < min) {
      return { moneyMin: { min: this.min(), text: this.limitText(this.min()) } };
    }
    const max = limitUnits(this.max(), this.decimals());
    if (max !== null && value > max) {
      return { moneyMax: { max: this.max(), text: this.limitText(this.max()) } };
    }
    return null;
  }

  registerOnValidatorChange(fn: () => void): void {
    this.onValidatorChange = fn;
  }

  // ------------------------------------------------------------------ acessibilidade

  private description: HTMLElement | null = null;

  ngOnInit(): void {
    if (this.currency() !== 'BRL') {
      return;
    }
    const field = this.injector.get(FieldControl, null, { self: true, optional: true });
    // descrição fora do `input-group` (o Bootstrap arredonda pelo primeiro/último filho do grupo)
    const id = `${field?.id() || this.element.id || `app-money-${++nextId}`}-currency`;
    const span = this.document.createElement('span');
    span.id = id;
    span.className = 'visually-hidden';
    span.textContent = 'Valor em reais.';
    const group = this.element.parentElement?.classList.contains('input-group') ? this.element.parentElement : this.element;
    group.after(span);
    this.description = span;
    if (field) {
      field.setExtraDescribedBy(id);
    } else {
      const current = this.element.getAttribute('aria-describedby');
      this.renderer.setAttribute(this.element, 'aria-describedby', current ? `${id} ${current}` : id);
    }
  }

  // ------------------------------------------------------------------ eventos

  protected onBeforeInput(event: InputEvent): void {
    const type = event.inputType;
    if (type === 'insertText' || type === 'insertReplacementText') {
      event.preventDefault();
      const data = event.data ?? event.dataTransfer?.getData('text/plain') ?? '';
      // substituição de texto inteiro (corretor, preenchimento) se comporta como colar
      if (type === 'insertReplacementText' && data.length > 1) {
        this.paste(data);
        return;
      }
      this.type(data);
    } else if (type === 'insertFromPaste' || type === 'insertFromDrop' || type === 'insertFromYank') {
      event.preventDefault();
      this.paste(event.data ?? event.dataTransfer?.getData('text/plain') ?? '');
    } else if (type.startsWith('delete')) {
      event.preventDefault();
      this.commit(this.hasSelection() ? EMPTY_MONEY : popDigit(this.state));
    } else if (type === 'historyUndo' || type === 'historyRedo') {
      // o histórico nativo guardaria textos que a máscara já reescreveu
      event.preventDefault();
    }
    // composição (IME, alguns teclados Android) não é cancelável: `onInput` reinterpreta o texto
  }

  /** Alternativa quando o `beforeinput` não pôde ser cancelado: relê os dígitos do texto. */
  protected onInput(): void {
    const text = this.element.value;
    const digits = text.replace(/\D/g, '');
    const negative = this.allowNegative() && /^\s*[-−]/.test(text);
    const state: MoneyState = digits ? { units: BigInt(digits), negative } : { units: null, negative };
    this.commit(fitsDigits(state, this.maxDigits()) ? state : this.state);
  }

  protected onPaste(event: ClipboardEvent): void {
    event.preventDefault();
    this.paste(event.clipboardData?.getData('text/plain') ?? '');
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.element.focus();
    this.paste(event.dataTransfer?.getData('text/plain') ?? '');
  }

  protected onKeydown(event: KeyboardEvent): void {
    // o cursor fica sempre no fim; Shift + setas continuam selecionando (para substituir o valor)
    if (NAVIGATION_KEYS.has(event.key) && !event.shiftKey && !event.altKey && !event.metaKey && !event.ctrlKey) {
      event.preventDefault();
      this.keepCaretAtEnd();
    }
  }

  protected keepCaretAtEnd(): void {
    if (!this.hasSelection()) {
      const end = this.element.value.length;
      this.element.setSelectionRange(end, end);
    }
  }

  protected touched(): void {
    // "-" sem dígitos não é valor: some ao sair do campo
    if (this.state.units === null && this.state.negative) {
      this.commit(EMPTY_MONEY);
    }
    this.onTouched();
  }

  // ------------------------------------------------------------------ regras

  private type(data: string): void {
    let state = this.hasSelection() ? EMPTY_MONEY : this.state;
    for (const char of data) {
      if (char >= '0' && char <= '9') {
        state = pushDigit(state, Number(char), this.maxDigits());
      } else if ((char === '-' || char === '−') && this.allowNegative()) {
        state = { units: state.units, negative: !state.negative };
      } else if (char === '+') {
        state = { units: state.units, negative: false };
      }
    }
    this.commit(state);
  }

  private paste(text: string): void {
    const parsed = parseMoneyText(text, this.decimals());
    // texto sem número, negativo onde não cabe ou grande demais: mantém o valor anterior
    if (!parsed || (parsed.negative && !this.allowNegative()) || !fitsDigits(parsed, this.maxDigits())) {
      this.render();
      return;
    }
    this.commit(parsed);
  }

  private commit(state: MoneyState): void {
    this.state = state;
    this.render();
    const model = toModel(state, this.decimals(), this.moneyValue());
    if (model !== this.model) {
      this.model = model;
      this.onChange(model);
    }
  }

  private render(): void {
    const text = formatMoneyState(this.state, this.decimals());
    if (this.element.value !== text) {
      this.renderer.setProperty(this.element, 'value', text);
    }
    if (this.document.activeElement === this.element) {
      this.element.setSelectionRange(text.length, text.length);
    }
  }

  private hasSelection(): boolean {
    const { selectionStart, selectionEnd } = this.element;
    return selectionStart !== null && selectionEnd !== null && selectionStart !== selectionEnd;
  }

  private limitText(limit: number | string | null | undefined): string {
    const decimals = this.decimals();
    const value = Number(limit);
    if (this.currency() === 'BRL' && decimals === 2) {
      return formatAmount(value);
    }
    let format = DECIMAL_FORMATS.get(decimals);
    if (!format) {
      format = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
      DECIMAL_FORMATS.set(decimals, format);
    }
    return format.format(value);
  }
}
