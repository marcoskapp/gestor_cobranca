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
import {
  RETENTION_ACTION_LABELS,
  RETENTION_CATEGORY_HINTS,
  RETENTION_CATEGORY_LABELS,
  retentionPeriod,
} from '../data/compliance.labels';
import { RetentionPolicyView, RetentionRunItemView, RetentionRunView } from '../data/compliance.models';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';

/**
 * Configuração da política de uma categoria: prazo na unidade da categoria (dias ou meses), dentro dos limites dela,
 * e habilitação — só nas categorias sem padrão do sistema (as com padrão descartam sempre).
 */
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
            O descarte é automático, todos os dias.
          } @else {
            Prazos maiores exigidos pela base legal de cada credor prevalecem.
          }
        </p>
        <app-field
          class="app-full-width mb-3"
          [label]="'Prazo (' + unitLabel + ')'"
          [hint]="hint"
          [hintEnd]="range"
          [error]="form.controls.period.invalid ? error() : undefined"
        >
          <input appInput type="number" formControlName="period" [min]="data.minAmount" [max]="data.maxAmount" />
        </app-field>
        @if (data.defaultAmount === null) {
          <div class="form-check form-switch">
            <input class="form-check-input" type="checkbox" role="switch" id="retention-dialogs-enabled" formControlName="enabled" />
            <label class="form-check-label" for="retention-dialogs-enabled">Política habilitada</label>
          </div>
        } @else {
          <p class="app-muted mb-0">
            Padrão do sistema: {{ formatPeriod(data.defaultAmount, data.unit) }}. O descarte desta categoria está sempre ativo: ajuste
            só o prazo.
            @if (data.configured) {
              <button class="btn btn-link p-0 align-baseline" type="button" (click)="useDefault()" [disabled]="saving()">
                Usar o padrão
              </button>
              <span class="d-block small">Apaga o prazo da assessoria: a categoria passa a seguir o padrão do sistema.</span>
            }
          </p>
        }
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
  protected readonly formatPeriod = retentionPeriod;
  protected readonly saving = signal(false);
  protected readonly unitLabel = this.data.unit === 'DAYS' ? 'dias' : 'meses';
  protected readonly hint = RETENTION_CATEGORY_HINTS[this.data.category];
  protected readonly range = `De ${this.data.minAmount} a ${this.data.maxAmount}`;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    // já preenchido: o prazo da assessoria ou, sem ele, o padrão do sistema
    period: this.fb.control<number | null>(this.data.retentionAmount ?? this.data.defaultAmount, [
      Validators.required,
      Validators.min(this.data.minAmount),
      Validators.max(this.data.maxAmount),
    ]),
    // categoria com padrão do sistema descarta sempre (o servidor recusa desabilitar)
    enabled: [this.data.defaultAmount !== null || this.data.enabled],
  });

  protected error(): string {
    return errorMessage(this.form.controls.period.errors);
  }

  /**
   * Volta ao padrão do sistema: apaga o prazo da assessoria no servidor (com trilha), em vez de gravar o número do
   * padrão como prazo próprio — assim a categoria acompanha o padrão se ele mudar.
   */
  protected useDefault(): void {
    if (this.saving()) {
      return;
    }
    this.saving.set(true);
    this.api
      .resetToDefault(this.data.category)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((saved) => this.ref.close(saved));
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .configure(this.data.category, v.period ?? 0, this.data.defaultAmount !== null || v.enabled)
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
