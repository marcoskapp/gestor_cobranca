import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { catchError, concatMap, finalize, from, map, of, toArray } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
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
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    Amount,
    AppDatePipe,
  ],
  template: `
    <h2 mat-dialog-title>Abrir contestação</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of errors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <p class="app-dialog-message">Devedor: <strong>{{ data.debtorName }}</strong></p>
        @if (data.titles.length > 1) {
          <fieldset class="dd-titles">
            <legend>Títulos contestados <span class="app-muted">(nenhum marcado = todos em cobrança)</span></legend>
            @for (t of data.titles; track t.titleId) {
              <mat-checkbox [checked]="selected().has(t.titleId)" (change)="toggle(t.titleId, $event.checked)">
                <span class="app-numeric">{{ t.externalId }}{{ t.installmentNumber ? '/' + t.installmentNumber : '' }}</span>
                · venc. {{ t.dueDate | appDate: 'date' }} · <app-amount [value]="t.openBalance" />
              </mat-checkbox>
            }
          </fieldset>
        }
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Motivo</mat-label>
            <mat-select formControlName="type" required>
              @for (t of types; track t.value) {
                <mat-option [value]="t.value">{{ t.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Quem contesta</mat-label>
            <mat-select formControlName="origin">
              @for (o of origins; track o.value) {
                <mat-option [value]="o.value">{{ o.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Descrição</mat-label>
            <textarea matInput formControlName="description" rows="4" [maxlength]="maxDescription" required></textarea>
            <mat-hint align="end">{{ form.controls.description.value.length }} / {{ maxDescription }}</mat-hint>
            @if (form.controls.description.invalid) {
              <mat-error>Descreva o que o devedor contesta.</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Prazo de resposta</mat-label>
            <input matInput [matDatepicker]="due" formControlName="responseDueDate" [min]="today" />
            <mat-datepicker-toggle matIconSuffix [for]="due" />
            <mat-datepicker #due />
            <mat-hint>Vazio = prazo padrão da assessoria.</mat-hint>
          </mat-form-field>
          <mat-slide-toggle formControlName="blocksCollection" class="dd-toggle">Suspender cobrança e contato</mat-slide-toggle>
        </div>
        @if (!form.controls.blocksCollection.value) {
          <p class="app-alert app-alert--warning" role="note">
            <mat-icon aria-hidden="true">info</mat-icon>
            <span>Sem suspensão, os títulos continuam em réguas e negociações durante a análise.</span>
          </p>
        }
        <div class="dd-files">
          <input #file type="file" multiple [accept]="accept" (change)="pick(file.files); file.value = ''" hidden />
          <button mat-stroked-button type="button" (click)="file.click()">
            <mat-icon aria-hidden="true">attach_file</mat-icon>
            Anexar evidências
          </button>
          <span class="app-muted">PDF, imagem, áudio, texto ou e-mail, até 20 MB cada.</span>
          @for (f of files(); track f.name) {
            <span class="dd-file">
              <mat-icon aria-hidden="true">description</mat-icon>{{ f.name }}
              <button mat-icon-button type="button" (click)="remove(f)" [attr.aria-label]="'Remover ' + f.name">
                <mat-icon aria-hidden="true">close</mat-icon>
              </button>
            </span>
          }
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Abrir contestação</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .dd-titles {
      display: flex;
      flex-direction: column;
      margin: 0 0 12px;
      padding: 8px 12px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
    }
    .dd-toggle {
      align-self: center;
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
      padding-left: 8px;
      border-radius: var(--app-radius-pill);
      background: var(--app-surface-raised);
      font-size: var(--app-text-sm);
    }
  `,
})
export class DisputeDialog {
  protected readonly data = inject<DisputeDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<DisputeDialog, DisputeView>>(MatDialogRef);
  private readonly api = inject(DisputesApi);
  private readonly notifications = inject(NotificationService);

  protected readonly types = Object.entries(DISPUTE_TYPE_LABELS).map(([value, label]) => ({ value: value as DisputeType, label }));
  protected readonly origins = Object.entries(DISPUTE_ORIGIN_LABELS).map(([value, label]) => ({ value: value as DisputeOrigin, label }));
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly maxDescription = DESCRIPTION_MAX;
  protected readonly today = fromLocalDate(zonedNow().date);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected readonly files = signal<readonly File[]>([]);
  protected readonly selected = signal<ReadonlySet<string>>(new Set());

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    type: this.fb.control<DisputeType | ''>('', [AppValidators.notBlank]),
    origin: this.fb.control<DisputeOrigin>('DEBTOR'),
    description: ['', [AppValidators.notBlank, Validators.maxLength(DESCRIPTION_MAX)]],
    responseDueDate: this.fb.control<Date | null>(null),
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
        responseDueDate: toLocalDate(v.responseDueDate),
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
