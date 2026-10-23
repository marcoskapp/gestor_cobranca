import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
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

export interface TitleTransferData {
  title: TitleView;
  /** Resumo exibido antes da confirmação (devedor, credor, saldo). */
  details: readonly { label: string; value: string }[];
}

/** Transfere o título para outra carteira do mesmo credor, encerrando e abrindo titularidade. */
@Component({
  selector: 'app-title-transfer',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Transferir de carteira</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
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
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
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
        <mat-form-field class="app-full-width">
          <mat-label>Motivo</mat-label>
          <textarea matInput formControlName="reason" rows="3" [maxlength]="maxReason" required></textarea>
          <mat-hint align="end">{{ form.controls.reason.value.length }} / {{ maxReason }}</mat-hint>
          @if (form.controls.reason.invalid) {
            <mat-error>{{ reasonError() }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Transferir</button>
      </mat-dialog-actions>
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
  protected readonly data = inject<TitleTransferData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<TitleTransfer, TitleView>>(MatDialogRef);
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
