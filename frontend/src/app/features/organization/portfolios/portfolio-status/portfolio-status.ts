import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { finalize, startWith } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { PORTFOLIO_REASON_REQUIRED, PORTFOLIO_TRANSITIONS, describeStatus } from '../../../../shared/status/status';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { PortfoliosApi } from '../../data/organization.api';
import { PortfolioView } from '../../data/organization.models';

const CONSEQUENCES: Record<string, string> = {
  ACTIVE: 'Os títulos passam a ser elegíveis para cobrança dentro da vigência.',
  SUSPENDED: 'A operação é interrompida temporariamente; os títulos deixam de ser trabalhados.',
  RETURNED: 'A carteira é devolvida ao credor; títulos não recuperados saem da operação.',
  CLOSED: 'Encerramento definitivo. Não será possível reativar a carteira.',
};

/** Mudança de status com as transições permitidas pelo domínio e motivo quando exigido. */
@Component({
  selector: 'app-portfolio-status',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatRadioModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Alterar situação da carteira {{ portfolio.code }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p>Situação atual: <strong>{{ currentLabel }}</strong></p>
        @if (targets.length === 0) {
          <p class="app-alert app-alert--info">Carteira encerrada: não há transições disponíveis.</p>
        } @else {
          <mat-radio-group formControlName="status" class="app-form-stack" aria-label="Nova situação">
            @for (t of targets; track t.value) {
              <mat-radio-button [value]="t.value">
                <strong>{{ t.label }}</strong>
                <div class="app-muted">{{ t.consequence }}</div>
              </mat-radio-button>
            }
          </mat-radio-group>
          <mat-form-field class="app-full-width">
            <mat-label>{{ reasonRequired() ? 'Motivo (obrigatório)' : 'Motivo' }}</mat-label>
            <textarea matInput formControlName="reason" rows="3" [maxlength]="maxReason"></textarea>
            @if (form.controls.reason.invalid) {
              <mat-error>{{ reasonError() }}</mat-error>
            }
          </mat-form-field>
        }
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        @if (targets.length > 0) {
          <button mat-flat-button type="submit" [disabled]="saving()" [class.app-button-danger]="selected() === 'CLOSED'">
            Confirmar
          </button>
        }
      </mat-dialog-actions>
    </form>
  `,
})
export class PortfolioStatusDialog {
  protected readonly portfolio = inject<PortfolioView>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<PortfolioStatusDialog, PortfolioView>>(MatDialogRef);
  private readonly api = inject(PortfoliosApi);
  private readonly notifications = inject(NotificationService);

  protected readonly maxReason = Limits.reason;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);
  protected readonly currentLabel = describeStatus('portfolio', this.portfolio.status).label;
  protected readonly targets = (PORTFOLIO_TRANSITIONS[this.portfolio.status] ?? []).map((value) => ({
    value,
    label: describeStatus('portfolio', value).label,
    consequence: CONSEQUENCES[value] ?? '',
  }));

  protected readonly form = inject(FormBuilder).nonNullable.group({
    status: [this.targets[0]?.value ?? '', [AppValidators.notBlank]],
    reason: ['', [Validators.maxLength(Limits.reason)]],
  });

  protected readonly selected = toSignal(
    this.form.controls.status.valueChanges.pipe(startWith(this.form.controls.status.value)),
    { requireSync: true },
  );
  protected readonly reasonRequired = computed(() => PORTFOLIO_REASON_REQUIRED.includes(this.selected()));

  protected reasonError(): string {
    return errorMessage(this.form.controls.reason.errors);
  }

  protected save(): void {
    const { status, reason } = this.form.getRawValue();
    const reasonControl = this.form.controls.reason;
    if (this.reasonRequired() && !reason.trim()) {
      reasonControl.setErrors({ required: true });
      reasonControl.markAsTouched();
    }
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.generalErrors.set([]);
    this.api
      .changeStatus(this.portfolio.id, status, reason.trim() || null)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (updated) => {
          this.notifications.success(`Carteira agora está "${describeStatus('portfolio', updated.status).label}".`);
          this.ref.close(updated);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
