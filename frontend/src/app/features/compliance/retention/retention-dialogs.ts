import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../shared/data/paged-query';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { errorMessage } from '../../../shared/validation/error-messages';
import { RetentionApi } from '../data/compliance.api';
import { RETENTION_ACTION_LABELS, RETENTION_CATEGORY_LABELS } from '../data/compliance.labels';
import { RetentionPolicyView, RetentionRunItemView, RetentionRunView } from '../data/compliance.models';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';

/** Configuração da política de uma categoria (prazo em meses e habilitação). */
@Component({
  selector: 'app-retention-policy-form',
  imports: [ReactiveFormsModule, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ categories[data.category] }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          Ação ao fim do prazo: {{ actions[data.action] }}.
          @if (!data.executableHere) {
            Esta política é aplicada pelo módulo dono dos dados.
          } @else {
            Prazos maiores exigidos pela base legal de cada credor prevalecem.
          }
        </p>
        <app-field class="app-full-width mb-3" label="Prazo de retenção (meses)" hint="Contado a partir da última movimentação." [error]="form.controls.retentionMonths.invalid ? (error()) : undefined">
          <input appInput type="number" formControlName="retentionMonths" min="1" max="1200" />
        </app-field>
        <div class="form-check form-switch">
          <input class="form-check-input" type="checkbox" role="switch" id="retention-dialogs-enabled" formControlName="enabled" />
          <label class="form-check-label" for="retention-dialogs-enabled">Política habilitada</label>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar</button>
      </div>
    </form>
  `,
})
export class RetentionPolicyForm {
  protected readonly data = inject<RetentionPolicyView>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<RetentionPolicyView>>(DialogRef);
  private readonly api = inject(RetentionApi);
  protected readonly categories = RETENTION_CATEGORY_LABELS;
  protected readonly actions = RETENTION_ACTION_LABELS;
  protected readonly saving = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    retentionMonths: this.fb.control<number | null>(this.data.retentionMonths, [Validators.required, Validators.min(1), Validators.max(1200)]),
    enabled: [this.data.enabled],
  });

  protected error(): string {
    return errorMessage(this.form.controls.retentionMonths.errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .configure(this.data.category, v.retentionMonths ?? 0, v.enabled)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((saved) => this.ref.close(saved));
  }
}

/** Itens de uma execução: o relatório da simulação ou a evidência do descarte. */
@Component({
  selector: 'app-retention-run-items',
  imports: [RouterLink, DataTable, CellDef, StatusChip, AppDatePipe, ...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ run.dryRun ? 'Relatório da simulação' : 'Evidência do descarte' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <p class="app-dialog-message">
        {{ categories[run.category] }} · corte em {{ run.cutoff | appDate: 'date' }} ({{ run.retentionMonths }} meses) ·
        {{ run.candidateCount }} candidatos, {{ run.eligibleCount }} elegíveis, {{ run.processedCount }} anonimizados,
        {{ run.retainedCount }} mantidos, {{ run.failedCount }} falhas.
      </p>
      <app-data-table
        caption="Itens da execução"
        [rows]="items.rows()"
        [columns]="columns"
        [loading]="items.loading()"
        [failed]="items.failed()"
        [page]="items.tablePage()"
        (pageChange)="items.setPage($event)"
        (retry)="items.reload()"
        [rowKey]="itemKey"
        emptyMessage="Nenhum devedor nesta execução."
      >
        <ng-template appCell="debtor" [appCellOf]="items.rows()" let-i>
          <a [routerLink]="['/devedores', i.debtorId]" appDialogClose class="app-mono">{{ i.debtorId.slice(0, 8) }}</a>
        </ng-template>
        <ng-template appCell="outcome" [appCellOf]="items.rows()" let-i><app-status-chip domain="retentionItem" [status]="i.outcome" /></ng-template>
        <ng-template appCell="detail" [appCellOf]="items.rows()" let-i>{{ i.detail || i.errorCode || '—' }}</ng-template>
        <ng-template appCell="processedAt" [appCellOf]="items.rows()" let-i>{{ i.processedAt | appDate }}</ng-template>
      </app-data-table>
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Fechar</button>
    </div>
  `,
})
export class RetentionRunItems {
  protected readonly run = inject<RetentionRunView>(DIALOG_DATA);
  private readonly api = inject(RetentionApi);
  protected readonly categories = RETENTION_CATEGORY_LABELS;
  private readonly runId = signal(this.run.id);
  protected readonly items = pagedQuery(this.runId, (id, page) => this.api.runItems(id, page));
  protected readonly itemKey = (i: RetentionRunItemView) => i.debtorId;

  protected readonly columns: TableColumn[] = [
    { key: 'debtor', header: 'Devedor' },
    { key: 'outcome', header: 'Resultado' },
    { key: 'detail', header: 'Detalhe' },
    { key: 'processedAt', header: 'Processado em' },
  ];
}
