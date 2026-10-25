import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { startWith } from 'rxjs';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { REASON_MAX, REASON_MIN } from '../data/incentive.rules';

export interface ReasonDialogData {
  title: string;
  message: string;
  label: string;
  confirmLabel: string;
  /** Mínimo de caracteres (padrão 10, como o servidor); 0 = opcional. */
  min?: number;
  max?: number;
  destructive?: boolean;
  details?: readonly { label: string; value: string }[];
  warning?: string;
}

/**
 * Confirmação com motivo de tamanho mínimo (o `ConfirmDialog` compartilhado não valida mínimo, e
 * os motivos do incentive exigem 10 a 2000 caracteres). Fecha com o texto ou `undefined`.
 */
@Component({
  selector: 'app-incentive-reason-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.title }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">{{ data.message }}</p>
        @if (data.details?.length) {
          <dl class="app-definition-list mb-3">
            @for (d of data.details; track d.label) {
              <dt>{{ d.label }}</dt>
              <dd>{{ d.value }}</dd>
            }
          </dl>
        }
        @if (data.warning) {
          <div class="alert alert-warning d-flex align-items-start gap-2" role="note"><app-icon name="exclamation-triangle" /><span>{{ data.warning }}</span></div>
        }
        <app-field [label]="data.label" [hint]="hint()" [error]="'Escreva ao menos ' + min + ' caracteres.'">
          <textarea appInput ngbAutofocus formControlName="text" rows="3" [attr.maxlength]="max"></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn" [class.btn-danger]="data.destructive" [class.btn-primary]="!data.destructive" type="submit">{{ data.confirmLabel }}</button>
      </div>
    </form>
  `,
})
export class ReasonDialog {
  protected readonly data = inject<ReasonDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<string>>(DialogRef);
  protected readonly min = this.data.min ?? REASON_MIN;
  protected readonly max = this.data.max ?? REASON_MAX;
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    text: ['', [Validators.maxLength(this.max), (c: AbstractControl) => (this.min > 0 && (c.value as string).trim().length < this.min ? { minlength: true } : null)]],
  });
  private readonly value = toSignal(this.form.controls.text.valueChanges.pipe(startWith('')), { initialValue: '' });
  protected readonly hint = computed(() => `${this.value().trim().length}/${this.max}${this.min > 0 ? ` · mínimo ${this.min} caracteres` : ' · opcional'}`);

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.ref.close(this.form.getRawValue().text.trim());
  }
}
