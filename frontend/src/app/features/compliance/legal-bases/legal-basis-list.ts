import { Component, OnInit, computed, inject, input } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { map, startWith } from 'rxjs';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { toLocalDate } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup, PortfolioNames } from '../../organization/data/organization.lookups';
import { LegalBasesApi } from '../data/compliance.api';
import { LEGAL_BASIS_LABELS, PROCESSING_ROLE_LABELS } from '../data/compliance.labels';
import { LegalBasisView } from '../data/compliance.models';
import { LegalBasisEnd } from './legal-basis-end';
import { LegalBasisForm, LegalBasisFormData } from './legal-basis-form';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { DialogService } from '../../../shared/dialog/dialog.service';

/** Vigência do registro na data (`yyyy-MM-dd`). */
export function legalBasisPeriod(r: Pick<LegalBasisView, 'validFrom' | 'validUntil'>, today: string): 'CURRENT' | 'FUTURE' | 'ENDED' {
  if (r.validFrom > today) {
    return 'FUTURE';
  }
  return r.validUntil && r.validUntil < today ? 'ENDED' : 'CURRENT';
}

@Component({
  selector: 'app-legal-basis-list',
  imports: [ReactiveFormsModule, DataTable, CellDef, EmptyState, EntityPicker, PageHeader, StatusChip, AppDatePipe, Icon, NgbTooltip],
  template: `
    <app-page-header title="Bases legais" subtitle="Fundamento do tratamento dos dados (LGPD art. 7º) por credor e carteira, com prazo de retenção.">
      <button class="btn btn-primary" type="button" [disabled]="!creditorId()" (click)="register()">
        <app-icon name="hammer" />
        Registrar base legal
      </button>
    </app-page-header>

    <div class="app-filters" data-tour="filters">
      <div class="app-filters__wide">
        <app-entity-picker [control]="creditorControl" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
    </div>

    @if (!creditorId()) {
      <section class="app-card">
        <app-empty-state icon="buildings" title="Escolha o credor." message="As bases legais são registradas por credor e, se necessário, por carteira." />
      </section>
    } @else {
      <app-data-table
        caption="Bases legais do credor"
        [rows]="rows()"
        [columns]="columns"
        [loading]="records.isLoading()"
        [failed]="records.status() === 'error'"
        (retry)="records.reload()"
        emptyIcon="hammer"
        emptyMessage="Nenhuma base legal registrada para este credor."
        emptyHint="Sem base legal vigente, a retenção usa apenas a política geral da assessoria."
      >
        <ng-template appCell="legalBasis" [appCellOf]="rows()" let-r>
          {{ basisLabels[r.legalBasis] }}
          <div class="app-muted">{{ roleLabels[r.processingRole] }} · {{ r.contractReference }}</div>
        </ng-template>
        <ng-template appCell="portfolio" [appCellOf]="rows()" let-r>{{ r.portfolioId ? portfolios.name(r.portfolioId) : 'Todas as carteiras' }}</ng-template>
        <ng-template appCell="period" [appCellOf]="rows()" let-r>
          {{ r.validFrom | appDate: 'date' }} – {{ r.validUntil ? (r.validUntil | appDate: 'date') : 'sem prazo' }}
          @if (r.endReason) {
            <div class="app-muted">{{ r.endReason }}</div>
          }
        </ng-template>
        <ng-template appCell="retentionMonths" [appCellOf]="rows()" let-r>{{ r.retentionMonths }} meses</ng-template>
        <ng-template appCell="status" [appCellOf]="rows()" let-r><app-status-chip domain="legalBasis" [status]="period(r)" /></ng-template>
        <ng-template appCell="actions" [appCellOf]="rows()" let-r>
          @if (period(r) !== 'ENDED') {
            <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="end(r)" aria-label="Encerrar vigência" ngbTooltip="Encerrar vigência">
              <app-icon name="calendar-x" />
            </button>
          }
        </ng-template>
      </app-data-table>
    }
  `,
})
export class LegalBasisList implements OnInit {
  /** `?credor=` vindo de outras telas. */
  readonly credor = input<string>();

  private readonly api = inject(LegalBasesApi);
  private readonly dialog = inject(DialogService);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly basisLabels = LEGAL_BASIS_LABELS;
  protected readonly roleLabels = PROCESSING_ROLE_LABELS;
  private readonly today = toLocalDate(new Date())!;

  protected readonly creditorControl = new FormControl<string[]>([], { nonNullable: true });
  protected readonly creditorId = toSignal(
    this.creditorControl.valueChanges.pipe(
      startWith([] as string[]),
      map((ids) => ids[0] ?? null),
    ),
    { initialValue: null },
  );
  protected readonly records = rxResource({
    params: () => this.creditorId() ?? undefined,
    stream: ({ params }) => this.api.listByCreditor(params),
  });
  private readonly list = valueOr(this.records, []);
  protected readonly rows = computed(() => [...this.list()].sort((a, b) => b.validFrom.localeCompare(a.validFrom)));
  protected readonly creditors = nameMap(this.creditorSource, () => [this.creditorId()]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.rows().map((r) => r.portfolioId));

  protected readonly columns: TableColumn[] = [
    { key: 'legalBasis', header: 'Base legal' },
    { key: 'portfolio', header: 'Alcance' },
    { key: 'period', header: 'Vigência' },
    { key: 'legalResponsible', header: 'Responsável jurídico' },
    { key: 'retentionMonths', header: 'Retenção', align: 'end' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  ngOnInit(): void {
    const initial = this.credor();
    if (initial) {
      this.creditorControl.setValue([initial]);
    }
  }

  protected period(r: LegalBasisView): 'CURRENT' | 'FUTURE' | 'ENDED' {
    return legalBasisPeriod(r, this.today);
  }

  protected register(): void {
    const creditorId = this.creditorId();
    if (!creditorId) {
      return;
    }
    this.dialog
      .open<LegalBasisForm, LegalBasisFormData, LegalBasisView>(LegalBasisForm, {
        data: { creditorId, creditorName: this.creditors.name(creditorId) },
        size: 'lg',
      })
      .afterClosed()
      .subscribe((saved) => saved && this.records.reload());
  }

  protected end(r: LegalBasisView): void {
    this.dialog
      .open<LegalBasisEnd, LegalBasisView, LegalBasisView>(LegalBasisEnd, { data: r })
      .afterClosed()
      .subscribe((saved) => saved && this.records.reload());
  }
}
