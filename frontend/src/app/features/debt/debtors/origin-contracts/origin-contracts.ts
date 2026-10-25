import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { Amount } from '../../../../shared/components/amount/amount';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { nameMap } from '../../../../shared/data/name-map';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../../../organization/data/organization.lookups';
import { OriginContractsApi } from '../../data/debt.api';
import { INSTRUMENT_TYPE_LABELS, OBLIGATION_ROLE_LABELS, titleLabel } from '../../data/debt.labels';
import { ObligationPartyView, OriginContractView, TitleView } from '../../data/debt.models';

/** Títulos agrupados pelo contrato de origem (financiamento, cartão, plano) informado na carga. */
export interface OriginContractGroup {
  contractId: string | null;
  creditorId: string;
  titles: TitleView[];
  principal: number;
  openBalance: number;
  openTitles: number;
}

export function groupByOriginContract(titles: readonly TitleView[]): OriginContractGroup[] {
  const groups = new Map<string, OriginContractGroup>();
  for (const t of titles) {
    const key = `${t.creditorId}|${t.originContractId ?? ''}`;
    const group = groups.get(key) ?? {
      contractId: t.originContractId,
      creditorId: t.creditorId,
      titles: [],
      principal: 0,
      openBalance: 0,
      openTitles: 0,
    };
    group.titles.push(t);
    group.principal += Number(t.principalAmount) || 0;
    if (t.status === 'OPEN' || t.status === 'SUSPENDED') {
      group.openBalance += Number(t.openBalance) || 0;
      group.openTitles += 1;
    }
    groups.set(key, group);
  }
  return [...groups.values()].sort((a, b) => b.openBalance - a.openBalance);
}

interface ContractDetails {
  contract: OriginContractView | null;
  parties: ObligationPartyView[];
}

/**
 * Contratos de origem do devedor: produto, instrumento (define a via judicial e a prescrição),
 * valores e coobrigados ativos (documento mascarado). Somente leitura nesta tela.
 */
@Component({
  selector: 'app-origin-contracts',
  imports: [RouterLink, DataTable, CellDef, Amount, AppDatePipe],
  template: `
    <app-data-table
      caption="Contratos de origem"
      [rows]="groups()"
      [columns]="columns"
      [loading]="loading() || details.isLoading()"
      [rowKey]="groupKey"
      emptyIcon="file-earmark-text"
      emptyMessage="Nenhum contrato de origem informado nas cargas."
    >
      <ng-template appCell="contract" [appCellOf]="groups()" let-g>
        @let c = contractOf(g);
        @if (c) {
          <strong class="app-numeric">{{ c.externalId }}</strong>
          <div class="app-muted">{{ c.product || 'Produto não informado' }}</div>
        } @else if (g.contractId) {
          <span class="app-muted">Contrato indisponível</span>
        } @else {
          <span class="app-muted">Sem contrato informado</span>
        }
      </ng-template>
      <ng-template appCell="instrument" [appCellOf]="groups()" let-g>
        @let c = contractOf(g);
        @if (c?.instrumentType) {
          {{ instruments[c!.instrumentType!] }}
          @if (c!.executiveTitle) {
            <div class="app-muted">Título executivo</div>
          }
        } @else {
          <span class="app-muted">Não classificado</span>
        }
      </ng-template>
      <ng-template appCell="creditor" [appCellOf]="groups()" let-g>
        {{ creditors.name(g.creditorId) }}
        @if (contractOf(g)?.signedAt) {
          <div class="app-muted">Assinado em {{ contractOf(g)!.signedAt | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="parties" [appCellOf]="groups()" let-g>
        @for (p of activeParties(g); track p.partyId) {
          <div>{{ p.name }} <span class="app-muted">· {{ roles[p.role] ?? p.role }}</span></div>
        } @empty {
          <span class="app-muted">—</span>
        }
      </ng-template>
      <ng-template appCell="titles" [appCellOf]="groups()" let-g>
        @for (t of g.titles; track t.id) {
          <div><a class="app-numeric" [routerLink]="['/titulos', t.id]">{{ titleLabel(t) }}</a></div>
        }
      </ng-template>
      <ng-template appCell="amounts" [appCellOf]="groups()" let-g>
        <app-amount [value]="g.openBalance" />
        <div class="app-muted">de <app-amount [value]="contractOf(g)?.originalAmount ?? g.principal" /> · {{ g.openTitles }} em cobrança</div>
      </ng-template>
    </app-data-table>
  `,
})
export class OriginContracts {
  readonly titles = input.required<readonly TitleView[]>();
  readonly loading = input(false);

  private readonly api = inject(OriginContractsApi);
  protected readonly instruments = INSTRUMENT_TYPE_LABELS;
  protected readonly roles = OBLIGATION_ROLE_LABELS;
  protected readonly titleLabel = titleLabel;

  protected readonly groups = computed(() => groupByOriginContract(this.titles()));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.groups().map((g) => g.creditorId));
  protected readonly groupKey = (g: OriginContractGroup) => `${g.creditorId}|${g.contractId ?? ''}`;

  /** Contrato e partes de cada id; um contrato inacessível não impede a exibição dos demais. */
  protected readonly details = rxResource({
    params: () => {
      const ids = [...new Set(this.groups().map((g) => g.contractId).filter((id): id is string => !!id))].sort();
      return ids.length ? ids : undefined;
    },
    stream: ({ params }) =>
      forkJoin(
        params.map((id) =>
          forkJoin({
            contract: this.api.get(id).pipe(catchError(() => of(null))),
            parties: this.api.parties(id).pipe(catchError(() => of<ObligationPartyView[]>([]))),
          }).pipe(map((d): [string, ContractDetails] => [id, d])),
        ),
      ).pipe(map((entries) => new Map(entries))),
  });

  protected readonly columns: TableColumn[] = [
    { key: 'contract', header: 'Contrato' },
    { key: 'instrument', header: 'Instrumento' },
    { key: 'creditor', header: 'Credor' },
    { key: 'parties', header: 'Coobrigados' },
    { key: 'titles', header: 'Títulos' },
    { key: 'amounts', header: 'Saldo em cobrança', align: 'end' },
  ];

  protected contractOf(g: OriginContractGroup): OriginContractView | null {
    return g.contractId && this.details.hasValue() ? (this.details.value()?.get(g.contractId)?.contract ?? null) : null;
  }

  protected activeParties(g: OriginContractGroup): ObligationPartyView[] {
    const parties = g.contractId && this.details.hasValue() ? (this.details.value()?.get(g.contractId)?.parties ?? []) : [];
    return parties.filter((p) => p.active && p.role !== 'PRINCIPAL');
  }
}
