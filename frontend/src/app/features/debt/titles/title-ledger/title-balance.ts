import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Amount } from '../../../../shared/components/amount/amount';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { TitlesApi } from '../../data/debt.api';
import { LEDGER_COMPONENT_LABELS } from '../../data/debt.labels';
import { ComponentBalanceView } from '../../data/debt.models';
import { Icon } from '../../../../shared/components/icon/icon';

/**
 * Saldo por componente calculado do razão, com a divergência em relação ao último saldo
 * informado pelo credor nas cargas (sinal explícito: positivo = razão acima do informado).
 */
@Component({
  selector: 'app-title-balance',
  imports: [DataTable, CellDef, Amount, AppDatePipe, Icon],
  template: `
    @if (current(); as b) {
      @if (b.divergence !== null && b.divergence !== 0) {
        <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
          <app-icon name="arrow-left-right" />
          <span>
            O saldo do razão difere do último saldo informado pelo credor em
            <app-amount [value]="b.divergence" [signed]="true" />. Confira os lançamentos antes de negociar.
          </span>
        </div>
      }
    }
    <app-data-table
      caption="Saldo por componente"
      [rows]="components()"
      [columns]="columns"
      [loading]="balance.isLoading()"
      [failed]="balance.status() === 'error'"
      (retry)="balance.reload()"
      [rowKey]="componentKey"
      emptyIcon="wallet2"
      emptyMessage="Sem lançamentos para compor o saldo."
    >
      <ng-template appCell="component" [appCellOf]="components()" let-c>{{ labels[c.component] ?? c.component }}</ng-template>
      <ng-template appCell="charged" [appCellOf]="components()" let-c><app-amount [value]="c.charged" /></ng-template>
      <ng-template appCell="discounted" [appCellOf]="components()" let-c><app-amount [value]="c.discounted" /></ng-template>
      <ng-template appCell="paid" [appCellOf]="components()" let-c><app-amount [value]="c.paid" /></ng-template>
      <ng-template appCell="reversed" [appCellOf]="components()" let-c><app-amount [value]="c.reversed" /></ng-template>
      <ng-template appCell="balance" [appCellOf]="components()" let-c><strong><app-amount [value]="c.balance" /></strong></ng-template>
    </app-data-table>

    @if (current(); as b) {
      @if (b.snapshots.length) {
        <h3 class="app-section-title">Saldos informados pelo credor</h3>
        <dl class="app-definition-list">
          @for (s of b.snapshots; track s.recordedAt) {
            <dt>{{ s.referenceDate | appDate: 'date' }}</dt>
            <dd><app-amount [value]="s.reportedBalance" /></dd>
          }
        </dl>
      }
    }
  `,
})
export class TitleBalance {
  readonly titleId = input.required<string>();
  private readonly api = inject(TitlesApi);

  protected readonly labels: Record<string, string> = LEDGER_COMPONENT_LABELS;
  protected readonly balance = rxResource({ params: () => this.titleId(), stream: ({ params }) => this.api.balance(params) });
  protected readonly current = valueOr(this.balance, undefined);
  protected readonly components = computed(() => this.current()?.components ?? []);
  protected readonly componentKey = (c: ComponentBalanceView) => c.component;

  protected readonly columns: TableColumn[] = [
    { key: 'component', header: 'Componente' },
    { key: 'charged', header: 'Lançado', align: 'end' },
    { key: 'discounted', header: 'Descontos e baixas', align: 'end' },
    { key: 'paid', header: 'Pago', align: 'end' },
    { key: 'reversed', header: 'Estornado', align: 'end' },
    { key: 'balance', header: 'Saldo', align: 'end' },
  ];
}
