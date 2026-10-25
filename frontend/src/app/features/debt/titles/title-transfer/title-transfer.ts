import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { PortfolioLookup } from '../../../organization/data/organization.lookups';
import { TitlesApi } from '../../data/debt.api';
import { TitleView } from '../../data/debt.models';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';

export interface TitleTransferData {
  title: TitleView;
  /** Resumo exibido antes da confirmação (devedor, credor, saldo). */
  details: readonly { label: string; value: string }[];
}

/** Transfere o título para outra carteira do mesmo credor, encerrando e abrindo titularidade. */
@Component({
  selector: 'app-title-transfer',
  imports: [ReactiveFormsModule, EntityPicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Transferir de carteira</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          O título passa para a carteira escolhida do mesmo credor. O período de titularidade atual é encerrado e fica no histórico.
        </p>
        <dl class="app-definition-list app-impact">
          @for (d of data.details; track d.label) {
            <dt>{{ d.label }}</dt>
            <dd>{{ d.value }}</dd>
          }
        </dl>
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <app-entity-picker
          [control]="form.controls.target"
          [source]="portfolioSource"
          label="Carteira de destino"
          hint="Somente carteiras do mesmo credor."
          [single]="true"
        />
        @if (form.controls.target.touched && form.controls.target.invalid) {
          <p class="app-field-error" role="alert">Escolha a carteira de destino.</p>
        }
        <app-field class="app-full-width mb-3" label="Motivo" [hintEnd]="(form.controls.reason.value.length) + ' / ' + (maxReason)" [error]="form.controls.reason.invalid ? (reasonError()) : undefined">
          <textarea appInput formControlName="reason" rows="3" [maxlength]="maxReason" required></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Transferir</button>
      </div>
    </form>
  `,
  styles: `
    .app-impact {
      margin: 0 0 16px;
      padding: 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
  `,
})
export class TitleTransfer {
  protected readonly data = inject<TitleTransferData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TitleView>>(DialogRef);
  private readonly api = inject(TitlesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.data.title.creditorId);
  protected readonly maxReason = Limits.reason;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    target: this.fb.control<string[]>([], [AppValidators.notBlank]),
    reason: ['', [AppValidators.notBlank, Validators.maxLength(Limits.reason)]],
  });

  protected reasonError(): string {
    return errorMessage(this.form.controls.reason.errors);
  }

  protected save(): void {
    const target = this.form.controls.target.value[0];
    if (target === this.data.title.portfolioId) {
      this.generalErrors.set(['O título já está nesta carteira.']);
      return;
    }
    if (this.form.invalid || !target || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.generalErrors.set([]);
    this.api
      .transfer([this.data.title.id], target, this.form.controls.reason.value.trim())
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: ([updated]) => {
          this.notifications.success('Título transferido de carteira.');
          this.ref.close(updated);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
