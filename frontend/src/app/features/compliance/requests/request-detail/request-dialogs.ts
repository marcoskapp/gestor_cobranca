import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { EntityPicker, EntitySource } from '../../../../shared/components/entity-picker/entity-picker';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { AppValidators } from '../../../../shared/validation/validators';

const RESPONSE_MAX = 4000;

export interface ResponseDialogData {
  title: string;
  message: string;
  label: string;
  confirmLabel: string;
  destructive?: boolean;
  details: readonly { label: string; value: string }[];
}

/** Resposta ao titular na conclusão ou fundamentação da recusa (até 4.000 caracteres). */
@Component({
  selector: 'app-request-response-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="app-dialog-message">{{ data.message }}</p>
      <dl class="app-definition-list rd-details">
        @for (d of data.details; track d.label) {
          <dt>{{ d.label }}</dt>
          <dd>{{ d.value }}</dd>
        }
      </dl>
      <mat-form-field class="app-full-width">
        <mat-label>{{ data.label }}</mat-label>
        <textarea matInput [formControl]="text" rows="6" [maxlength]="max" required></textarea>
        <mat-hint align="end">{{ text.value.length }} / {{ max }}</mat-hint>
        @if (text.invalid) {
          <mat-error>{{ error() }}</mat-error>
        }
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancelar</button>
      <button mat-flat-button type="button" [class.app-button-danger]="data.destructive" (click)="confirm()">{{ data.confirmLabel }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    .rd-details {
      margin: 0 0 16px;
      padding: 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
  `,
})
export class ResponseDialog {
  protected readonly data = inject<ResponseDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ResponseDialog, string>>(MatDialogRef);
  protected readonly max = RESPONSE_MAX;
  protected readonly text = inject(FormBuilder).nonNullable.control('', [AppValidators.notBlank, Validators.maxLength(RESPONSE_MAX)]);

  protected error(): string {
    return errorMessage(this.text.errors);
  }

  protected confirm(): void {
    if (this.text.invalid) {
      this.text.markAsTouched();
      return;
    }
    this.ref.close(this.text.value.trim());
  }
}

export interface PickDialogData {
  title: string;
  message: string;
  label: string;
  hint?: string;
  confirmLabel: string;
  source: EntitySource;
  /** Escolha obrigatória (atribuição) ou opcional (confirmação de identidade pelo documento). */
  required: boolean;
}

/** Escolha de um registro (titular ou responsável). Resultado: id escolhido, `null` (sem escolha) ou `undefined` (cancelado). */
@Component({
  selector: 'app-request-pick-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="app-dialog-message">{{ data.message }}</p>
      <app-entity-picker [control]="picked" [source]="data.source" [label]="data.label" [hint]="data.hint" [single]="true" />
      @if (picked.touched && picked.invalid) {
        <p class="app-field-error" role="alert">Escolha uma opção.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancelar</button>
      <button mat-flat-button type="button" (click)="confirm()">{{ data.confirmLabel }}</button>
    </mat-dialog-actions>
  `,
})
export class PickDialog {
  protected readonly data = inject<PickDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<PickDialog, string | null>>(MatDialogRef);
  protected readonly picked = inject(FormBuilder).nonNullable.control<string[]>([], this.data.required ? [AppValidators.notBlank] : []);

  protected confirm(): void {
    if (this.picked.invalid) {
      this.picked.markAsTouched();
      return;
    }
    this.ref.close(this.picked.value[0] ?? null);
  }
}
