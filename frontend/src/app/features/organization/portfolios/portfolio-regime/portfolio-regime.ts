import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { Icon } from '../../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { PortfoliosApi } from '../../data/organization.api';
import { PORTFOLIO_REGIME_EFFECTS, PORTFOLIO_REGIME_LABELS, PortfolioRegime, PortfolioView } from '../../data/organization.models';

/**
 * Mudança do regime da carteira (consumo × empresarial, cobrança B2B §6), com motivo obrigatório e auditoria. O regime
 * não muda contrato nem política: só as regras de proteção que dependem dele.
 */
@Component({
  selector: 'app-portfolio-regime',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Alterar regime da carteira {{ portfolio.code }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p>Regime atual: <strong>{{ labels[portfolio.regime] }}</strong>. Contratos e política de negociação não mudam.</p>
        <fieldset class="mb-3">
          <legend class="visually-hidden">Novo regime</legend>
          @for (r of regimes; track r) {
            <div class="form-check mb-2">
              <input class="form-check-input" type="radio" formControlName="regime" [id]="'portfolio-regime-' + r" [value]="r" [attr.ngbAutofocus]="$first ? '' : null" />
              <label class="form-check-label" [for]="'portfolio-regime-' + r">
                <span class="fw-medium">{{ labels[r] }}</span>
                <span class="d-block app-muted small">{{ effects[r] }}</span>
              </label>
            </div>
          }
        </fieldset>
        <app-field label="Motivo" [error]="reasonError()">
          <textarea appInput formControlName="reason" rows="3" [maxlength]="maxReason" placeholder="Ex.: contrato de fornecimento entre empresas"></textarea>
        </app-field>
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Confirmar</button>
      </div>
    </form>
  `,
})
export class PortfolioRegimeDialog {
  protected readonly portfolio = inject<PortfolioView>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PortfolioView>>(DialogRef);
  private readonly api = inject(PortfoliosApi);
  private readonly notifications = inject(NotificationService);

  protected readonly labels = PORTFOLIO_REGIME_LABELS;
  protected readonly effects = PORTFOLIO_REGIME_EFFECTS;
  protected readonly regimes: PortfolioRegime[] = ['CONSUMER', 'BUSINESS'];
  protected readonly maxReason = Limits.reason;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    regime: [(this.portfolio.regime === 'BUSINESS' ? 'CONSUMER' : 'BUSINESS') as PortfolioRegime, [AppValidators.notBlank]],
    reason: ['', [AppValidators.notBlank, Validators.maxLength(Limits.reason)]],
  });

  protected reasonError(): string {
    const c = this.form.controls.reason;
    return c.touched ? errorMessage(c.errors) : '';
  }

  protected save(): void {
    const { regime, reason } = this.form.getRawValue();
    if (regime === this.portfolio.regime) {
      this.generalErrors.set(['A carteira já está neste regime.']);
      return;
    }
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.generalErrors.set([]);
    this.api
      .changeRegime(this.portfolio.id, regime, reason.trim())
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (updated) => {
          this.notifications.success(`Carteira agora no regime ${PORTFOLIO_REGIME_LABELS[updated.regime].toLowerCase()}.`);
          this.ref.close(updated);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
