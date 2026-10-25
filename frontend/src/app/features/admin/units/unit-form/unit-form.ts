import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits, Patterns } from '../../../../shared/validation/validators';
import { BusinessUnitsApi } from '../../../organization/data/organization.api';
import { BusinessUnitView } from '../../../organization/data/organization.models';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { Icon } from '../../../../shared/components/icon/icon';

@Component({
  selector: 'app-unit-form',
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule, ...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ unit ? 'Editar unidade' : 'Nova unidade' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
        }
        <div class="vstack gap-3">
          <app-field label="Código" [error]="message('code')">
            <input appInput formControlName="code" autocomplete="off" [maxlength]="limits.unitCode" class="font-monospace" [attr.ngbAutofocus]="unit ? null : ''" />
          </app-field>
          <app-field label="Nome" [error]="message('name')">
            <input appInput formControlName="name" autocomplete="off" [maxlength]="limits.unitName" [attr.ngbAutofocus]="unit ? '' : null" />
          </app-field>
          @if (unit) {
            <div class="form-check form-switch"><input class="form-check-input" type="checkbox" role="switch" id="unitfo-active" formControlName="active"  /><label class="form-check-label" for="unitfo-active">Unidade ativa</label></div>
          }
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ unit ? 'Salvar' : 'Criar unidade' }}</button>
      </div>
    </form>
  `,
})
export class UnitForm {
  protected readonly unit = inject<BusinessUnitView | null>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<BusinessUnitView>>(DialogRef);
  private readonly api = inject(BusinessUnitsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly limits = Limits;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    code: [
      { value: this.unit?.code ?? '', disabled: this.unit !== null },
      [AppValidators.notBlank, Validators.maxLength(Limits.unitCode), Validators.pattern(Patterns.code)],
    ],
    name: [this.unit?.name ?? '', [AppValidators.notBlank, Validators.maxLength(Limits.unitName)]],
    active: [this.unit?.active ?? true],
  });

  protected message(control: 'code' | 'name'): string {
    const errors = this.form.controls[control].errors;
    return errors?.['pattern'] ? 'Use letras, números, "-" ou "_".' : errorMessage(errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.generalErrors.set([]);
    (this.unit ? this.api.update(this.unit.id, v.name.trim(), v.active) : this.api.create(v.code.trim(), v.name.trim()))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success(this.unit ? 'Unidade atualizada.' : 'Unidade criada.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
