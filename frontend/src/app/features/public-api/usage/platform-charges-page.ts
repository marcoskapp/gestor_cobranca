import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { PlatformPolicyVersions } from '../../platform/billing/components/platform-policy-versions';
import { PlatformPositionPanel } from '../../platform/billing/components/platform-position-panel';
import { PlatformStatementPanel } from '../../platform/billing/components/platform-statement-parts';
import { TenantPlatformBillingApi } from '../../platform/data/platform-billing.api';
import { PlatformStatementView } from '../../platform/data/platform-billing.models';
import { formatCycle } from '../data/public-api.labels';

const NUMBER = new Intl.NumberFormat('pt-BR');

/**
 * Cobrança da plataforma à assessoria pelo consumo da integração, somente leitura (transparência): a política que a
 * plataforma aplica, a posição do ciclo em andamento (consumo de todos os tokens, de todos os credores) e os extratos
 * fechados, com o mesmo cálculo da plataforma. Não é a cobrança da assessoria aos credores ("Extratos da API").
 */
@Component({
  selector: 'app-platform-charges-page',
  imports: [ReactiveFormsModule, ...FORM_FIELD, Amount, CellDef, DataTable, Icon, PageHeader, PlatformPolicyVersions,
    PlatformPositionPanel, PlatformStatementPanel],
  template: `
    <app-page-header title="Cobrança da plataforma" subtitle="O que a plataforma cobra da assessoria pelo uso da integração por todos os credores. Somente leitura." />

    <section class="app-card" aria-labelledby="pc-position" data-tour="charges-position">
      <div class="pc-head">
        <h2 class="app-card__title" id="pc-position">Ciclo em andamento</h2>
        <button class="btn btn-sm btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="position.reload()" [disabled]="position.isLoading()">
          <app-icon name="arrow-clockwise" /> Atualizar
        </button>
      </div>
      @if (position.status() === 'error') {
        <div class="alert alert-danger" role="alert">Não foi possível carregar a posição.</div>
      } @else if (position.value(); as p) {
        <app-platform-position-panel [position]="p" />
      } @else {
        <p class="app-muted" role="status">Carregando…</p>
      }
    </section>

    <section class="app-card" aria-labelledby="pc-policy" data-tour="charges-policy">
      <h2 class="app-card__title" id="pc-policy">Política da plataforma</h2>
      @if (policy.value(); as pol) {
        <app-platform-policy-versions [policy]="pol" />
      }
    </section>

    <section class="app-card" aria-labelledby="pc-statements" data-tour="charges-statements">
      <h2 class="app-card__title" id="pc-statements">Extratos fechados</h2>
      <form class="app-filters" [formGroup]="filters" role="search" aria-label="Filtrar extratos">
        <app-field label="Mês do fechamento"><input appInput type="month" formControlName="month" /></app-field>
      </form>
      <app-data-table
        caption="Extratos da plataforma"
        [rows]="query.rows()"
        [columns]="columns"
        [loading]="query.loading()"
        [failed]="query.failed()"
        [page]="query.tablePage()"
        (pageChange)="query.setPage($event)"
        (retry)="query.reload()"
        emptyIcon="receipt"
        emptyMessage="Nenhum extrato da plataforma."
        emptyHint="Os ciclos encerrados fecham automaticamente na madrugada seguinte ao dia de fechamento."
      >
        <ng-template appCell="period" [appCellOf]="query.rows()" let-s>{{ cycle(s) }}<div class="app-muted pc-small">revisão {{ s.revision }}</div></ng-template>
        <ng-template appCell="usage" [appCellOf]="query.rows()" let-s><span class="pc-small">{{ n(s.billableRequests) }} cham. · {{ n(s.billableMbIn) }}/{{ n(s.billableMbOut) }} MB · {{ n(s.records) }} reg.</span></ng-template>
        <ng-template appCell="total" [appCellOf]="query.rows()" let-s><app-amount [value]="s.totalAmount" /></ng-template>
        <ng-template appCell="actions" [appCellOf]="query.rows()" let-s>
          <button class="btn btn-sm btn-link" type="button" (click)="selected.set(s)">Detalhar</button>
          @if (canExport()) {
            <button class="btn btn-sm btn-link" type="button" (click)="exportStatement(s)" aria-label="Exportar CSV"><app-icon name="download" /></button>
          }
        </ng-template>
      </app-data-table>
      @if (selected(); as s) {
        <app-platform-statement-panel [statement]="s" />
      }
    </section>
  `,
  styles: `
    .pc-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .pc-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class PlatformChargesPage {
  private readonly api = inject(TenantPlatformBillingApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly canExport = computed(() => this.auth.hasPermission(Permission.REPORT_EXPORT));
  protected readonly selected = signal<PlatformStatementView | null>(null);
  protected readonly position = rxResource({ stream: () => this.api.position() });
  protected readonly policy = rxResource({ stream: () => this.api.policy() });

  protected readonly filters = this.fb.group({ month: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => ({ month: this.filters.getRawValue().month || null }))),
    { initialValue: { month: null as string | null }, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.statements(f.month, page));

  protected readonly columns: TableColumn[] = [
    { key: 'period', header: 'Ciclo' },
    { key: 'usage', header: 'Consumo cobrável', info: 'platform.billing.billable-requests' },
    { key: 'total', header: 'Total', align: 'end', info: 'platform.statement.total' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected n(value: number): string {
    return NUMBER.format(value);
  }

  protected cycle(s: PlatformStatementView): string {
    return formatCycle(s.periodStart, s.periodEnd);
  }

  protected exportStatement(s: PlatformStatementView): void {
    if (s.id) {
      this.api.exportStatement(s.id).subscribe(() => this.notifications.info('Extrato exportado (registrado no log de acesso).'));
    }
  }
}
