import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { CommissionStatementsApi } from '../data/incentive.api';
import { CREDIT_NOTE_LABELS, DELAY_BUCKET_LABELS, ENTRY_KIND_LABELS, ORIGIN_LABELS, ROLE_LABELS } from '../data/incentive.labels';
import { LedgerEntryView, YearMonth } from '../data/incentive.models';
import { monthLabel, pct } from '../data/incentive.rules';

/**
 * Lançamentos do razão de comissão de um negociador (append-only): pagamento creditado com peso
 * de crédito, carência e período de competência. Com `disputable`, o beneficiário contesta um
 * lançamento (emitido por `dispute`).
 */
@Component({
  selector: 'app-ledger-table',
  imports: [RouterLink, NgbTooltip, Amount, CellDef, DataTable, Icon, StatusChip, AppDatePipe],
  template: `
    <app-data-table
      [caption]="caption()"
      [rows]="query.rows()"
      [columns]="columns()"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="journal-text"
      emptyMessage="Nenhum lançamento no período."
      emptyHint="Lançamentos nascem de pagamentos confirmados de acordos com crédito para o negociador."
    >
      <ng-template appCell="paid" [appCellOf]="query.rows()" let-e>
        {{ e.paidOn | appDate: 'date' }}
        <div class="app-muted in-small">{{ kinds[e.kind] ?? e.kind }} · {{ month(e.period) }}</div>
      </ng-template>
      <ng-template appCell="origin" [appCellOf]="query.rows()" let-e>
        {{ label(origins, e.origin) }}
        <div class="app-muted in-small">
          {{ label(roles, e.role, '') }}{{ e.creditorId ? ' · ' + creditors.name(e.creditorId) : '' }}{{ e.delayBucket ? ' · ' + buckets[e.delayBucket] : '' }}
        </div>
      </ng-template>
      <ng-template appCell="base" [appCellOf]="query.rows()" let-e><app-amount [value]="e.baseAmount" [signed]="e.kind === 'REVERSAL'" /></ng-template>
      <ng-template appCell="weight" [appCellOf]="query.rows()" let-e>
        {{ pct(e.creditWeight) }}
        @if (e.renegotiationFactor !== null && e.renegotiationFactor !== 100) {
          <div class="app-muted in-small">renegociação {{ pct(e.renegotiationFactor) }}</div>
        }
        @if (e.creditNote && notes[e.creditNote]) {
          <div class="app-muted in-small">{{ notes[e.creditNote] }}</div>
        }
      </ng-template>
      <ng-template appCell="credited" [appCellOf]="query.rows()" let-e><app-amount [value]="e.creditedAmount" [signed]="true" /></ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-e>
        <app-status-chip domain="commissionEntry" [status]="e.status" />
        @if (e.status === 'PENDING' && e.clearsOn) {
          <div class="app-muted in-small">libera em {{ e.clearsOn | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-e>
        @if (e.agreementId && linkAgreements()) {
          <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/remuneracao/atribuicoes', e.agreementId]" aria-label="Ver a atribuição de crédito do acordo" ngbTooltip="Atribuição do acordo">
            <app-icon name="diagram-3" />
          </a>
        }
        @if (disputable()) {
          <button type="button" class="btn btn-sm btn-outline-secondary border-0" (click)="onDispute(e)" aria-label="Contestar este lançamento" ngbTooltip="Contestar">
            <app-icon name="flag" />
          </button>
        }
      </ng-template>
    </app-data-table>
  `,
  styleUrl: '../incentive.scss',
})
export class LedgerTable {
  /** Negociador (nulo = o próprio). */
  readonly userId = input<string | null>(null);
  readonly period = input<YearMonth | null>(null);
  readonly caption = input('Lançamentos do razão de comissão');
  readonly disputable = input(false);
  readonly linkAgreements = input(true);
  /** Chamado quando o beneficiário contesta um lançamento. */
  readonly disputeEntry = input<((entry: LedgerEntryView) => void) | null>(null);

  private readonly api = inject(CommissionStatementsApi);
  protected readonly kinds = ENTRY_KIND_LABELS;
  protected readonly origins = ORIGIN_LABELS as Record<string, string>;
  protected readonly roles = ROLE_LABELS;
  protected readonly buckets = DELAY_BUCKET_LABELS as Record<string, string>;
  protected readonly notes = CREDIT_NOTE_LABELS;
  protected readonly pct = pct;
  protected readonly month = monthLabel;

  private readonly filter = computed(() => ({ userId: this.userId(), period: this.period() }), {
    equal: (a, b) => a.userId === b.userId && a.period === b.period,
  });
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.ledger(f.userId, f.period, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((e) => e.creditorId));

  protected readonly columns = computed<TableColumn[]>(() => [
    { key: 'paid', header: 'Pagamento' },
    { key: 'origin', header: 'Origem do acordo' },
    { key: 'base', header: 'Base', align: 'end' },
    { key: 'weight', header: 'Peso de crédito', align: 'end' },
    { key: 'credited', header: 'Creditado', align: 'end' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ]);

  protected label(map: Record<string, string>, key: string | null, empty = '—'): string {
    return key ? (map[key] ?? key) : empty;
  }

  protected onDispute(entry: LedgerEntryView): void {
    this.disputeEntry()?.(entry);
  }

  reload(): void {
    this.query.reload();
  }
}
