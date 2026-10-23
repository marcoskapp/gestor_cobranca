import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
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

export interface LegalBasisFormData {
  creditorId: string;
  creditorName: string;
}

type Field = 'legalBasis' | 'contractReference' | 'validFrom' | 'validUntil' | 'legalResponsible' | 'retentionMonths' | 'notes';

/** Registro da base legal do tratamento (art. 7º da LGPD) para o credor ou uma carteira. */
@Component({
  selector: 'app-legal-basis-form',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    EntityPicker,
  ],
  template: `
    <h2 mat-dialog-title>Registrar base legal</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p class="app-dialog-message">Credor: <strong>{{ data.creditorName }}</strong>. As vigências do mesmo alcance não podem se sobrepor.</p>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <app-entity-picker
          [control]="form.controls.portfolio"
          [source]="portfolioSource"
          label="Carteira (opcional)"
          hint="Vazio = vale para todas as carteiras do credor sem registro próprio."
          [single]="true"
        />
        <div class="app-form-grid">
          <mat-form-field class="app-span-all">
            <mat-label>Base legal</mat-label>
            <mat-select formControlName="legalBasis">
              @for (b of bases; track b.value) {
                <mat-option [value]="b.value">{{ b.label }}</mat-option>
              }
            </mat-select>
            @if (form.controls.legalBasis.invalid) {
              <mat-error>{{ message('legalBasis') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Papel da assessoria</mat-label>
            <mat-select formControlName="processingRole">
              @for (r of roles; track r.value) {
                <mat-option [value]="r.value">{{ r.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Referência contratual</mat-label>
            <input matInput formControlName="contractReference" maxlength="120" placeholder="Ex.: Contrato 2026/015, cláusula 9" />
            @if (form.controls.contractReference.invalid) {
              <mat-error>{{ message('contractReference') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Início da vigência</mat-label>
            <input matInput [matDatepicker]="from" formControlName="validFrom" />
            <mat-datepicker-toggle matIconSuffix [for]="from" />
            <mat-datepicker #from />
            @if (form.controls.validFrom.invalid) {
              <mat-error>{{ message('validFrom') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Fim da vigência</mat-label>
            <input matInput [matDatepicker]="until" formControlName="validUntil" />
            <mat-datepicker-toggle matIconSuffix [for]="until" />
            <mat-datepicker #until />
            <mat-hint>Vazio = sem prazo.</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Responsável jurídico</mat-label>
            <input matInput formControlName="legalResponsible" maxlength="150" />
            @if (form.controls.legalResponsible.invalid) {
              <mat-error>{{ message('legalResponsible') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Retenção após o encerramento (meses)</mat-label>
            <input matInput type="number" formControlName="retentionMonths" min="1" max="1200" />
            <mat-hint>Prazo mínimo de guarda exigido para este credor.</mat-hint>
            @if (form.controls.retentionMonths.invalid) {
              <mat-error>{{ message('retentionMonths') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Observações</mat-label>
            <textarea matInput formControlName="notes" rows="2" maxlength="1000"></textarea>
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Registrar base legal</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class LegalBasisForm {
  protected readonly data = inject<LegalBasisFormData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<LegalBasisForm, LegalBasisView>>(MatDialogRef);
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
