import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { ContractsApi } from '../../data/organization.api';
import { ContractTermsRequest, ContractView } from '../../data/organization.models';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { Icon } from '../../../../shared/components/icon/icon';

export interface ContractFormData {
  creditorId: string;
  contract: ContractView | null;
}

type Field = 'number' | 'startDate' | 'endDate' | 'returnAfterDays' | 'settlementDay' | 'notes';

/** Novo contrato de prestação de serviço ou alteração dos termos de um contrato vigente. */
@Component({
  selector: 'app-contract-form',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, NgbInputDatepicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.contract ? 'Termos do contrato ' + data.contract.number : 'Novo contrato' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
        }
        <div class="app-form-grid row-gap-3">
          <app-field class="app-span-all" label="Número do contrato" [error]="message('number')">
            <input appInput formControlName="number" [maxlength]="limits.contractNumber" [attr.ngbAutofocus]="data.contract ? null : ''" />
          </app-field>
          <app-field label="Início da vigência" [error]="message('startDate')">
            <div class="input-group">
              <input appInput formControlName="startDate" [attr.ngbAutofocus]="data.contract ? '' : null" ngbDatepicker #start="ngbDatepicker" container="body" placeholder="dd/mm/aaaa" autocomplete="off" />
              <button class="btn btn-outline-secondary" type="button" (click)="start.toggle()" aria-label="Abrir calendário: Início da vigência">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field label="Fim da vigência" hint="Opcional (prazo indeterminado)" [error]="message('endDate')">
            <div class="input-group">
              <input appInput formControlName="endDate" ngbDatepicker #end="ngbDatepicker" container="body" placeholder="dd/mm/aaaa" autocomplete="off" />
              <button class="btn btn-outline-secondary" type="button" (click)="end.toggle()" aria-label="Abrir calendário: Fim da vigência">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field label="Devolução após (dias)" hint="Prazo para devolver títulos não recuperados" [error]="message('returnAfterDays')">
            <input appInput type="number" formControlName="returnAfterDays" min="1" />
          </app-field>
          <app-field label="Dia do repasse" hint="Dia do mês (1 a 28)" [error]="message('settlementDay')">
            <input appInput type="number" formControlName="settlementDay" min="1" max="28" />
          </app-field>
          <app-field class="app-span-all" label="Observações" [error]="message('notes')">
            <textarea appInput formControlName="notes" rows="3" [maxlength]="limits.contractNotes"></textarea>
          </app-field>
        </div>
        @if (form.hasError('dateRange')) {
          <div class="invalid-feedback d-block mt-2" role="alert">O fim da vigência não pode ser anterior ao início.</div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar</button>
      </div>
    </form>
  `,
})
export class ContractForm {
  protected readonly data = inject<ContractFormData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ContractView>>(DialogRef);
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
      // modelo ISO (yyyy-MM-dd) do ngbDatepicker
      startDate: [this.data.contract?.startDate ?? null, [Validators.required]],
      endDate: [this.data.contract?.endDate ?? null],
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
      startDate: v.startDate as string,
      endDate: v.endDate || null,
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
