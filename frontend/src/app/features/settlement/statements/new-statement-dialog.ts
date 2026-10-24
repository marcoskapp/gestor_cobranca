import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { catchError, finalize, of, switchMap } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { StatementsApi } from '../data/settlement.api';
import { SETTLEMENT_ERROR_LABELS } from '../data/settlement.labels';
import { PeriodSuggestion, StatementView } from '../data/settlement.models';

/**
 * Novo demonstrativo do credor: o período sugerido é o ciclo seguinte ao último demonstrativo (ou o
 * último ciclo encerrado) pelo dia de fechamento do contrato. Um em aberto por credor; sem lacunas.
 */
@Component({
  selector: 'app-new-statement-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatIconModule, MatInputModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Novo demonstrativo</h2>
    <form [formGroup]="form" (ngSubmit)="create()" novalidate>
      <mat-dialog-content>
        <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        @if (suggestion(); as s) {
          <p class="app-muted ns-small" role="status">Período sugerido pelo fechamento {{ s.settlementDay ? 'do dia ' + s.settlementDay : 'do contrato' }}. Ajuste só se o credor pediu outro ciclo (até 3 meses, sem lacunas).</p>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Período</mat-label>
          <mat-date-range-input [rangePicker]="picker">
            <input matStartDate formControlName="start" placeholder="Início" />
            <input matEndDate formControlName="end" placeholder="Fim" />
          </mat-date-range-input>
          <mat-datepicker-toggle matIconSuffix [for]="picker" />
          <mat-date-range-picker #picker />
          <mat-hint>Vazio = o sistema escolhe o próximo ciclo</mat-hint>
        </mat-form-field>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="!form.controls.creditor.value.length || sending()">Criar rascunho</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .ns-small {
      margin: 0 0 8px;
      font-size: var(--app-text-xs);
    }
  `,
})
export class NewStatementDialog {
  private readonly api = inject(StatementsApi);
  private readonly ref = inject<MatDialogRef<NewStatementDialog, StatementView>>(MatDialogRef);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly suggestion = signal<PeriodSuggestion | null>(null);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    creditor: this.fb.control<string[]>([]),
    start: this.fb.control<Date | null>(null),
    end: this.fb.control<Date | null>(null),
  });

  constructor() {
    this.form.controls.creditor.valueChanges
      .pipe(
        switchMap((ids) => (ids[0] ? this.api.suggestPeriod(ids[0]).pipe(catchError(() => of(null))) : of(null))),
        takeUntilDestroyed(),
      )
      .subscribe((s) => {
        this.suggestion.set(s);
        this.form.patchValue({ start: fromLocalDate(s?.periodStart), end: fromLocalDate(s?.periodEnd) });
      });
  }

  protected create(): void {
    const v = this.form.getRawValue();
    const creditorId = v.creditor[0];
    if (!creditorId || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .create({ creditorId, periodStart: toLocalDate(v.start), periodEnd: toLocalDate(v.end) })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (s) => this.ref.close(s),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && SETTLEMENT_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
