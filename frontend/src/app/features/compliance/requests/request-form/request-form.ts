import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { DocumentMask } from '../../../../shared/directives/document-mask';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators } from '../../../../shared/validation/validators';
import { DataSubjectRequestsApi } from '../../data/compliance.api';
import { REQUEST_CHANNEL_LABELS, REQUEST_TYPE_LABELS } from '../../data/compliance.labels';
import { DataSubjectRequestView, RequestChannel, RequestType } from '../../data/compliance.models';

type Field = 'type' | 'channel' | 'requesterName' | 'document' | 'requesterContact' | 'details';

/**
 * Registro da solicitação do titular (LGPD art. 18). O prazo é calculado pelo servidor a partir
 * do recebimento; documento e contato do requerente vão só no corpo e voltam mascarados.
 */
@Component({
  selector: 'app-request-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatInputModule, MatSelectModule, DocumentMask],
  template: `
    <h2 mat-dialog-title>Registrar solicitação do titular</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Tipo de pedido</mat-label>
            <mat-select formControlName="type">
              @for (t of types; track t.value) {
                <mat-option [value]="t.value">{{ t.label }}</mat-option>
              }
            </mat-select>
            @if (form.controls.type.invalid) {
              <mat-error>{{ message('type') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Canal de recebimento</mat-label>
            <mat-select formControlName="channel">
              @for (c of channels; track c.value) {
                <mat-option [value]="c.value">{{ c.label }}</mat-option>
              }
            </mat-select>
            @if (form.controls.channel.invalid) {
              <mat-error>{{ message('channel') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Nome do requerente</mat-label>
            <input matInput formControlName="requesterName" maxlength="200" autocomplete="off" />
            @if (form.controls.requesterName.invalid) {
              <mat-error>{{ message('requesterName') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>CPF ou CNPJ do titular</mat-label>
            <input matInput formControlName="document" appDocumentMask maxlength="18" autocomplete="off" />
            <mat-hint>Recomendado: localiza o titular na confirmação de identidade.</mat-hint>
            @if (form.controls.document.invalid) {
              <mat-error>{{ message('document') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Contato para resposta</mat-label>
            <input matInput formControlName="requesterContact" maxlength="254" autocomplete="off" />
            <mat-hint>E-mail ou telefone informado pelo requerente.</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Recebida em</mat-label>
            <input matInput [matDatepicker]="received" formControlName="receivedAt" [max]="today" placeholder="Hoje" />
            <mat-datepicker-toggle matIconSuffix [for]="received" />
            <mat-datepicker #received />
            <mat-hint>O prazo legal conta a partir do recebimento.</mat-hint>
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Descrição do pedido</mat-label>
            <textarea matInput formControlName="details" rows="4" maxlength="4000"></textarea>
            <mat-hint>Resuma o pedido. Não copie dados de saúde ou outros dados sensíveis.</mat-hint>
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Registrar solicitação</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class RequestForm {
  private readonly ref = inject<MatDialogRef<RequestForm, DataSubjectRequestView>>(MatDialogRef);
  private readonly api = inject(DataSubjectRequestsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly types = Object.entries(REQUEST_TYPE_LABELS).map(([value, label]) => ({ value: value as RequestType, label }));
  protected readonly channels = Object.entries(REQUEST_CHANNEL_LABELS).map(([value, label]) => ({ value: value as RequestChannel, label }));
  protected readonly today = new Date();
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    type: this.fb.control<RequestType | ''>('', [AppValidators.notBlank]),
    channel: this.fb.control<RequestChannel | ''>('', [AppValidators.notBlank]),
    requesterName: ['', [AppValidators.notBlank, Validators.maxLength(200)]],
    document: ['', [AppValidators.document()]],
    requesterContact: ['', [Validators.maxLength(254)]],
    details: ['', [Validators.maxLength(4000)]],
    receivedAt: this.fb.control<Date | null>(null),
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
      .open({
        type: v.type as RequestType,
        channel: v.channel as RequestChannel,
        requesterName: v.requesterName.trim(),
        document: v.document.trim() || null,
        requesterContact: v.requesterContact.trim() || null,
        details: v.details.trim() || null,
        receivedAt: receivedInstant(v.receivedAt, new Date()),
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success('Solicitação registrada. O prazo de atendimento já está correndo.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}

/** Recebimento: hoje (ou vazio) = agora; dias anteriores = meio-dia local (evita virar o dia no fuso). */
export function receivedInstant(date: Date | null, now: Date): string | null {
  if (!date) {
    return null;
  }
  const sameDay = date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate();
  return sameDay ? null : new Date(date.getFullYear(), date.getMonth(), date.getDate(), 12).toISOString();
}
