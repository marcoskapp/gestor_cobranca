import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { catchError, finalize, of, switchMap } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { isoDate } from '../../../shared/models/dates';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { StatementsApi } from '../data/settlement.api';
import { SETTLEMENT_ERROR_LABELS } from '../data/settlement.labels';
import { PeriodSuggestion, StatementView } from '../data/settlement.models';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';


/**
 * Novo demonstrativo do credor: o período sugerido é o ciclo seguinte ao último demonstrativo (ou o
 * último ciclo encerrado) pelo dia de fechamento do contrato. Um em aberto por credor; sem lacunas.
 */
@Component({
  selector: 'app-new-statement-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Novo demonstrativo</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form [formGroup]="form" (ngSubmit)="create()" novalidate>
      <div class="modal-body">
        <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        @if (suggestion(); as s) {
          <p class="app-muted ns-small" role="status">Período sugerido pelo fechamento {{ s.settlementDay ? 'do dia ' + s.settlementDay : 'do contrato' }}. Ajuste só se o credor pediu outro ciclo (até 3 meses, sem lacunas).</p>
        }
        <fieldset class="mt-3" aria-describedby="ns-period-hint">
          <legend class="form-label fs-5 mb-1">Período</legend>
          <div class="row g-2">
            <app-field class="col-sm-6" label="Início">
              <div class="input-group">
                <input appInput ngbDatepicker #start="ngbDatepicker" formControlName="start" placeholder="dd/mm/aaaa" autocomplete="off" />
                <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário do início" (click)="start.toggle()">
                  <app-icon name="calendar-event" />
                </button>
              </div>
            </app-field>
            <app-field class="col-sm-6" label="Fim">
              <div class="input-group">
                <input appInput ngbDatepicker #end="ngbDatepicker" formControlName="end" placeholder="dd/mm/aaaa" autocomplete="off" />
                <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário do fim" (click)="end.toggle()">
                  <app-icon name="calendar-event" />
                </button>
              </div>
            </app-field>
          </div>
          <div class="form-text" id="ns-period-hint">Vazio = o sistema escolhe o próximo ciclo</div>
        </fieldset>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="!form.controls.creditor.value.length || sending()">Criar rascunho</button>
      </div>
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
  private readonly ref = inject<DialogRef<StatementView>>(DialogRef);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly suggestion = signal<PeriodSuggestion | null>(null);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    creditor: this.fb.control<string[]>([]),
    // LocalDate (`yyyy-MM-dd`) do ngbDatepicker
    start: this.fb.control<string | null>(null),
    end: this.fb.control<string | null>(null),
  });

  constructor() {
    this.form.controls.creditor.valueChanges
      .pipe(
        switchMap((ids) => (ids[0] ? this.api.suggestPeriod(ids[0]).pipe(catchError(() => of(null))) : of(null))),
        takeUntilDestroyed(),
      )
      .subscribe((s) => {
        this.suggestion.set(s);
        this.form.patchValue({ start: s?.periodStart ?? null, end: s?.periodEnd ?? null });
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
      .create({ creditorId, periodStart: isoDate(v.start), periodEnd: isoDate(v.end) })
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
