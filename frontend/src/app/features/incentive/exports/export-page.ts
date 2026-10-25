import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { PayrollExportsApi } from '../data/incentive.api';
import { ExportFormat, ExportView } from '../data/incentive.models';
import { addMonths, monthLabel, monthOf, monthOptions } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';

/**
 * Exportação para a folha: um arquivo (CSV ou XLSX) com todos os extratos aprovados do período,
 * guardado no servidor com SHA-256; os aprovados passam a exportados. Reexportar inclui os já
 * exportados. Downloads posteriores são conferidos com o SHA-256 registrado.
 */
@Component({
  selector: 'app-incentive-export-page',
  imports: [ReactiveFormsModule, ...FORM_FIELD, Amount, AppDatePipe, CellDef, DataTable, Icon, PageHeader],
  template: `
    <app-page-header title="Exportação para a folha" subtitle="Arquivo com os extratos aprovados do período, para lançamento na folha de pagamento." />
    <form class="app-filters" data-tour="filters" [formGroup]="form" aria-label="Período e formato da exportação">
      <app-field label="Período">
        <select appInput formControlName="period">
          @for (m of months; track m.value) {
            <option [ngValue]="m.value">{{ m.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Formato">
        <select appInput formControlName="format">
          <option ngValue="CSV">CSV (separador ;)</option>
          <option ngValue="XLSX">Planilha XLSX</option>
        </select>
      </app-field>
      <div class="app-filters__actions">
        <button type="button" class="btn btn-primary" (click)="generate()" [disabled]="busy()" data-tour="export-generate">
          <app-icon name="download" />
          Gerar arquivo
        </button>
      </div>
    </form>
    @if (error()) {
      <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
    }
    <app-data-table
      [caption]="'Arquivos gerados para ' + month(period())"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="download"
      emptyMessage="Nenhum arquivo gerado para este período."
      emptyHint="Aprove os extratos do período e gere o arquivo da folha."
    >
      <ng-template appCell="file" [appCellOf]="rows()" let-x>
        <strong>{{ x.fileName }}</strong>
        <div class="app-muted in-small font-monospace" [title]="x.sha256">SHA-256 {{ x.sha256.slice(0, 16) }}…</div>
      </ng-template>
      <ng-template appCell="statements" [appCellOf]="rows()" let-x>{{ x.statements }}</ng-template>
      <ng-template appCell="total" [appCellOf]="rows()" let-x><app-amount [value]="x.totalAmount" /></ng-template>
      <ng-template appCell="when" [appCellOf]="rows()" let-x>
        {{ x.exportedAt | appDate }}
        <div class="app-muted in-small">{{ users.name(x.exportedBy) }}</div>
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-x>
        <button type="button" class="btn btn-sm btn-outline-primary" (click)="download(x)" [disabled]="downloading() === x.id" [attr.aria-label]="'Baixar ' + x.fileName">
          <app-icon name="download" />
          Baixar
        </button>
        @if (verified().get(x.id) === false) {
          <div class="in-small text-danger-emphasis" role="alert">SHA-256 não confere</div>
        } @else if (verified().get(x.id)) {
          <div class="in-small text-success-emphasis">SHA-256 conferido</div>
        }
      </ng-template>
    </app-data-table>
  `,
  styleUrl: '../incentive.scss',
})
export class ExportPage {
  private readonly api = inject(PayrollExportsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly today = zonedNow().date;
  protected readonly months = monthOptions(this.today, 24);
  protected readonly month = monthLabel;
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    period: [addMonths(monthOf(this.today), -1)],
    format: this.fb.control<ExportFormat>('CSV'),
  });
  private readonly value = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly period = computed(() => {
    this.value();
    return this.form.getRawValue().period;
  });
  protected readonly list = rxResource({ params: () => this.period(), stream: ({ params }) => this.api.list(params) });
  private readonly raw = valueOr(this.list, []);
  protected readonly rows = computed(() => [...this.raw()].sort((a, b) => b.exportedAt.localeCompare(a.exportedAt)));
  protected readonly users = nameMap(inject(UserNames), () => this.rows().map((x) => x.exportedBy), { batch: USER_NAMES_BATCH });
  protected readonly busy = signal(false);
  protected readonly downloading = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly verified = signal<ReadonlyMap<string, boolean>>(new Map());

  protected readonly columns: TableColumn[] = [
    { key: 'file', header: 'Arquivo' },
    { key: 'statements', header: 'Extratos', align: 'end' },
    { key: 'total', header: 'Total', align: 'end' },
    { key: 'when', header: 'Gerado em' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected generate(): void {
    const { period, format } = this.form.getRawValue();
    this.confirm
      .confirm({
        title: `Exportar ${monthLabel(period)} para a folha`,
        message: 'O arquivo inclui todos os extratos aprovados (e os já exportados) do período. Os aprovados passam a "exportado para a folha". O arquivo fica guardado com o SHA-256 como evidência.',
        confirmLabel: 'Gerar e baixar',
        details: [
          { label: 'Período', value: monthLabel(period) },
          { label: 'Formato', value: format },
        ],
      })
      .subscribe(() => {
        this.busy.set(true);
        this.error.set(null);
        this.api
          .export(period, format)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: (name) => {
              this.notifications.success(`Arquivo ${name} gerado e baixado.`);
              this.list.reload();
            },
            error: (err: unknown) => this.error.set(incentiveError(err)),
          });
      });
  }

  protected download(x: ExportView): void {
    this.downloading.set(x.id);
    this.api
      .download(x)
      .pipe(finalize(() => this.downloading.set(null)))
      .subscribe({
        next: (r) => {
          this.verified.update((m) => new Map([...m, [x.id, r.verified]]));
          if (r.verified) {
            this.notifications.success(`${r.fileName} baixado. SHA-256 conferido.`);
          } else {
            this.notifications.error(`${r.fileName} baixado, mas o SHA-256 não confere com o registrado. Não envie este arquivo à folha; baixe de novo.`);
          }
        },
        error: (err: unknown) => this.error.set(incentiveError(err)),
      });
  }
}
