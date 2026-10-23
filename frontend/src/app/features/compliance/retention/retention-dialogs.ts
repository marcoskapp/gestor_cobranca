import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
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

/** Configuração da política de uma categoria (prazo em meses e habilitação). */
@Component({
  selector: 'app-retention-policy-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSlideToggleModule],
  template: `
    <h2 mat-dialog-title>{{ categories[data.category] }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p class="app-dialog-message">
          Ação ao fim do prazo: {{ actions[data.action] }}.
          @if (!data.executableHere) {
            Esta política é aplicada pelo módulo dono dos dados.
          } @else {
            Prazos maiores exigidos pela base legal de cada credor prevalecem.
          }
        </p>
        <mat-form-field class="app-full-width">
          <mat-label>Prazo de retenção (meses)</mat-label>
          <input matInput type="number" formControlName="retentionMonths" min="1" max="1200" />
          <mat-hint>Contado a partir da última movimentação.</mat-hint>
          @if (form.controls.retentionMonths.invalid) {
            <mat-error>{{ error() }}</mat-error>
          }
        </mat-form-field>
        <mat-slide-toggle formControlName="enabled">Política habilitada</mat-slide-toggle>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Salvar</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class RetentionPolicyForm {
  protected readonly data = inject<RetentionPolicyView>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<RetentionPolicyForm, RetentionPolicyView>>(MatDialogRef);
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
  imports: [MatDialogModule, MatButtonModule, RouterLink, DataTable, CellDef, StatusChip, AppDatePipe],
  template: `
    <h2 mat-dialog-title>{{ run.dryRun ? 'Relatório da simulação' : 'Evidência do descarte' }}</h2>
    <mat-dialog-content>
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
          <a [routerLink]="['/devedores', i.debtorId]" mat-dialog-close class="app-mono">{{ i.debtorId.slice(0, 8) }}</a>
        </ng-template>
        <ng-template appCell="outcome" [appCellOf]="items.rows()" let-i><app-status-chip domain="retentionItem" [status]="i.outcome" /></ng-template>
        <ng-template appCell="detail" [appCellOf]="items.rows()" let-i>{{ i.detail || i.errorCode || '—' }}</ng-template>
        <ng-template appCell="processedAt" [appCellOf]="items.rows()" let-i>{{ i.processedAt | appDate }}</ng-template>
      </app-data-table>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Fechar</button>
    </mat-dialog-actions>
  `,
})
export class RetentionRunItems {
  protected readonly run = inject<RetentionRunView>(MAT_DIALOG_DATA);
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
