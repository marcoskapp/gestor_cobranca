import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Amount } from '../../shared/components/amount/amount';
import { CellDef } from '../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../shared/components/data-table/data-table';
import { Icon } from '../../shared/components/icon/icon';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { CreditorStatementsApi } from '../data/creditor-statements.api';
import { TransferDirection } from '../data/creditor.models';

/** Texto de "quem paga quem" para o credor (o valor vem sempre positivo na tela). */
export function directionText(direction: TransferDirection, estimated = false): string {
  switch (direction) {
    case 'AGENCY_TO_CREDITOR':
      return estimated ? 'A assessoria deve repassar a você' : 'A assessoria repassa a você';
    case 'CREDITOR_TO_AGENCY':
      return estimated ? 'Você deve pagar à assessoria' : 'Você paga à assessoria';
    default:
      return 'Nada a repassar';
  }
}

/**
 * Prestação de contas no portal do credor: período em aberto (estimativa) e os demonstrativos já enviados, do mais
 * recente ao mais antigo. Cada linha leva à ficha, onde a pessoa confere os arquivos e responde.
 */
@Component({
  selector: 'app-creditor-statements',
  imports: [RouterLink, Amount, AppDatePipe, CellDef, DataTable, Icon, Skeleton, StatusChip],
  template: `
    <h1 class="h4">Prestação de contas</h1>
    <p class="app-muted">O que a assessoria recebeu, o que ficou com ela e o valor a repassar, por período.</p>

    <section class="app-card" aria-labelledby="cs-aberto" data-tour="creditor-open-period">
      <h2 class="app-card__title" id="cs-aberto">Período em aberto</h2>
      @if (open.isLoading()) {
        <app-skeleton variant="lines" [rows]="3" />
      } @else if (open.error()) {
        <div class="d-flex flex-wrap align-items-center gap-2" role="alert">
          <span>Não foi possível calcular a posição agora.</span>
          <button class="btn btn-outline-secondary btn-sm" type="button" (click)="open.reload()">Tentar novamente</button>
        </div>
      } @else if (open.value(); as p) {
        <dl class="app-definition-list">
          <dt>Período</dt>
          <dd>desde {{ p.periodStart | appDate: 'date' }}{{ p.expectedPeriodEnd ? ' · fecha em ' : '' }}{{ p.expectedPeriodEnd ? (p.expectedPeriodEnd | appDate: 'date') : '' }}</dd>
          <dt>Recuperado até agora</dt>
          <dd><app-amount [value]="p.recovered" /></dd>
          <dt>{{ direction(p.direction, true) }}</dt>
          <dd><app-amount [value]="abs(p.netTransfer)" /></dd>
        </dl>
        <p class="app-muted mb-0">Estimativa calculada em {{ p.calculatedAt | appDate }}. O valor final sai no demonstrativo do fechamento.</p>
      }
    </section>

    <section class="app-card" aria-labelledby="cs-lista">
      <h2 class="app-card__title" id="cs-lista">Demonstrativos enviados</h2>
      <app-data-table
        caption="Demonstrativos enviados pela assessoria"
        [rows]="rows()"
        [columns]="columns"
        [loading]="list.isLoading()"
        [failed]="!!list.error()"
        (retry)="list.reload()"
        emptyIcon="file-earmark-text"
        emptyMessage="Nenhum demonstrativo enviado ainda."
        emptyHint="Quando a assessoria fechar um período, o demonstrativo aparece aqui para você conferir e responder."
      >
        <ng-template appCell="period" [appCellOf]="rows()" let-s>
          <a [routerLink]="[s.id]">nº {{ s.sequenceNumber }} · {{ s.periodStart | appDate: 'date' }} a {{ s.periodEnd | appDate: 'date' }}</a>
        </ng-template>
        <ng-template appCell="status" [appCellOf]="rows()" let-s>
          <app-status-chip domain="creditorStatement" [status]="s.status" />
        </ng-template>
        <ng-template appCell="recovered" [appCellOf]="rows()" let-s><app-amount [value]="s.recovered" /></ng-template>
        <ng-template appCell="net" [appCellOf]="rows()" let-s>
          <app-amount [value]="abs(s.netTransfer)" />
          <div class="app-muted cs-small">{{ direction(s.direction) }}</div>
        </ng-template>
        <ng-template appCell="sentAt" [appCellOf]="rows()" let-s>{{ s.sentAt | appDate: 'date' }}</ng-template>
      </app-data-table>
    </section>

    <p class="app-muted">
      <app-icon name="info-circle" />
      Os valores de um demonstrativo enviado não mudam: correções entram como ajuste no período seguinte.
    </p>
  `,
  styles: `
    .cs-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class CreditorStatements {
  private readonly api = inject(CreditorStatementsApi);
  protected readonly list = rxResource({ stream: () => this.api.list() });
  protected readonly open = rxResource({ stream: () => this.api.openPeriod() });
  protected readonly rows = computed(() => this.list.value() ?? []);
  protected readonly direction = directionText;
  protected readonly abs = (value: number) => Math.abs(Number(value));

  protected readonly columns: TableColumn[] = [
    { key: 'period', header: 'Demonstrativo', mobile: 'title' },
    { key: 'status', header: 'Situação', mobile: 'badge' },
    { key: 'recovered', header: 'Recuperado', align: 'end' },
    { key: 'net', header: 'Líquido', align: 'end' },
    { key: 'sentAt', header: 'Enviado em' },
  ];
}
