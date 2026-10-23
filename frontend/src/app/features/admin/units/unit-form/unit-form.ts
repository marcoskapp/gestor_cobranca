import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits, Patterns } from '../../../../shared/validation/validators';
import { BusinessUnitsApi } from '../../../organization/data/organization.api';
import { BusinessUnitView } from '../../../organization/data/organization.models';

@Component({
  selector: 'app-unit-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSlideToggleModule],
  template: `
    <h2 mat-dialog-title>{{ unit ? 'Editar unidade' : 'Nova unidade' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-stack">
          <mat-form-field>
            <mat-label>Código</mat-label>
            <input matInput formControlName="code" autocomplete="off" [maxlength]="limits.unitCode" class="app-mono" />
            @if (form.controls.code.invalid) {
              <mat-error>{{ message('code') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Nome</mat-label>
            <input matInput formControlName="name" autocomplete="off" [maxlength]="limits.unitName" />
            @if (form.controls.name.invalid) {
              <mat-error>{{ message('name') }}</mat-error>
            }
          </mat-form-field>
          @if (unit) {
            <mat-slide-toggle formControlName="active">Unidade ativa</mat-slide-toggle>
          }
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">{{ unit ? 'Salvar' : 'Criar unidade' }}</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class UnitForm {
  protected readonly unit = inject<BusinessUnitView | null>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<UnitForm, BusinessUnitView>>(MatDialogRef);
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
