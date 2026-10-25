import { Component, input, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Field } from '../../forms/field';
import { FieldControl } from '../../forms/field-control';
import { Limits } from '../../validation/validators';
import { Icon } from '../icon/icon';

/** Campo de senha com alternância de visibilidade e mensagens de erro acessíveis. */
@Component({
  selector: 'app-password-field',
  imports: [ReactiveFormsModule, Field, FieldControl, Icon],
  host: { class: 'd-block' },
  template: `
    <app-field [label]="label()" [hint]="hint()">
      <div class="input-group">
        <input
          appInput
          [type]="visible() ? 'text' : 'password'"
          [formControl]="control()"
          [attr.autocomplete]="autocomplete()"
          [maxlength]="max"
          spellcheck="false"
          autocapitalize="off"
        />
        <button
          type="button"
          class="btn btn-outline-secondary app-password-field__toggle"
          (click)="visible.set(!visible())"
          [attr.aria-label]="visible() ? 'Ocultar senha' : 'Mostrar senha'"
          [attr.aria-pressed]="visible()"
        >
          <app-icon [name]="visible() ? 'eye-slash' : 'eye'" />
        </button>
      </div>
    </app-field>
  `,
  styles: `
    .app-password-field__toggle {
      --bs-btn-border-color: var(--app-control-border);
    }
  `,
})
export class PasswordField {
  readonly control = input.required<FormControl<string>>();
  readonly label = input('Senha');
  readonly autocomplete = input<'current-password' | 'new-password'>('current-password');
  readonly hint = input<string>();
  protected readonly visible = signal(false);
  protected readonly max = Limits.password;
}

export const NEW_PASSWORD_HINT =
  'Mínimo de 12 caracteres, com ao menos 3 tipos: maiúsculas, minúsculas, números e símbolos.';
