import { Component, input, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { errorMessage } from '../../validation/error-messages';
import { Limits } from '../../validation/validators';

/** Campo de senha com alternância de visibilidade e mensagens de erro acessíveis. */
@Component({
  selector: 'app-password-field',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatIconModule, MatButtonModule],
  template: `
    <mat-form-field class="app-full-width">
      <mat-label>{{ label() }}</mat-label>
      <input
        matInput
        [type]="visible() ? 'text' : 'password'"
        [formControl]="control()"
        [attr.autocomplete]="autocomplete()"
        [maxlength]="max"
        spellcheck="false"
        autocapitalize="off"
      />
      <button
        mat-icon-button
        matSuffix
        type="button"
        (click)="visible.set(!visible())"
        [attr.aria-label]="visible() ? 'Ocultar senha' : 'Mostrar senha'"
        [attr.aria-pressed]="visible()"
      >
        <mat-icon aria-hidden="true">{{ visible() ? 'visibility_off' : 'visibility' }}</mat-icon>
      </button>
      @if (hint()) {
        <mat-hint>{{ hint() }}</mat-hint>
      }
      @if (control().invalid) {
        <mat-error>{{ message() }}</mat-error>
      }
    </mat-form-field>
  `,
})
export class PasswordField {
  readonly control = input.required<FormControl<string>>();
  readonly label = input('Senha');
  readonly autocomplete = input<'current-password' | 'new-password'>('current-password');
  readonly hint = input<string>();
  protected readonly visible = signal(false);
  protected readonly max = Limits.password;

  protected message(): string {
    return errorMessage(this.control().errors);
  }
}

export const NEW_PASSWORD_HINT =
  'Mínimo de 12 caracteres, com ao menos 3 tipos: maiúsculas, minúsculas, números e símbolos.';
