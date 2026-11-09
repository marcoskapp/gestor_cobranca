import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { toLocalDate } from '../../../shared/models/dates';
import { errorMessage } from '../../../shared/validation/error-messages';
import { AppValidators } from '../../../shared/validation/validators';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { CHANNEL_LABELS, ComplaintChannel, ComplaintSubject, ComplaintView, ComplaintsApi, SUBJECT_LABELS } from './complaint.data';

/** Vínculos pré-preenchidos quando o registro parte de outra tela (caso, devedor). */
export interface ComplaintFormData {
  creditorId?: string | null;
  debtorId?: string | null;
  caseId?: string | null;
}

const SUMMARY_MAX = 4000;

/**
 * Registro de reclamação recebida por canal externo. O prazo de resposta vem da regra do canal, salvo quando o canal
 * informa outro na notificação (ex.: Procon).
 */
@Component({
  selector: 'app-complaint-form',
  imports: [ReactiveFormsModule, EntityPicker, Icon, NgbInputDatepicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registrar reclamação</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        @if (data.caseId || data.debtorId) {
          <p class="app-dialog-message">A reclamação fica vinculada ao {{ data.caseId ? 'caso' : 'devedor' }} de onde você veio.</p>
        }
        @if (!data.creditorId) {
          <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
          @if (form.controls.creditor.touched && !form.controls.creditor.value.length) {
            <div class="invalid-feedback d-block mb-2">Escolha o credor da cobrança reclamada.</div>
          }
        }
        <div class="app-form-grid gap-3">
          <app-field label="Canal" [error]="message('channel')">
            <select appInput formControlName="channel" ngbAutofocus>
              <option value="" disabled>Selecione</option>
              @for (c of channels; track c.value) {
                <option [ngValue]="c.value">{{ c.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Protocolo do canal (opcional)" hint="Número da reclamação no Procon, consumidor.gov.br, Bacen…">
            <input appInput formControlName="externalProtocol" maxlength="60" />
          </app-field>
          <app-field class="app-span-all" label="Assunto" [error]="message('subject')">
            <select appInput formControlName="subject">
              <option value="" disabled>Selecione</option>
              @for (s of subjects; track s.value) {
                <option [ngValue]="s.value">{{ s.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Recebida em" [error]="message('receivedOn')">
            <div class="input-group">
              <input appInput ngbDatepicker #received="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="receivedOn" [maxDate]="todayStruct" />
              <button type="button" class="btn btn-outline-secondary" (click)="received.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field label="Prazo informado pelo canal (opcional)" hint="Deixe em branco para usar a regra do canal.">
            <div class="input-group">
              <input appInput ngbDatepicker #due="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="responseDueDate" />
              <button type="button" class="btn btn-outline-secondary" (click)="due.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field
            class="app-span-all"
            label="Resumo da reclamação"
            [hint]="'Não inclua dados de saúde nem documentos além do necessário. ' + form.controls.summary.value.length + '/' + summaryMax"
            [error]="message('summary')"
          >
            <textarea appInput formControlName="summary" rows="4" [attr.maxlength]="summaryMax"></textarea>
          </app-field>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Registrar reclamação</button>
      </div>
    </form>
  `,
})
export class ComplaintFormDialog {
  protected readonly data = inject<ComplaintFormData | null>(DIALOG_DATA, { optional: true }) ?? {};
  private readonly ref = inject<DialogRef<ComplaintView>>(DialogRef);
  private readonly api = inject(ComplaintsApi);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly summaryMax = SUMMARY_MAX;
  protected readonly channels = Object.entries(CHANNEL_LABELS).map(([value, label]) => ({ value: value as ComplaintChannel, label }));
  protected readonly subjects = Object.entries(SUBJECT_LABELS).map(([value, label]) => ({ value: value as ComplaintSubject, label }));
  private readonly today = new Date();
  protected readonly todayStruct = { year: this.today.getFullYear(), month: this.today.getMonth() + 1, day: this.today.getDate() };
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    creditor: this.fb.control<string[]>(this.data.creditorId ? [this.data.creditorId] : []),
    channel: this.fb.control<ComplaintChannel | ''>('', [AppValidators.notBlank]),
    externalProtocol: ['', [Validators.maxLength(60)]],
    subject: this.fb.control<ComplaintSubject | ''>('', [AppValidators.notBlank]),
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    receivedOn: this.fb.control<string | null>(toLocalDate(this.today), [Validators.required]),
    responseDueDate: this.fb.control<string | null>(null),
    summary: ['', [AppValidators.notBlank, Validators.maxLength(SUMMARY_MAX)]],
  });

  protected message(field: 'channel' | 'subject' | 'receivedOn' | 'summary'): string | undefined {
    const c = this.form.controls[field];
    return c.invalid ? errorMessage(c.errors) : undefined;
  }

  protected save(): void {
    const v = this.form.getRawValue();
    if (this.form.invalid || !v.creditor.length || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api
      .register({
        creditorId: v.creditor[0],
        debtorId: this.data.debtorId ?? null,
        caseId: this.data.caseId ?? null,
        channel: v.channel as ComplaintChannel,
        externalProtocol: v.externalProtocol.trim() || null,
        subject: v.subject as ComplaintSubject,
        summary: v.summary.trim(),
        receivedAt: receivedInstant(v.receivedOn),
        responseDueDate: v.responseDueDate || null,
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({ next: (c) => this.ref.close(c), error: (e) => this.error.set(userMessage(toApiError(e))) });
  }
}

/** Data escolhida → instante: hoje = agora; dia anterior = meio-dia local (fora de qualquer virada de fuso). */
export function receivedInstant(day: string | null, now = new Date()): string | null {
  if (!day || day === toLocalDate(now)) {
    return null;
  }
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d, 12, 0, 0).toISOString();
}
