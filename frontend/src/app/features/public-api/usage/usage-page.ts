import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { debounceTime, finalize, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { BarChart } from '../../analytics/components/bar-chart';
import { ChartRow } from '../../analytics/components/chart.models';
import { ColumnChart } from '../../analytics/components/column-chart';
import { KpiCard } from '../../analytics/components/kpi-card';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { PublicApiUsageApi } from '../data/public-api.api';
import { formatBytes } from '../data/public-api.labels';
import { UsageFilter, UsageReport } from '../data/public-api.models';

const COUNT = new Intl.NumberFormat('pt-BR');

function isoDay(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * Consumo da API pública: requisições, bytes e registros por dia, por endpoint e por token, com exportação em CSV.
 * As últimas requisições podem levar até um minuto para aparecer (gravação em lote).
 */
@Component({
  selector: 'app-public-api-usage',
  imports: [ReactiveFormsModule, ...FORM_FIELD, BarChart, ColumnChart, EmptyState, EntityPicker, Icon, KpiCard, PageHeader, Skeleton],
  template: `
    <app-page-header title="Consumo da API pública" subtitle="Requisições, dados trafegados e registros processados pelos tokens dos credores. Base do extrato mensal.">
      @if (canExport()) {
        <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="export()" [disabled]="exporting()" data-tour="usage-export">
          <app-icon name="download" />
          Exportar CSV
        </button>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar consumo">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="De">
        <input appInput type="date" formControlName="from" required />
      </app-field>
      <app-field label="Até">
        <input appInput type="date" formControlName="to" required />
      </app-field>
      @if (filters.controls.token.value) {
        <div class="app-filters__actions">
          <button class="btn btn-link" type="button" (click)="filters.controls.token.setValue('')">Ver todos os tokens</button>
        </div>
      }
    </form>

    @if (report.isLoading() && !data()) {
      <app-skeleton variant="card" [rows]="5" />
    } @else if (report.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar o consumo." message="Verifique o período (até 366 dias) e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="report.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (data(); as r) {
      <div class="up-kpis" data-tour="usage-kpis">
        <app-kpi-card label="Requisições" [detail]="count(r.totals.billableRequests) + ' cobráveis (2xx/3xx)'">{{ count(r.totals.requests) }}</app-kpi-card>
        <app-kpi-card label="Limitadas (429)" detail="Não cobradas">{{ count(r.totals.throttled) }}</app-kpi-card>
        <app-kpi-card label="Erros (não cobrados)" [detail]="count(r.totals.clientErrors) + ' do cliente (4xx) · ' + count(r.totals.serverErrors) + ' da plataforma (5xx)'">{{ count(r.totals.clientErrors + r.totals.serverErrors) }}</app-kpi-card>
        <app-kpi-card label="Dados recebidos" detail="Corpo das requisições">{{ bytes(r.totals.bytesIn) }}</app-kpi-card>
        <app-kpi-card label="Dados enviados" detail="Corpo das respostas">{{ bytes(r.totals.bytesOut) }}</app-kpi-card>
        <app-kpi-card label="Registros" detail="Títulos importados, contatos, pagamentos…">{{ count(r.totals.records) }}</app-kpi-card>
      </div>
      @if (!r.totals.requests && !r.totals.records) {
        <app-empty-state icon="activity" title="Sem consumo no período." message="As chamadas aparecem aqui até um minuto depois de feitas." />
      } @else {
        <section class="app-card" data-tour="usage-daily">
          <app-column-chart title="Requisições por dia" [summary]="dailySummary()" [series]="{ name: 'Requisições', color: '--app-chart-1' }" [rows]="dailyRows()" [money]="false" />
        </section>
        <div class="up-grid">
          <section class="app-card">
            <app-bar-chart title="Por endpoint" categoryHeader="Endpoint" noteHeader="Dados" [summary]="'Endpoints mais chamados no período.'" [series]="[{ name: 'Requisições', color: '--app-chart-2' }]" [rows]="routeRows()" />
          </section>
          <section class="app-card" data-tour="usage-tokens">
            <app-bar-chart title="Por token" categoryHeader="Token" noteHeader="Registros" [summary]="'Consumo de cada token no período.'" [series]="[{ name: 'Requisições', color: '--app-chart-3' }]" [rows]="tokenRows()" />
          </section>
        </div>
      }
    }
  `,
  styles: `
    .up-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 180px), 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .up-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 460px), 1fr));
      gap: var(--app-gap);
      margin-top: var(--app-gap);
      .app-card + .app-card {
        margin-top: 0;
      }
    }
  `,
})
export class UsagePage {
  private readonly api = inject(PublicApiUsageApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly canExport = computed(() => this.auth.hasPermission(Permission.REPORT_EXPORT));
  protected readonly exporting = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly today = new Date();
  protected readonly filters = this.fb.group({
    creditor: this.fb.control<string[]>([]),
    from: [isoDay(new Date(this.today.getFullYear(), this.today.getMonth(), 1))],
    to: [isoDay(this.today)],
    token: [inject(ActivatedRoute).snapshot.queryParamMap.get('token') ?? ''],
  });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => this.current())),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly report = rxResource({ params: () => this.filter(), stream: ({ params }) => this.api.report(params) });
  protected readonly data = computed<UsageReport | null>(() => (this.report.hasValue() ? this.report.value() : null));

  protected readonly dailyRows = computed<ChartRow[]>(() =>
    (this.data()?.daily ?? []).map((d) => ({
      key: d.day,
      label: `${d.day.slice(8, 10)}/${d.day.slice(5, 7)}`,
      values: [d.totals.requests],
      display: [COUNT.format(d.totals.requests)],
      note: `${formatBytes(d.totals.bytesIn)} recebidos · ${formatBytes(d.totals.bytesOut)} enviados`,
    })),
  );
  protected readonly dailySummary = computed(() => {
    const days = this.data()?.daily ?? [];
    const peak = days.reduce((a, b) => (b.totals.requests > (a?.totals.requests ?? -1) ? b : a), days[0]);
    return peak ? `Pico de ${COUNT.format(peak.totals.requests)} requisições em ${peak.day.split('-').reverse().join('/')}.` : '';
  });
  protected readonly routeRows = computed<ChartRow[]>(() =>
    (this.data()?.byRoute ?? []).slice(0, 12).map((r) => ({
      key: `${r.method} ${r.route}`,
      label: `${r.method} ${r.route.replace('/public-api/v1', '')}`,
      values: [r.totals.requests],
      display: [COUNT.format(r.totals.requests)],
      note: `${formatBytes(r.totals.bytesIn + r.totals.bytesOut)}`,
    })),
  );
  protected readonly tokenRows = computed<ChartRow[]>(() =>
    (this.data()?.byToken ?? []).slice(0, 12).map((t) => ({
      key: t.tokenId,
      label: t.name ? `${t.name} (${t.prefix})` : t.tokenId,
      values: [t.totals.requests],
      display: [COUNT.format(t.totals.requests)],
      note: COUNT.format(t.totals.records),
    })),
  );

  protected count(value: number): string {
    return COUNT.format(value);
  }

  protected bytes(value: number): string {
    return formatBytes(value);
  }

  protected export(): void {
    this.exporting.set(true);
    this.api
      .exportUsage(this.filter())
      .pipe(finalize(() => this.exporting.set(false)))
      .subscribe(() => this.notifications.info('Exportação gerada (registrada no log de acesso).'));
  }

  private current(): UsageFilter {
    const v = this.filters.getRawValue();
    return { from: v.from, to: v.to, creditorId: v.creditor[0] ?? null, tokenId: v.token || null };
  }
}
