import { Component, computed, contentChild, effect, input } from '@angular/core';
import { FieldControl } from './field-control';
import { InputGroupDensity } from './input-group';
import { MoneyInput } from './money-input';

/**
 * Campo com rótulo, ajuda e mensagem de erro no padrão de formulários do Bootstrap
 * (`form-label`, `form-text`, `invalid-feedback`), substituto do `mat-form-field`.
 * O controle projetado precisa da diretiva `appInput`; a mensagem de erro vem de
 * `errorMessage()` (pt-BR) e aparece quando o controle é inválido e foi tocado ou o formulário
 * foi submetido. Rótulo sempre visível (sem placeholder no lugar do rótulo — WCAG 3.3.2).
 *
 * ```html
 * <app-field label="E-mail" hint="Usado para entrar no sistema">
 *   <input appInput type="email" formControlName="email" autocomplete="username" />
 * </app-field>
 *
 * <!-- com botão ou texto ao lado: input-group do Bootstrap; valor monetário com a máscara `appMoney` -->
 * <app-field label="Valor">
 *   <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney formControlName="valor" /></div>
 * </app-field>
 * ```
 */
@Component({
  selector: 'app-field',
  host: { class: 'app-field d-block' },
  template: `
    <label class="form-label" [attr.for]="controlId()">{{ label() }}</label>
    <ng-content />
    @if (control()?.invalid()) {
      <div class="invalid-feedback d-block" [id]="errorId()" aria-live="polite">{{ error() || control()?.message() }}</div>
    } @else if (hint() || hintEnd()) {
      <div class="form-text d-flex gap-2" [id]="hintId()">
        <span class="flex-grow-1" [class]="hintClass()">{{ hint() }}</span>
        @if (hintEnd()) {
          <span class="text-nowrap">{{ hintEnd() }}</span>
        }
      </div>
    }
  `,
})
export class Field {
  readonly label = input.required<string>();
  readonly hint = input<string>();
  /** Texto auxiliar à direita (ex.: contador `12 / 500`). */
  readonly hintEnd = input<string>();
  /** Classes extras da ajuda (ex.: `text-danger-emphasis fw-semibold` para destacar um alerta na ajuda). */
  readonly hintClass = input<string>('');
  /** Mensagem de erro própria (substitui a mensagem padrão do validador). */
  readonly error = input<string>();

  protected readonly control = contentChild(FieldControl, { descendants: true });
  protected readonly controlId = computed(() => this.control()?.id() ?? null);
  protected readonly hintId = computed(() => `${this.controlId()}-hint`);
  protected readonly errorId = computed(() => `${this.controlId()}-error`);

  constructor() {
    effect(() => {
      const control = this.control();
      if (!control) {
        return;
      }
      const invalid = control.invalid();
      control.setDescribedBy(invalid ? this.errorId() : this.hint() || this.hintEnd() ? this.hintId() : null);
    });
  }
}

/** Importe junto: `imports: [ReactiveFormsModule, ...FORM_FIELD]` (inclui `appMoney` e a densidade do `input-group`). */
export const FORM_FIELD = [Field, FieldControl, MoneyInput, InputGroupDensity] as const;
