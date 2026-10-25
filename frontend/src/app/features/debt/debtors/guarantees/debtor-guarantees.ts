import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Amount } from '../../../../shared/components/amount/amount';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../../shared/data/name-map';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../../../organization/data/organization.lookups';
import { OverdueBadge } from '../../components/overdue-badge';
import { COLLATERAL_TYPE_LABELS, OBLIGATION_ROLE_LABELS } from '../../data/debt.labels';
import { DebtorNames } from '../../data/debt.lookups';
import { CoObligationSummary, CollateralSummary, DebtorSummary } from '../../data/debt.models';

/**
 * Garantias e coobrigações do devedor (consulta), a partir da ficha consolidada: contratos de
 * terceiros em que ele é avalista, fiador ou codevedor, e garantias dos contratos em que participa.
 */
@Component({
  selector: 'app-debtor-guarantees',
  imports: [RouterLink, DataTable, CellDef, StatusChip, Amount, AppDatePipe, OverdueBadge],
  template: `
    <h3 class="app-section-title">Coobrigações em contratos de terceiros</h3>
    <app-data-table
      caption="Coobrigações do devedor"
      [rows]="coObligations()"
      [columns]="coColumns"
      [loading]="loading()"
      [rowKey]="coKey"
      emptyIcon="person-hearts"
      emptyMessage="O devedor não é avalista, fiador nem codevedor em outros contratos."
    >
      <ng-template appCell="contract" [appCellOf]="coObligations()" let-c>
        <span class="app-numeric">{{ c.contractExternalId }}</span>
        <div class="app-muted">{{ creditors.name(c.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="role" [appCellOf]="coObligations()" let-c>
        {{ roles[c.role] ?? c.role }}
        @if (c.sinceDate) {
          <div class="app-muted">desde {{ c.sinceDate | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="principal" [appCellOf]="coObligations()" let-c>
        <a [routerLink]="['/devedores', c.principalDebtorId]">{{ debtors.name(c.principalDebtorId) }}</a>
      </ng-template>
      <ng-template appCell="overdue" [appCellOf]="coObligations()" let-c><app-overdue-badge [days]="c.maxDaysOverdue" /></ng-template>
      <ng-template appCell="openBalance" [appCellOf]="coObligations()" let-c>
        <app-amount [value]="c.openBalance" />
        <div class="app-muted">{{ c.titleCount }} título(s)</div>
      </ng-template>
    </app-data-table>

    <h3 class="app-section-title gu-gap">Garantias</h3>
    <app-data-table
      caption="Garantias dos contratos do devedor"
      [rows]="collateral()"
      [columns]="collateralColumns"
      [loading]="loading()"
      [rowKey]="collateralKey"
      emptyIcon="shield"
      emptyMessage="Nenhuma garantia nos contratos em que o devedor participa."
    >
      <ng-template appCell="type" [appCellOf]="collateral()" let-g>
        {{ types[g.type] ?? g.type }}
        <div class="app-muted">{{ g.assetSummary || 'Bem não descrito' }}</div>
      </ng-template>
      <ng-template appCell="debtorRole" [appCellOf]="collateral()" let-g>
        {{ roles[g.debtorRole] ?? g.debtorRole }}
        <div class="app-muted">{{ creditors.name(g.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="collateral()" let-g><app-status-chip domain="collateral" [status]="g.status" /></ng-template>
      <ng-template appCell="estimatedValue" [appCellOf]="collateral()" let-g>
        <app-amount [value]="g.estimatedValue" />
        <div class="app-muted">{{ g.lastAppraisalDate ? 'avaliada em ' + (g.lastAppraisalDate | appDate: 'date') : 'sem avaliação' }}</div>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .gu-gap {
      margin-top: 24px;
    }
  `,
})
export class DebtorGuarantees {
  readonly summary = input<DebtorSummary | undefined>();
  readonly loading = input(false);

  protected readonly roles = OBLIGATION_ROLE_LABELS;
  protected readonly types = COLLATERAL_TYPE_LABELS;
  protected readonly coObligations = computed(() => this.summary()?.coObligations ?? []);
  protected readonly collateral = computed(() => this.summary()?.collateral ?? []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [
    ...this.coObligations().map((c) => c.creditorId),
    ...this.collateral().map((g) => g.creditorId),
  ]);
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.coObligations().map((c) => c.principalDebtorId));
  protected readonly coKey = (c: CoObligationSummary) => c.contractId;
  protected readonly collateralKey = (g: CollateralSummary) => g.collateralId;

  protected readonly coColumns: TableColumn[] = [
    { key: 'contract', header: 'Contrato' },
    { key: 'role', header: 'Papel do devedor' },
    { key: 'principal', header: 'Devedor principal' },
    { key: 'overdue', header: 'Maior atraso' },
    { key: 'openBalance', header: 'Saldo em cobrança', align: 'end' },
  ];

  protected readonly collateralColumns: TableColumn[] = [
    { key: 'type', header: 'Garantia' },
    { key: 'debtorRole', header: 'Participação' },
    { key: 'status', header: 'Situação' },
    { key: 'estimatedValue', header: 'Valor recuperável estimado', align: 'end' },
  ];
}
