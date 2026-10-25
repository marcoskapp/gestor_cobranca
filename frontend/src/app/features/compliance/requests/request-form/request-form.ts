import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
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
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../../shared/components/icon/icon';
import { fromLocalDate, toDateStruct, toLocalDate } from '../../../../shared/models/dates';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { DialogRef } from '../../../../shared/dialog/dialog-ref';

type Field = 'type' | 'channel' | 'requesterName' | 'document' | 'requesterContact' | 'details';

/**
 * Registro da solicitação do titular (LGPD art. 18). O prazo é calculado pelo servidor a partir
 * do recebimento; documento e contato do requerente vão só no corpo e voltam mascarados.
 */
@Component({
  selector: 'app-request-form',
  imports: [ReactiveFormsModule, DocumentMask, Icon, NgbInputDatepicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registrar solicitação do titular</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <div class="app-form-grid gap-3">
          <app-field label="Tipo de pedido" [error]="form.controls.type.invalid ? (message('type')) : undefined">
            <select appInput formControlName="type">
              @for (t of types; track t.value) {
                <option [ngValue]="t.value">{{ t.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Canal de recebimento" [error]="form.controls.channel.invalid ? (message('channel')) : undefined">
            <select appInput formControlName="channel">
              @for (c of channels; track c.value) {
                <option [ngValue]="c.value">{{ c.label }}</option>
              }
            </select>
          </app-field>
          <app-field class="app-span-all" label="Nome do requerente" [error]="form.controls.requesterName.invalid ? (message('requesterName')) : undefined">
            <input appInput formControlName="requesterName" maxlength="200" autocomplete="off" />
          </app-field>
          <app-field label="CPF ou CNPJ do titular" hint="Recomendado: localiza o titular na confirmação de identidade." [error]="form.controls.document.invalid ? (message('document')) : undefined">
            <input appInput formControlName="document" appDocumentMask maxlength="18" autocomplete="off" />
          </app-field>
          <app-field label="Contato para resposta" hint="E-mail ou telefone informado pelo requerente.">
            <input appInput formControlName="requesterContact" maxlength="254" autocomplete="off" />
          </app-field>
          <app-field label="Recebida em" hint="Vazio = hoje. O prazo legal conta a partir do recebimento.">
            <div class="input-group">
              <input appInput ngbDatepicker #received="ngbDatepicker" formControlName="receivedAt" [maxDate]="today" placeholder="dd/mm/aaaa" />
              <button type="button" class="btn btn-outline-secondary" (click)="received.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field class="app-span-all" label="Descrição do pedido" hint="Resuma o pedido. Não copie dados de saúde ou outros dados sensíveis.">
            <textarea appInput formControlName="details" rows="4" maxlength="4000"></textarea>
          </app-field>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Registrar solicitação</button>
      </div>
    </form>
  `,
})
export class RequestForm {
  private readonly ref = inject<DialogRef<DataSubjectRequestView>>(DialogRef);
  private readonly api = inject(DataSubjectRequestsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly types = Object.entries(REQUEST_TYPE_LABELS).map(([value, label]) => ({ value: value as RequestType, label }));
  protected readonly channels = Object.entries(REQUEST_CHANNEL_LABELS).map(([value, label]) => ({ value: value as RequestChannel, label }));
  protected readonly today = toDateStruct(toLocalDate(new Date())!);
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
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    receivedAt: this.fb.control<string | null>(null),
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
        receivedAt: receivedInstant(fromLocalDate(v.receivedAt), new Date()),
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
