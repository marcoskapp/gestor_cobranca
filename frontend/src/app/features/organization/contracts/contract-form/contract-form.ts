import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { fromLocalDate, toLocalDate } from '../../../../shared/models/dates';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { ContractsApi } from '../../data/organization.api';
import { ContractTermsRequest, ContractView } from '../../data/organization.models';

export interface ContractFormData {
  creditorId: string;
  contract: ContractView | null;
}

type Field = 'number' | 'startDate' | 'endDate' | 'returnAfterDays' | 'settlementDay' | 'notes';

/** Novo contrato de prestação de serviço ou alteração dos termos de um contrato vigente. */
@Component({
  selector: 'app-contract-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatDatepickerModule],
  template: `
    <h2 mat-dialog-title>{{ data.contract ? 'Termos do contrato ' + data.contract.number : 'Novo contrato' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-grid">
          <mat-form-field class="app-span-all">
            <mat-label>Número do contrato</mat-label>
            <input matInput formControlName="number" [maxlength]="limits.contractNumber" />
            @if (form.controls.number.invalid) {
              <mat-error>{{ message('number') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Início da vigência</mat-label>
            <input matInput [matDatepicker]="start" formControlName="startDate" placeholder="dd/mm/aaaa" />
            <mat-datepicker-toggle matIconSuffix [for]="start" />
            <mat-datepicker #start />
            @if (form.controls.startDate.invalid) {
              <mat-error>{{ message('startDate') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Fim da vigência</mat-label>
            <input matInput [matDatepicker]="end" formControlName="endDate" placeholder="dd/mm/aaaa" />
            <mat-datepicker-toggle matIconSuffix [for]="end" />
            <mat-datepicker #end />
            <mat-hint>Opcional (prazo indeterminado)</mat-hint>
            @if (form.controls.endDate.invalid) {
              <mat-error>{{ message('endDate') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Devolução após (dias)</mat-label>
            <input matInput type="number" formControlName="returnAfterDays" min="1" />
            <mat-hint>Prazo para devolver títulos não recuperados</mat-hint>
            @if (form.controls.returnAfterDays.invalid) {
              <mat-error>{{ message('returnAfterDays') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Dia do repasse</mat-label>
            <input matInput type="number" formControlName="settlementDay" min="1" max="28" />
            <mat-hint>Dia do mês (1 a 28)</mat-hint>
            @if (form.controls.settlementDay.invalid) {
              <mat-error>{{ message('settlementDay') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Observações</mat-label>
            <textarea matInput formControlName="notes" rows="3" [maxlength]="limits.contractNotes"></textarea>
            @if (form.controls.notes.invalid) {
              <mat-error>{{ message('notes') }}</mat-error>
            }
          </mat-form-field>
        </div>
        @if (form.hasError('dateRange')) {
          <p class="app-field-error" role="alert">O fim da vigência não pode ser anterior ao início.</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Salvar</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class ContractForm {
  protected readonly data = inject<ContractFormData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ContractForm, ContractView>>(MatDialogRef);
  private readonly api = inject(ContractsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly limits = Limits;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  protected readonly form = inject(FormBuilder).group(
    {
      number: [
        { value: this.data.contract?.number ?? '', disabled: this.data.contract !== null },
        [AppValidators.notBlank, Validators.maxLength(Limits.contractNumber)],
      ],
      startDate: [fromLocalDate(this.data.contract?.startDate), [Validators.required]],
      endDate: [fromLocalDate(this.data.contract?.endDate)],
      returnAfterDays: [this.data.contract?.returnAfterDays ?? null, [Validators.min(1)]],
      settlementDay: [this.data.contract?.settlementDay ?? 10, [Validators.required, Validators.min(1), Validators.max(28)]],
      notes: [this.data.contract?.notes ?? '', [Validators.maxLength(Limits.contractNotes)]],
    },
    { validators: AppValidators.dateRange('startDate', 'endDate') },
  );

  protected message(field: Field): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const terms: ContractTermsRequest = {
      startDate: toLocalDate(v.startDate) as string,
      endDate: toLocalDate(v.endDate),
      returnAfterDays: v.returnAfterDays ?? null,
      settlementDay: Number(v.settlementDay),
      notes: v.notes?.trim() || null,
    };
    const request$ = this.data.contract
      ? this.api.updateTerms(this.data.contract.id, terms)
      : this.api.create({ creditorId: this.data.creditorId, number: (v.number ?? '').trim(), terms });
    this.saving.set(true);
    this.generalErrors.set([]);
    request$.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.notifications.success(this.data.contract ? 'Termos atualizados.' : 'Contrato cadastrado.');
        this.ref.close(saved);
      },
      error: (err: unknown) => {
        const unmatched = applyServerErrors(this.form, toApiError(err), {
          'terms.startDate': 'startDate',
          'terms.endDate': 'endDate',
          'terms.returnAfterDays': 'returnAfterDays',
          'terms.settlementDay': 'settlementDay',
          'terms.notes': 'notes',
        });
        this.generalErrors.set(unmatched);
      },
    });
  }
}
