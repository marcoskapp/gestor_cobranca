import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { toLocalDate } from '../../../shared/models/dates';
import { errorMessage } from '../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { PortfolioLookup } from '../../organization/data/organization.lookups';
import { LegalBasesApi } from '../data/compliance.api';
import { LEGAL_BASIS_LABELS, PROCESSING_ROLE_LABELS } from '../data/compliance.labels';
import { LegalBasisCode, LegalBasisView, ProcessingRole } from '../data/compliance.models';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';

export interface LegalBasisFormData {
  creditorId: string;
  creditorName: string;
}

type Field = 'legalBasis' | 'contractReference' | 'validFrom' | 'validUntil' | 'legalResponsible' | 'retentionMonths' | 'notes';

/** Registro da base legal do tratamento (art. 7º da LGPD) para o credor ou uma carteira. */
@Component({
  selector: 'app-legal-basis-form',
  imports: [ReactiveFormsModule, EntityPicker, Icon, NgbInputDatepicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registrar base legal</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">Credor: <strong>{{ data.creditorName }}</strong>. As vigências do mesmo alcance não podem se sobrepor.</p>
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <app-entity-picker
          [control]="form.controls.portfolio"
          [source]="portfolioSource"
          label="Carteira (opcional)"
          hint="Vazio = vale para todas as carteiras do credor sem registro próprio."
          [single]="true"
        />
        <div class="app-form-grid gap-3">
          <app-field class="app-span-all" label="Base legal" [error]="form.controls.legalBasis.invalid ? (message('legalBasis')) : undefined">
            <select appInput formControlName="legalBasis">
              @for (b of bases; track b.value) {
                <option [ngValue]="b.value">{{ b.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Papel da assessoria">
            <select appInput formControlName="processingRole">
              @for (r of roles; track r.value) {
                <option [ngValue]="r.value">{{ r.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Referência contratual" [error]="form.controls.contractReference.invalid ? (message('contractReference')) : undefined">
            <input appInput formControlName="contractReference" maxlength="120" placeholder="Ex.: Contrato 2026/015, cláusula 9" />
          </app-field>
          <app-field label="Início da vigência" [error]="form.controls.validFrom.invalid ? (message('validFrom')) : undefined">
            <div class="input-group">
              <input appInput ngbDatepicker #from="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="validFrom" />
              <button type="button" class="btn btn-outline-secondary" (click)="from.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field label="Fim da vigência" hint="Vazio = sem prazo.">
            <div class="input-group">
              <input appInput ngbDatepicker #until="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="validUntil" />
              <button type="button" class="btn btn-outline-secondary" (click)="until.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field label="Responsável jurídico" [error]="form.controls.legalResponsible.invalid ? (message('legalResponsible')) : undefined">
            <input appInput formControlName="legalResponsible" maxlength="150" />
          </app-field>
          <app-field label="Retenção após o encerramento (meses)" hint="Prazo mínimo de guarda exigido para este credor." [error]="form.controls.retentionMonths.invalid ? (message('retentionMonths')) : undefined">
            <input appInput type="number" formControlName="retentionMonths" min="1" max="1200" />
          </app-field>
          <app-field class="app-span-all" label="Observações">
            <textarea appInput formControlName="notes" rows="2" maxlength="1000"></textarea>
          </app-field>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Registrar base legal</button>
      </div>
    </form>
  `,
})
export class LegalBasisForm {
  protected readonly data = inject<LegalBasisFormData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<LegalBasisView>>(DialogRef);
  private readonly api = inject(LegalBasesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.data.creditorId);
  protected readonly bases = Object.entries(LEGAL_BASIS_LABELS).map(([value, label]) => ({ value: value as LegalBasisCode, label }));
  protected readonly roles = Object.entries(PROCESSING_ROLE_LABELS).map(([value, label]) => ({ value: value as ProcessingRole, label }));
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    portfolio: this.fb.control<string[]>([]),
    legalBasis: this.fb.control<LegalBasisCode | ''>('', [AppValidators.notBlank]),
    processingRole: this.fb.control<ProcessingRole>('OPERADOR'),
    contractReference: ['', [AppValidators.notBlank, Validators.maxLength(120)]],
    validFrom: this.fb.control<Date | null>(new Date(), [Validators.required]),
    validUntil: this.fb.control<Date | null>(null),
    legalResponsible: ['', [AppValidators.notBlank, Validators.maxLength(150)]],
    retentionMonths: this.fb.control<number | null>(60, [Validators.required, Validators.min(1), Validators.max(1200)]),
    notes: ['', [Validators.maxLength(1000)]],
  });

  protected message(field: Field): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.generalErrors.set([]);
    this.api
      .register({
        creditorId: this.data.creditorId,
        portfolioId: v.portfolio[0] ?? null,
        legalBasis: v.legalBasis as LegalBasisCode,
        processingRole: v.processingRole,
        contractReference: v.contractReference.trim(),
        validFrom: toLocalDate(v.validFrom)!,
        validUntil: toLocalDate(v.validUntil),
        legalResponsible: v.legalResponsible.trim(),
        retentionMonths: v.retentionMonths ?? 0,
        notes: v.notes.trim() || null,
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success('Base legal registrada.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
