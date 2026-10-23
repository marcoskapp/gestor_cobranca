import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
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
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, ReactiveFormsModule],
  template: `
    <h2 mat-dialog-title class="app-confirm__title">
      @if (data.destructive) {
        <mat-icon class="app-confirm__icon" aria-hidden="true">warning_amber</mat-icon>
      }
      {{ data.title }}
    </h2>
    <mat-dialog-content>
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
        <p class="app-alert app-alert--warning" role="note">
          <mat-icon aria-hidden="true">info</mat-icon>
          <span>{{ data.warning }}</span>
        </p>
      }
      @if (data.reason) {
        <mat-form-field class="app-full-width">
          <mat-label>{{ data.reasonLabel ?? 'Motivo' }}</mat-label>
          <textarea
            matInput
            [formControl]="reason"
            rows="3"
            [maxlength]="maxReason"
            [required]="data.reason === 'required'"
          ></textarea>
          <mat-hint align="end">{{ reason.value.length }} / {{ maxReason }}</mat-hint>
          @if (reason.invalid) {
            <mat-error>{{ error() }}</mat-error>
          }
        </mat-form-field>
      }
      @if (data.confirmationText) {
        <mat-form-field class="app-full-width">
          <mat-label>Digite {{ data.confirmationText }} para confirmar</mat-label>
          <input matInput [formControl]="typed" autocomplete="off" spellcheck="false" />
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="false">Cancelar</button>
      <button
        mat-flat-button
        type="button"
        [class.app-button-danger]="data.destructive"
        [disabled]="!typedMatches()"
        (click)="confirm()"
      >
        {{ data.confirmLabel ?? 'Confirmar' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .app-confirm__title {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .app-confirm__icon {
      color: var(--app-danger-fg);
    }
    .app-confirm__details {
      margin: 0 0 16px;
      padding: 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
  `,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ConfirmDialog, ConfirmDialogResult>>(MatDialogRef);
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

  protected error(): string {
    return errorMessage(this.reason.errors);
  }

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
