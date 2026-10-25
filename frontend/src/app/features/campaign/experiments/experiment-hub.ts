import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import { catchError, debounceTime, forkJoin, map, of, startWith } from 'rxjs';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { CampaignsApi, ExperimentsApi } from '../data/campaign.api';
import { METRIC_LABELS } from '../data/campaign.labels';
import { CampaignFilter, ExperimentView } from '../data/campaign.models';

/**
 * Testes A/B de todas as campanhas visíveis: o teste aberto (ou o último) de cada uma, com a
 * métrica principal e a situação. Cada campanha tem a própria tela de configuração e resultados.
 */
@Component({
  selector: 'app-experiment-hub',
  imports: [ReactiveFormsModule, RouterLink, NgbTooltip, ...FORM_FIELD, Icon, CellDef, DataTable, EntityPicker, PageHeader, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Testes A/B" subtitle="Variantes da régua comparadas com um grupo de controle, com intervalo de confiança por braço." />
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar campanhas">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="Situação da campanha">
        <select appInput formControlName="status">
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
    </form>
    <app-data-table
      caption="Campanhas e testes A/B"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="eyedropper"
      emptyMessage="Nenhuma campanha encontrada."
      emptyHint="Testes A/B são criados dentro de cada campanha com régua publicada."
    >
      <ng-template appCell="campaign" [appCellOf]="query.rows()" let-c>
        <a [routerLink]="['/campanhas', c.id, 'testes']">{{ c.name }}</a>
        <div class="app-muted xh-small">{{ creditors.name(c.creditorId) }} · <app-status-chip domain="campaign" [status]="c.status" /></div>
      </ng-template>
      <ng-template appCell="experiment" [appCellOf]="query.rows()" let-c>
        @let e = latest(c.id);
        @if (e === undefined) {
          <span class="app-muted">…</span>
        } @else if (e) {
          {{ e.name }}
          <div class="app-muted xh-small">{{ metricLabels[e.primaryMetric] }} · controle {{ e.controlShare }}% · {{ e.variants.length }} variante(s)</div>
        } @else {
          <span class="app-muted">{{ c.publishedCadenceId ? 'Nenhum teste' : 'Sem régua publicada' }}</span>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-c>
        @if (latest(c.id); as e) {
          <app-status-chip domain="experiment" [status]="e.status" />
        }
      </ng-template>
      <ng-template appCell="since" [appCellOf]="query.rows()" let-c>
        @if (latest(c.id); as e) {
          {{ (e.startedAt ?? e.createdAt) | appDate: 'date' }}
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-c>
        <a class="btn btn-link" [routerLink]="['/campanhas', c.id, 'testes']" [attr.aria-label]="'Testes A/B de ' + c.name" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .xh-small {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px;
      font-size: var(--app-text-xs);
    }
  `,
})
export class ExperimentHub {
  private readonly campaigns = inject(CampaignsApi);
  private readonly api = inject(ExperimentsApi);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly statuses = statusOptions('campaign');
  protected readonly metricLabels = METRIC_LABELS;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), status: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map((): CampaignFilter => {
        const v = this.filters.getRawValue();
        return { creditorId: v.creditor[0] ?? null, status: v.status || null };
      }),
    ),
    { initialValue: { creditorId: null, status: null } as CampaignFilter, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.campaigns.search(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((c) => c.creditorId));

  /** Testes de cada campanha da página (uma consulta por campanha; falha isolada vira "sem teste"). */
  private readonly experimentsRes = rxResource({
    params: () => {
      const ids = this.query.rows().map((c) => c.id);
      return ids.length ? ids : undefined;
    },
    stream: ({ params }) =>
      forkJoin(params.map((id) => this.api.list(id).pipe(catchError(() => of<ExperimentView[]>([])), map((list) => [id, list] as const)))).pipe(map((pairs) => new Map(pairs))),
  });
  private readonly byCampaign = valueOr(this.experimentsRes, new Map<string, readonly ExperimentView[]>());
  private readonly latestByCampaign = computed(() => {
    const result = new Map<string, ExperimentView | null>();
    for (const [id, list] of this.byCampaign()) {
      const sorted = [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      result.set(id, sorted.find((e) => e.status !== 'STOPPED') ?? sorted[0] ?? null);
    }
    return result;
  });

  protected readonly columns: TableColumn[] = [
    { key: 'campaign', header: 'Campanha' },
    { key: 'experiment', header: 'Teste aberto ou último' },
    { key: 'status', header: 'Situação do teste' },
    { key: 'since', header: 'Desde' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  /** `undefined` enquanto carrega; `null` quando a campanha não tem teste. */
  protected latest(campaignId: string): ExperimentView | null | undefined {
    const map = this.latestByCampaign();
    return map.has(campaignId) ? map.get(campaignId)! : undefined;
  }
}
