import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { catchError, concatMap, finalize, from, map, of, toArray } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { toDateStruct } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { EVIDENCE_ACCEPT, evidenceProblem } from '../../compliance/data/compliance.labels';
import { DisputesApi } from '../data/collection.api';
import { DISPUTE_ORIGIN_LABELS, DISPUTE_TYPE_LABELS } from '../data/collection.labels';
import { DisputeOrigin, DisputeType, DisputeView, SheetTitle } from '../data/collection.models';
import { zonedNow } from '../data/collection.rules';

export interface DisputeDialogData {
  caseId: string;
  debtorName: string;
  /** Títulos em cobrança do caso (vazio na seleção = todos). */
  titles: readonly SheetTitle[];
}

const formatDate = (d: string) => d.split('-').reverse().join('/');
const DESCRIPTION_MAX = 4000;
const MAX_FILES = 20;

/**
 * Abre uma contestação (F21) com evidências. Bloqueante por padrão: suspende a cobrança e o
 * contato dos títulos até a resolução. As evidências são enviadas logo após a abertura, uma a uma
 * (o servidor calcula o SHA-256 e registra o envio).
 */
@Component({
  selector: 'app-dispute-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, Amount, AppDatePipe, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Abrir contestação</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of errors(); track e) {
          <div class="alert alert-danger" role="alert">{{ e }}</div>
        }
        <p class="app-dialog-message">Devedor: <strong>{{ data.debtorName }}</strong></p>
        @if (data.titles.length > 1) {
          <fieldset class="dd-titles">
            <legend class="form-label">Títulos contestados <span class="app-muted">(nenhum marcado = todos em cobrança)</span></legend>
            @for (t of data.titles; track t.titleId) {
              <div class="form-check">
                <input
                  class="form-check-input"
                  type="checkbox"
                  [id]="'dd-title-' + t.titleId"
                  #titleBox
                  [checked]="selected().has(t.titleId)"
                  (change)="toggle(t.titleId, titleBox.checked)"
                />
                <label class="form-check-label" [for]="'dd-title-' + t.titleId">
                  <span class="app-numeric">{{ t.externalId }}{{ t.installmentNumber ? '/' + t.installmentNumber : '' }}</span>
                  · venc. {{ t.dueDate | appDate: 'date' }} · <app-amount [value]="t.openBalance" />
                </label>
              </div>
            }
          </fieldset>
        }
        <div class="row g-3">
          <app-field class="col-sm-6" label="Motivo">
            <select appInput formControlName="type" required ngbAutofocus>
              <option value="" disabled>Selecione</option>
              @for (t of types; track t.value) {
                <option [ngValue]="t.value">{{ t.label }}</option>
              }
            </select>
          </app-field>
          <app-field class="col-sm-6" label="Quem contesta">
            <select appInput formControlName="origin">
              @for (o of origins; track o.value) {
                <option [ngValue]="o.value">{{ o.label }}</option>
              }
            </select>
          </app-field>
          <app-field
            class="col-12"
            label="Descrição"
            [hintEnd]="form.controls.description.value.length + ' / ' + maxDescription"
            error="Descreva o que o devedor contesta."
          >
            <textarea appInput formControlName="description" rows="4" [maxlength]="maxDescription" required></textarea>
          </app-field>
          <app-field class="col-sm-6" label="Prazo de resposta" hint="Vazio = prazo padrão da assessoria.">
            <div class="input-group">
              <input appInput ngbDatepicker #due="ngbDatepicker" formControlName="responseDueDate" placeholder="dd/mm/aaaa" [minDate]="today" />
              <button type="button" class="btn btn-outline-secondary" (click)="due.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <div class="col-sm-6 d-flex align-items-center">
            <div class="form-check form-switch">
              <input class="form-check-input" type="checkbox" role="switch" id="dd-blocks" formControlName="blocksCollection" />
              <label class="form-check-label" for="dd-blocks">Suspender cobrança e contato</label>
            </div>
          </div>
        </div>
        @if (!form.controls.blocksCollection.value) {
          <div class="alert alert-warning d-flex gap-2 align-items-start mt-3 mb-0" role="note">
            <app-icon name="info-circle" />
            <span>Sem suspensão, os títulos continuam em réguas e negociações durante a análise.</span>
          </div>
        }
        <div class="dd-files mt-3">
          <input #file type="file" multiple [accept]="accept" (change)="pick(file.files); file.value = ''" hidden />
          <button type="button" class="btn btn-outline-primary" (click)="file.click()">
            <app-icon name="paperclip" />
            Anexar evidências
          </button>
          <span class="app-muted">PDF, imagem, áudio, texto ou e-mail, até 20 MB cada.</span>
          @for (f of files(); track f.name) {
            <span class="badge rounded-pill bg-secondary-subtle text-secondary-emphasis border border-secondary-subtle dd-file">
              <app-icon name="file-earmark-text" />{{ f.name }}
              <button type="button" class="btn-close" (click)="remove(f)" [attr.aria-label]="'Remover ' + f.name"></button>
            </span>
          }
        </div>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="saving()">
          @if (saving()) {
            <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
          }
          Abrir contestação
        </button>
      </div>
    </form>
  `,
  styles: `
    .dd-titles {
      margin: 0 0 12px;
      padding: 8px 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
    }
    .dd-files {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    .dd-file {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-weight: var(--bs-body-font-weight);
      font-size: var(--app-text-sm);
      --bs-btn-close-width: 0.5em;
    }
  `,
})
export class DisputeDialog {
  protected readonly data = inject<DisputeDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<DisputeView>>(DialogRef);
  private readonly api = inject(DisputesApi);
  private readonly notifications = inject(NotificationService);

  protected readonly types = Object.entries(DISPUTE_TYPE_LABELS).map(([value, label]) => ({ value: value as DisputeType, label }));
  protected readonly origins = Object.entries(DISPUTE_ORIGIN_LABELS).map(([value, label]) => ({ value: value as DisputeOrigin, label }));
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly maxDescription = DESCRIPTION_MAX;
  protected readonly today = toDateStruct(zonedNow().date);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected readonly files = signal<readonly File[]>([]);
  protected readonly selected = signal<ReadonlySet<string>>(new Set());

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    type: this.fb.control<DisputeType | ''>('', [AppValidators.notBlank]),
    origin: this.fb.control<DisputeOrigin>('DEBTOR'),
    description: ['', [AppValidators.notBlank, Validators.maxLength(DESCRIPTION_MAX)]],
    responseDueDate: this.fb.control<string | null>(null),
    blocksCollection: [true],
  });

  protected toggle(id: string, on: boolean): void {
    this.selected.update((s) => {
      const next = new Set(s);
      if (on) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  protected pick(list: FileList | null): void {
    const picked = Array.from(list ?? []);
    const problems = picked.map((f) => ({ f, problem: evidenceProblem(f) }));
    const rejected = problems.filter((p) => p.problem).map((p) => `${p.f.name}: ${p.problem}`);
    const accepted = problems.filter((p) => !p.problem).map((p) => p.f);
    const merged = [...this.files().filter((f) => !accepted.some((a) => a.name === f.name)), ...accepted];
    if (merged.length > MAX_FILES) {
      rejected.push(`No máximo ${MAX_FILES} evidências por contestação.`);
    }
    this.files.set(merged.slice(0, MAX_FILES));
    this.errors.set(rejected);
  }

  protected remove(file: File): void {
    this.files.update((list) => list.filter((f) => f !== file));
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.errors.set([]);
    this.api
      .open(this.data.caseId, {
        titleIds: [...this.selected()],
        type: v.type as DisputeType,
        origin: v.origin,
        description: v.description.trim(),
        blocksCollection: v.blocksCollection,
        responseDueDate: v.responseDueDate || null,
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (dispute) => this.upload(dispute),
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }

  /** Envia as evidências em sequência; falha num arquivo não desfaz a contestação já aberta. */
  private upload(dispute: DisputeView): void {
    const files = this.files();
    if (files.length === 0) {
      this.done(dispute, 0, 0);
      return;
    }
    this.saving.set(true);
    from(files)
      .pipe(
        // falha num arquivo (já notificada pelo interceptor) não interrompe os demais
        concatMap((file) => this.api.attachEvidence(dispute.id, file).pipe(map(() => true), catchError(() => of(false)))),
        toArray(),
        finalize(() => this.saving.set(false)),
      )
      .subscribe((results) => {
        const failed = results.filter((ok) => !ok).length;
        this.done(dispute, results.length - failed, failed);
      });
  }

  private done(dispute: DisputeView, sent: number, failed: number): void {
    const evidence = sent ? ` com ${sent} evidência(s)` : '';
    const warning = failed ? ` ${failed} arquivo(s) não foram enviados: anexe-os na fila de contestações.` : '';
    this.notifications.success(`Contestação aberta${evidence}. Prazo de resposta: ${formatDate(dispute.responseDueDate)}.${warning}`);
    this.ref.close(dispute);
  }
}
