import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { nameMap } from '../../../../shared/data/name-map';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { CreditorNames, PortfolioNames } from '../../../organization/data/organization.lookups';
import { TitlesApi } from '../../data/debt.api';
import { OWNERSHIP_RELEASE_LABELS } from '../../data/debt.labels';
import { OwnershipView } from '../../data/debt.models';

/** Períodos de titularidade (credor/carteira) do título, do mais recente ao mais antigo. */
@Component({
  selector: 'app-title-ownership',
  imports: [DataTable, CellDef, AppDatePipe],
  template: `
    <app-data-table
      caption="Titularidade do título"
      [rows]="rows()"
      [columns]="columns"
      [loading]="ownership.isLoading()"
      [failed]="ownership.status() === 'error'"
      (retry)="ownership.reload()"
      [rowKey]="rowKey"
      emptyIcon="clock-history"
      emptyMessage="Sem histórico de titularidade."
    >
      <ng-template appCell="creditor" [appCellOf]="rows()" let-o>{{ creditors.name(o.creditorId) }}</ng-template>
      <ng-template appCell="portfolio" [appCellOf]="rows()" let-o>{{ portfolios.name(o.portfolioId) }}</ng-template>
      <ng-template appCell="assignedAt" [appCellOf]="rows()" let-o>{{ o.assignedAt | appDate }}</ng-template>
      <ng-template appCell="releasedAt" [appCellOf]="rows()" let-o>
        @if (o.releasedAt) {
          {{ o.releasedAt | appDate }}
        } @else {
          <span class="app-status app-status--success">Período atual</span>
        }
      </ng-template>
      <ng-template appCell="releaseType" [appCellOf]="rows()" let-o>
        {{ o.releaseType ? releaseLabels[o.releaseType] : '—' }}
      </ng-template>
      <ng-template appCell="releaseReason" [appCellOf]="rows()" let-o>{{ o.releaseReason || '—' }}</ng-template>
    </app-data-table>
  `,
})
export class TitleOwnership {
  readonly titleId = input.required<string>();
  private readonly api = inject(TitlesApi);

  protected readonly releaseLabels = OWNERSHIP_RELEASE_LABELS;
  protected readonly ownership = rxResource({ params: () => this.titleId(), stream: ({ params }) => this.api.ownership(params) });
  /** O backend devolve do mais antigo ao atual; a tela mostra o atual primeiro. */
  private readonly periods = valueOr(this.ownership, []);
  protected readonly rows = computed(() => [...this.periods()].reverse());
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((o) => o.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.rows().map((o) => o.portfolioId));
  protected readonly rowKey = (o: OwnershipView) => `${o.portfolioId}-${o.assignedAt}`;

  protected readonly columns: TableColumn[] = [
    { key: 'creditor', header: 'Credor' },
    { key: 'portfolio', header: 'Carteira' },
    { key: 'assignedAt', header: 'Início' },
    { key: 'releasedAt', header: 'Fim' },
    { key: 'releaseType', header: 'Encerramento' },
    { key: 'releaseReason', header: 'Motivo' },
  ];
}
