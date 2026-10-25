import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { DIALOG_DATA, DialogRef } from '../../dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../dialog/dialog.directives';
import { FORM_FIELD } from '../../forms/field';
import { Icon } from '../icon/icon';
import { AppValidators, Limits } from '../../validation/validators';
import { errorMessage } from '../../validation/error-messages';

export interface ConfirmDialogData {
  title: string;
  message: string;
  confirmLabel?: string;
  /** Ações destrutivas usam o botão em tom de erro. */
  destructive?: boolean;
  /** Solicita motivo (registrado na auditoria). */
  reason?: 'required' | 'optional';
  reasonLabel?: string;
  /** Limite do motivo quando o endpoint aceita menos que o padrão (`Limits.reason`). */
  reasonMaxLength?: number;
  /**
   * Resumo do impacto exibido antes da confirmação (ex.: devedor, valor, parcelas). Em ações
   * financeiras ou irreversíveis, mostre exatamente o que será feito.
   */
  details?: readonly { label: string; value: string }[];
  /** Ação irreversível: exige digitar este texto para habilitar a confirmação (ex.: "CANCELAR"). */
  confirmationText?: string;
  /** Aviso adicional em destaque (ex.: "Esta ação não pode ser desfeita."). */
  warning?: string;
}

/** Resultado: `false` se cancelado; caso contrário, o motivo informado (ou `''`). */
export type ConfirmDialogResult = string | false;

@Component({
  selector: 'app-confirm-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 d-flex align-items-center gap-2 mb-0">
        @if (data.destructive) {
          <app-icon class="text-danger-emphasis" name="exclamation-triangle" />
        }
        {{ data.title }}
      </h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <p class="app-dialog-message">{{ data.message }}</p>
      @if (data.details?.length) {
        <dl class="app-definition-list app-confirm__details">
          @for (d of data.details; track d.label) {
            <dt>{{ d.label }}</dt>
            <dd>{{ d.value }}</dd>
          }
        </dl>
      }
      @if (data.warning) {
        <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
          <app-icon name="info-circle" />
          <span>{{ data.warning }}</span>
        </div>
      }
      @if (data.reason) {
        <app-field class="mb-3" [label]="data.reasonLabel ?? 'Motivo'" [hintEnd]="reason.value.length + ' / ' + maxReason" [error]="error()">
          <textarea appInput [formControl]="reason" rows="3" [maxlength]="maxReason" [required]="data.reason === 'required'"></textarea>
        </app-field>
      }
      @if (data.confirmationText) {
        <app-field [label]="'Digite ' + data.confirmationText + ' para confirmar'">
          <input appInput [formControl]="typed" autocomplete="off" spellcheck="false" />
        </app-field>
      }
    </div>
    <div class="modal-footer">
      <!-- foco inicial em "Cancelar" (prevenção de erro, docs/tecnico/desenho/ux.md §2) -->
      <button type="button" class="btn btn-link" ngbAutofocus [appDialogClose]="false">Cancelar</button>
      <button
        type="button"
        class="btn"
        [class.btn-primary]="!data.destructive"
        [class.btn-danger]="data.destructive"
        [disabled]="!typedMatches()"
        (click)="confirm()"
      >
        {{ data.confirmLabel ?? 'Confirmar' }}
      </button>
    </div>
  `,
  styles: `
    .app-confirm__details {
      margin: 0 0 16px;
      padding: 12px 16px;
      border-radius: var(--bs-border-radius);
      background: var(--bs-tertiary-bg);
    }
  `,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ConfirmDialogResult>>(DialogRef);
  protected readonly maxReason = Math.min(this.data.reasonMaxLength ?? Limits.reason, Limits.reason);
  protected readonly reason = new FormControl('', {
    nonNullable: true,
    validators: [
      ...(this.data.reason === 'required' ? [AppValidators.notBlank] : []),
      Validators.maxLength(this.maxReason),
    ],
  });

  protected readonly typed = new FormControl('', { nonNullable: true });
  private readonly typedValue = toSignal(this.typed.valueChanges, { initialValue: '' });
  protected readonly typedMatches = computed(
    () => !this.data.confirmationText || this.typedValue().trim().toUpperCase() === this.data.confirmationText.toUpperCase(),
  );

  private readonly reasonValue = toSignal(this.reason.valueChanges, { initialValue: '' });
  protected readonly error = computed(() => {
    this.reasonValue();
    return errorMessage(this.reason.errors);
  });

  protected confirm(): void {
    if (!this.typedMatches()) {
      return;
    }
    if (this.data.reason && this.reason.invalid) {
      this.reason.markAsTouched();
      return;
    }
    this.ref.close(this.reason.value.trim());
  }
}
