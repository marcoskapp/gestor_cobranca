import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { EntityPicker, EntitySource } from '../../../../shared/components/entity-picker/entity-picker';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { AppValidators } from '../../../../shared/validation/validators';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';

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
  imports: [ReactiveFormsModule, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.title }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <p class="app-dialog-message">{{ data.message }}</p>
      <dl class="app-definition-list rd-details">
        @for (d of data.details; track d.label) {
          <dt>{{ d.label }}</dt>
          <dd>{{ d.value }}</dd>
        }
      </dl>
      <app-field class="app-full-width" [label]="(data.label)" [hintEnd]="(text.value.length) + ' / ' + (max)" [error]="text.invalid ? (error()) : undefined">
        <textarea appInput [formControl]="text" rows="6" [maxlength]="max" required></textarea>
      </app-field>
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
      <button class="btn" type="button" [class.btn-primary]="!data.destructive" [class.btn-danger]="data.destructive" (click)="confirm()">{{ data.confirmLabel }}</button>
    </div>
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
  protected readonly data = inject<ResponseDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<string>>(DialogRef);
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
  imports: [ReactiveFormsModule, EntityPicker, ...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.title }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <p class="app-dialog-message">{{ data.message }}</p>
      <app-entity-picker [control]="picked" [source]="data.source" [label]="data.label" [hint]="data.hint" [single]="true" />
      @if (picked.touched && picked.invalid) {
        <p class="app-field-error" role="alert">Escolha uma opção.</p>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
      <button class="btn btn-primary" type="button" (click)="confirm()">{{ data.confirmLabel }}</button>
    </div>
  `,
})
export class PickDialog {
  protected readonly data = inject<PickDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<string | null>>(DialogRef);
  protected readonly picked = inject(FormBuilder).nonNullable.control<string[]>([], this.data.required ? [AppValidators.notBlank] : []);

  protected confirm(): void {
    if (this.picked.invalid) {
      this.picked.markAsTouched();
      return;
    }
    this.ref.close(this.picked.value[0] ?? null);
  }
}
