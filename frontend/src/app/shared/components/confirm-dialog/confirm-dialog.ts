import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
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
}

/** Resultado: `false` se cancelado; caso contrário, o motivo informado (ou `''`). */
export type ConfirmDialogResult = string | false;

@Component({
  selector: 'app-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="app-dialog-message">{{ data.message }}</p>
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
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="false">Cancelar</button>
      <button
        mat-flat-button
        type="button"
        [class.app-button-danger]="data.destructive"
        (click)="confirm()"
      >
        {{ data.confirmLabel ?? 'Confirmar' }}
      </button>
    </mat-dialog-actions>
  `,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ConfirmDialog, ConfirmDialogResult>>(MatDialogRef);
  protected readonly maxReason = Limits.reason;
  protected readonly reason = new FormControl('', {
    nonNullable: true,
    validators: [
      ...(this.data.reason === 'required' ? [AppValidators.notBlank] : []),
      Validators.maxLength(Limits.reason),
    ],
  });

  protected error(): string {
    return errorMessage(this.reason.errors);
  }

  protected confirm(): void {
    if (this.data.reason && this.reason.invalid) {
      this.reason.markAsTouched();
      return;
    }
    this.ref.close(this.reason.value.trim());
  }
}
