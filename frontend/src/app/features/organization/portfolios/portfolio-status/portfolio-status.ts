import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, startWith } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { PORTFOLIO_REASON_REQUIRED, PORTFOLIO_TRANSITIONS, describeStatus } from '../../../../shared/status/status';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { PortfoliosApi } from '../../data/organization.api';
import { PortfolioView } from '../../data/organization.models';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { Icon } from '../../../../shared/components/icon/icon';

const CONSEQUENCES: Record<string, string> = {
  ACTIVE: 'Os títulos passam a ser elegíveis para cobrança dentro da vigência.',
  SUSPENDED: 'A operação é interrompida temporariamente; os títulos deixam de ser trabalhados.',
  RETURNED: 'A carteira é devolvida ao credor; títulos não recuperados saem da operação.',
  CLOSED: 'Encerramento definitivo. Não será possível reativar a carteira.',
};

/** Mudança de status com as transições permitidas pelo domínio e motivo quando exigido. */
@Component({
  selector: 'app-portfolio-status',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Alterar situação da carteira {{ portfolio.code }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p>Situação atual: <strong>{{ currentLabel }}</strong></p>
        @if (targets.length === 0) {
          <div class="alert alert-info d-flex align-items-start gap-2" role="note"><app-icon name="info-circle" /><span>Carteira encerrada: não há transições disponíveis.</span></div>
        } @else {
          <fieldset class="mb-3">
            <legend class="visually-hidden">Nova situação</legend>
            @for (t of targets; track t.value) {
              <div class="form-check mb-2">
                <input class="form-check-input" type="radio" formControlName="status" [id]="'portfolio-status-' + t.value" [value]="t.value" />
                <label class="form-check-label" [for]="'portfolio-status-' + t.value">
                  <span class="fw-medium">{{ t.label }}</span>
                  <span class="d-block app-muted small">{{ t.consequence }}</span>
                </label>
              </div>
            }
          </fieldset>
          <app-field [label]="(reasonRequired() ? 'Motivo (obrigatório)' : 'Motivo')" [error]="reasonError()">
            <textarea appInput formControlName="reason" rows="3" [maxlength]="maxReason"></textarea>
          </app-field>
        }
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        @if (targets.length > 0) {
          <button
            class="btn"
            type="submit"
            [disabled]="saving()"
            [class.btn-primary]="selected() !== 'CLOSED'"
            [class.btn-danger]="selected() === 'CLOSED'"
          >
            Confirmar
          </button>
        }
      </div>
    </form>
  `,
})
export class PortfolioStatusDialog {
  protected readonly portfolio = inject<PortfolioView>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PortfolioView>>(DialogRef);
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
