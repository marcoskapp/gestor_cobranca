import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, finalize, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { PublicApiUsageApi } from '../data/public-api.api';
import { PUBLIC_API_ERROR_LABELS, formatCycle } from '../data/public-api.labels';
import { StatementView } from '../data/public-api.models';
import { StatementViewPanel } from './statement-view';

/**
 * Faturamento da API pública (modelo pronto para faturar, sem cobrança): extrato por ciclo de consumo de cada credor,
 * com prévia, fechamento (os ciclos encerrados fecham sozinhos na madrugada seguinte), revisão com motivo e exportação.
 * Os preços e o dia de fechamento ficam no contrato do credor (ficha do credor → contratos → integrações).
 */
@Component({
  selector: 'app-public-api-billing',
  imports: [ReactiveFormsModule, ...FORM_FIELD, Amount, CellDef, DataTable, EntityPicker, Icon, PageHeader, StatementViewPanel],
  template: `
    <app-page-header title="Extratos da API pública" subtitle="Extrato por ciclo de consumo de cada credor, com os preços do contrato. Reprodutível: o mesmo consumo e as mesmas condições geram sempre o mesmo valor." />

    <section class="app-card" aria-labelledby="bp-preview" data-tour="billing-preview">
      <h2 class="app-card__title" id="bp-preview">Prévia e fechamento do ciclo</h2>
      <p class="app-muted bp-small">
        Preços e dia de fechamento ficam no contrato do credor (ficha do credor, contratos, botão de integrações) e valem a
        partir do próximo ciclo. O ciclo fecha no fim do dia de fechamento e o próximo começa às 00:00 do dia seguinte;
        os ciclos encerrados são fechados automaticamente na madrugada seguinte.
      </p>
      <form class="app-filters" [formGroup]="previewForm" aria-label="Prévia do extrato">
        <div class="app-filters__wide">
          <app-entity-picker [control]="previewForm.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <div class="app-filters__actions">
          <button class="btn btn-outline-primary" type="button" (click)="loadPreview()" [disabled]="!previewForm.controls.creditor.value.length">Calcular prévia</button>
          @if (canManage() && preview()?.ended) {
            <button class="btn btn-primary" type="button" (click)="close()" [disabled]="busy()">Fechar ciclo</button>
          }
        </div>
      </form>
      @if (preview(); as s) {
        <app-statement-view [statement]="s" [creditorName]="creditors.name(s.creditorId)" />
      }
    </section>

    <section class="app-card" aria-labelledby="bp-statements">
      <h2 class="app-card__title" id="bp-statements">Extratos fechados</h2>
      <form class="app-filters" [formGroup]="filters" role="search" aria-label="Filtrar extratos">
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <app-field label="Mês do fechamento"><input appInput type="month" formControlName="month" /></app-field>
      </form>
      <app-data-table
        caption="Extratos fechados"
        [rows]="query.rows()"
        [columns]="columns"
        [loading]="query.loading()"
        [failed]="query.failed()"
        [page]="query.tablePage()"
        (pageChange)="query.setPage($event)"
        (retry)="query.reload()"
        emptyIcon="receipt"
        emptyMessage="Nenhum extrato fechado."
        emptyHint="Cada ciclo encerrado é fechado automaticamente na madrugada seguinte ao dia de fechamento do contrato."
      >
        <ng-template appCell="period" [appCellOf]="query.rows()" let-s>{{ cycle(s) }}<div class="app-muted bp-small">revisão {{ s.revision }}</div></ng-template>
        <ng-template appCell="creditor" [appCellOf]="query.rows()" let-s>{{ creditors.name(s.creditorId) }}</ng-template>
        <ng-template appCell="usage" [appCellOf]="query.rows()" let-s><span class="bp-small">{{ n(s.billableRequests) }} cham. · {{ n(s.billableMbIn) }} MB rec. · {{ n(s.billableMbOut) }} MB env. · {{ n(s.records) }} reg.</span></ng-template>
        <ng-template appCell="plan" [appCellOf]="query.rows()" let-s>{{ s.pricingVersion ? s.contractNumber + ' · v' + s.pricingVersion : 'Sem condições' }}</ng-template>
        <ng-template appCell="total" [appCellOf]="query.rows()" let-s><app-amount [value]="s.totalAmount" /></ng-template>
        <ng-template appCell="actions" [appCellOf]="query.rows()" let-s>
          <button class="btn btn-sm btn-link" type="button" (click)="selected.set(s)">Detalhar</button>
          @if (canManage()) {
            <button class="btn btn-sm btn-link" type="button" (click)="revise(s)">Nova revisão</button>
          }
          @if (canExport()) {
            <button class="btn btn-sm btn-link" type="button" (click)="exportStatement(s)" aria-label="Exportar CSV"><app-icon name="download" /></button>
          }
        </ng-template>
      </app-data-table>
      @if (selected(); as s) {
        <app-statement-view [statement]="s" [creditorName]="creditors.name(s.creditorId)" />
      }
    </section>

  `,
  styles: `
    .bp-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class BillingPage {
  private readonly api = inject(PublicApiUsageApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.PUBLIC_API_BILLING_MANAGE));
  protected readonly canExport = computed(() => this.auth.hasPermission(Permission.REPORT_EXPORT));
  protected readonly busy = signal(false);
  protected readonly preview = signal<StatementView | null>(null);
  protected readonly selected = signal<StatementView | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly previewForm = this.fb.group({ creditor: this.fb.control<string[]>([]) });
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), month: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => this.current())),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.statements(f.creditorId, f.month, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => [
    ...this.query.rows().map((s) => s.creditorId),
    ...(this.preview() ? [this.preview()!.creditorId] : []),
  ]);

  protected readonly columns: TableColumn[] = [
    { key: 'period', header: 'Ciclo' },
    { key: 'creditor', header: 'Credor' },
    { key: 'usage', header: 'Consumo cobrável' },
    { key: 'plan', header: 'Condições do contrato' },
    { key: 'total', header: 'Total', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected n(value: number): string {
    return new Intl.NumberFormat('pt-BR').format(value);
  }

  protected cycle(s: StatementView): string {
    return formatCycle(s.periodStart, s.periodEnd);
  }

  protected loadPreview(): void {
    const v = this.previewForm.getRawValue();
    if (!v.creditor.length) {
      return;
    }
    this.api.preview(v.creditor[0]).subscribe((s) => this.preview.set(s));
  }

  protected close(): void {
    const s = this.preview();
    if (!s) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Fechar o ciclo',
        message: 'O extrato é gravado e não muda mais. Novos fechamentos do mesmo ciclo geram revisões com motivo.',
        confirmLabel: 'Fechar ciclo',
        reason: 'optional',
        reasonLabel: 'Observação',
        reasonMaxLength: 500,
        details: [{ label: 'Ciclo', value: formatCycle(s.periodStart, s.periodEnd) }],
      })
      .subscribe((reason) => this.submit(s.creditorId, null, reason || null));
  }

  protected revise(s: StatementView): void {
    this.confirm
      .confirm({
        title: 'Gerar nova revisão do extrato',
        message: 'Uma nova revisão do ciclo é gravada com o consumo atual e as mesmas condições; a anterior permanece.',
        confirmLabel: 'Gerar revisão',
        reason: 'required',
        reasonLabel: 'Motivo',
        reasonMaxLength: 500,
        details: [{ label: 'Ciclo', value: formatCycle(s.periodStart, s.periodEnd) }],
      })
      .subscribe((reason) => this.submit(s.creditorId, s.periodStart, reason || null));
  }

  protected exportStatement(s: StatementView): void {
    if (s.id) {
      this.api.exportStatement(s.id).subscribe(() => this.notifications.info('Extrato exportado (registrado no log de acesso).'));
    }
  }

  private submit(creditorId: string, periodStart: string | null, reason: string | null): void {
    this.busy.set(true);
    this.api
      .close(creditorId, periodStart, reason)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (s) => {
          this.notifications.success(`Extrato fechado (revisão ${s.revision}).`);
          this.preview.set(null);
          this.selected.set(s);
          this.query.reload();
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.notifications.error((e.code && PUBLIC_API_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }

  private current(): { creditorId: string | null; month: string | null } {
    const v = this.filters.getRawValue();
    return { creditorId: v.creditor[0] ?? null, month: v.month || null };
  }
}
