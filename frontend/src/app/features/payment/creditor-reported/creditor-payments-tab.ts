import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { debounceTime, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { activeFilterCount } from '../../../shared/data/filter-count';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { FORM_FIELD } from '../../../shared/forms/field';
import { emptyPage } from '../../../shared/models/page';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { ORIGIN_LABELS } from '../data/payment.labels';
import { ReportedPaymentsPage, CreditorReportedApi, ReportedPaymentFilter, ReportedPaymentItem, ReportingChannel, ReportedPaymentStatus } from './creditor-reported.api';
import { CHANNEL_LABELS, amountRangeProblem, channelText, defaultPeriod, periodProblem, urlMoney } from './creditor-reported.rules';

const CHANNELS = Object.keys(CHANNEL_LABELS) as ReportingChannel[];

/**
 * Aba "Pagamentos informados pelo credor": pagamentos externos recebidos diretamente pelo credor (integração, portal do
 * credor ou operador da assessoria). O servidor aplica o escopo por credor e as permissões de cada coluna (devedor,
 * títulos, acordo, token); a tela só esconde o que veio vazio. Filtros na URL (só ids, enums, datas e valores).
 */
@Component({
  selector: 'app-creditor-payments-tab',
  imports: [FilterBar, Icon, InfoHint, NgbInputDatepicker, NgbTooltip, ...FORM_FIELD, ReactiveFormsModule, RouterLink, Amount, AppDatePipe, CellDef, DataTable, EntityPicker, StatusChip],
  template: `
    <app-filter-bar [activeCount]="activeFilters()">
      <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar pagamentos informados pelo credor">
        <app-field label="Pago de">
          <div class="input-group">
            <input appInput ngbDatepicker #from="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" required />
            <button type="button" class="btn btn-outline-secondary" (click)="from.toggle()" aria-label="Abrir calendário de início"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <app-field label="Pago até">
          <div class="input-group">
            <input appInput ngbDatepicker #to="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" required />
            <button type="button" class="btn btn-outline-secondary" (click)="to.toggle()" aria-label="Abrir calendário de fim"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <app-field label="Canal" hint="Por onde o pagamento foi informado: integração do credor (token), portal do credor ou operador da assessoria.">
          <select appInput formControlName="channel">
            <option value="">Todos</option>
            @for (c of channels; track c) {
              <option [ngValue]="c">{{ channelLabels[c] }}</option>
            }
          </select>
        </app-field>
        <app-field label="Situação">
          <select appInput formControlName="status">
            <option value="">Todas</option>
            @for (s of statuses; track s.value) {
              <option [ngValue]="s.value">{{ s.label }}</option>
            }
          </select>
        </app-field>
        <app-field label="Valor de">
          <div class="input-group">
            <span class="input-group-text" aria-hidden="true">R$</span>
            <input appInput appMoney formControlName="minAmount" [min]="0" />
          </div>
        </app-field>
        <app-field label="Valor até">
          <div class="input-group">
            <span class="input-group-text" aria-hidden="true">R$</span>
            <input appInput appMoney formControlName="maxAmount" [min]="0" />
          </div>
        </app-field>
        <div class="app-filters__actions">
          <button class="btn btn-link" type="button" (click)="clear()">Limpar filtros</button>
        </div>
      </form>
    </app-filter-bar>
    @if (canViewTokens() && filters.controls.token.value) {
      <p class="cs-chip" data-testid="token-filter">
        <app-icon name="funnel" /> {{ tokenFilterText() }}
        <button class="btn btn-sm btn-link" type="button" (click)="filters.controls.token.setValue('')">Ver todos os tokens</button>
      </p>
    }
    @if (problem(); as message) {
      <div class="alert alert-warning d-flex gap-2 align-items-start" role="alert" data-testid="filter-problem">
        <app-icon name="exclamation-triangle" /><span>{{ message }}</span>
      </div>
    } @else if (totals(); as t) {
      <p class="cs-totals" aria-live="polite" data-testid="totals">
        <strong>{{ t.count }}</strong> {{ t.count === 1 ? 'pagamento' : 'pagamentos' }} · informado <strong><app-amount [value]="t.amount" /></strong>
        · confirmado <strong><app-amount [value]="t.effectiveAmount" /></strong>
        <app-info-hint indicator="payment.creditor-reported.confirmed-total" label="Total confirmado" />
      </p>
    }
    <app-data-table
      caption="Pagamentos informados pelo credor"
      [rows]="query.rows()"
      [columns]="columns()"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      [sort]="query.sort()"
      (pageChange)="query.setPage($event)"
      (sortChange)="query.setSort($event)"
      (retry)="query.reload()"
      emptyIcon="cash-coin"
      [emptyMessage]="problem() ? 'Ajuste o período para consultar.' : 'Nenhum pagamento informado pelo credor no período.'"
      emptyHint="Aparecem aqui os pagamentos feitos diretamente ao credor, informados pela integração, pelo portal do credor ou por um operador."
    >
      <ng-template appCell="paidAt" [appCellOf]="query.rows()" let-p>{{ p.paidAt | appDate: 'date' }}</ng-template>
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-p>{{ creditors.name(p.creditorId) }}</ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-p>
        @if (p.debtorName) {
          @if (canViewDebtor()) {
            <a [routerLink]="['/devedores', p.debtorId]" [attr.aria-label]="'Abrir devedor ' + p.debtorName">{{ p.debtorName }}</a>
          } @else {
            {{ p.debtorName }}
          }
        } @else {
          <span class="app-muted">—</span>
        }
      </ng-template>
      <ng-template appCell="target" [appCellOf]="query.rows()" let-p>
        @if (p.agreementId && canViewAgreement()) {
          <a [routerLink]="['/negociacao/acordos', p.agreementId]" [attr.aria-label]="'Abrir acordo do ' + rowContext(p)">Acordo</a>
        } @else if (p.titleIds.length && canViewTitle()) {
          @for (id of p.titleIds.slice(0, 3); track id; let i = $index) {
            <a class="cs-link" [routerLink]="['/titulos', id]" [attr.aria-label]="'Abrir título ' + (i + 1) + ' do ' + rowContext(p)">{{ p.titleIds.length === 1 ? 'Título' : 'Título ' + (i + 1) }}</a>
          }
          @if (p.titleIds.length > 3) {
            <span class="app-muted">e mais {{ p.titleIds.length - 3 }}</span>
          }
        } @else {
          <span class="app-muted">{{ p.titleCount ? (p.titleCount === 1 ? '1 título' : p.titleCount + ' títulos') : 'Acordo' }}</span>
        }
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-p><app-amount [value]="p.amount" /></ng-template>
      <ng-template appCell="channel" [appCellOf]="query.rows()" let-p>
        {{ channel(p) }}
        @if (p.token?.found && canViewTokens()) {
          <div><a class="cs-small" [routerLink]="['/integracoes/tokens', p.token.id]" [attr.aria-label]="'Abrir token ' + (p.token.name ?? '')">Abrir token</a></div>
        }
        <div class="app-muted cs-small">{{ originLabels[p.origin] }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-p>
        <app-status-chip domain="payment" [status]="p.status" />
        @if (p.evidenceUnverified) {
          <div class="cs-small cs-warn d-flex align-items-center gap-1">
            <app-icon name="exclamation-triangle" /> Comprovante sem verificação de vírus
            <app-info-hint indicator="payment.creditor-reported.evidence-unverified" label="Comprovante sem verificação de vírus" />
          </div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-p>
        <a class="btn btn-sm btn-link" [routerLink]="['/pagamentos', p.paymentId]" [attr.aria-label]="'Abrir ' + rowContext(p)" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .cs-small { font-size: var(--app-text-xs); }
    .cs-warn { color: var(--bs-warning-text-emphasis); font-weight: var(--app-weight-semibold); }
    .cs-link + .cs-link { margin-left: 8px; }
    .cs-totals, .cs-chip { margin: 0 0 var(--app-gap); }
  `,
})
export class CreditorPaymentsTab {
  private readonly api = inject(CreditorReportedApi);
  private readonly auth = inject(AuthService);
  private readonly date = new AppDatePipe();
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly channels = CHANNELS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly originLabels = ORIGIN_LABELS;
  protected readonly statuses = statusOptions('payment');
  protected readonly canViewDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  protected readonly canViewTitle = computed(() => this.auth.hasAnyPermission([Permission.DEBTOR_VIEW, Permission.CASE_VIEW]));
  protected readonly canViewAgreement = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));
  protected readonly canViewTokens = computed(() => this.auth.hasPermission(Permission.PUBLIC_API_TOKEN_VIEW));

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly period = defaultPeriod();
  /** `?de=&ate=&credor=&canal=&token=&situacao=&valor-min=&valor-max=` (o período padrão não vai para a URL). */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      from: this.fb.control<string | null>(this.period.from),
      to: this.fb.control<string | null>(this.period.to),
      creditor: this.fb.control<string[]>([]),
      channel: this.fb.control<ReportingChannel | ''>(''),
      token: [''],
      status: this.fb.control<ReportedPaymentStatus | ''>(''),
      minAmount: this.fb.control<number | null>(null),
      maxAmount: this.fb.control<number | null>(null),
    }),
    {
      from: urlParam.date('de'),
      to: urlParam.date('ate'),
      creditor: urlParam.ids('credor'),
      channel: urlParam.choice('canal', CHANNELS),
      token: urlParam.id('token'),
      status: urlParam.choice('situacao', statusOptions('payment').map((s) => s.value as ReportedPaymentStatus)),
      minAmount: urlMoney('valor-min'),
      maxAmount: urlMoney('valor-max'),
    },
  );
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => this.current())),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly problem = computed(() => {
    const f = this.filter();
    return periodProblem(f.from, f.to) ?? amountRangeProblem(f.minAmount, f.maxAmount);
  });
  protected readonly totals = signal<ReportedPaymentsPage['totals'] | null>(null);
  protected readonly query = pagedQuery(
    this.filter,
    (f, page) => {
      if (periodProblem(f.from, f.to) || amountRangeProblem(f.minAmount, f.maxAmount)) {
        this.totals.set(null);
        return of(emptyPage<ReportedPaymentItem>(page.size));
      }
      return this.api.payments(f, page).pipe(map((r) => {
        this.totals.set(r.totals);
        return r.items;
      }));
    },
    { url: true, sortFields: { paidAt: 'paidAt', amount: 'amount' } },
  );
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((p) => p.creditorId));

  /** O devedor só aparece com `DEBTOR_VIEW` (o servidor manda o nome só com ela). */
  protected readonly columns = computed<TableColumn[]>(() => [
    { key: 'paidAt', header: 'Pago em', sortable: true, mobile: 'meta' },
    { key: 'creditor', header: 'Credor', mobile: 'meta' },
    ...(this.canViewDebtor() ? [{ key: 'debtor', header: 'Devedor', mobile: 'title' } as TableColumn] : []),
    { key: 'target', header: 'Títulos ou acordo', mobile: 'meta' },
    { key: 'amount', header: 'Valor', align: 'end', sortable: true, mobile: 'meta' },
    { key: 'channel', header: 'Canal', mobile: 'meta' },
    { key: 'status', header: 'Situação', mobile: 'badge' },
    { key: 'actions', header: 'Ações', actions: true },
  ]);

  /** Contexto da linha nos rótulos dos links repetidos ("pagamento de 20/11/2026, R$ 120,00") para o leitor de tela (UX-11). */
  protected rowContext(p: ReportedPaymentItem): string {
    return `pagamento de ${this.date.transform(p.paidAt, 'date')}, ${formatAmount(p.amount)}`;
  }

  /** Aviso do filtro por token com o nome do token quando ele está numa linha da página (UX-07). */
  protected tokenFilterText(): string {
    const id = this.filters.controls.token.value;
    const token = this.query.rows().find((p) => p.token?.id === id && p.token.found)?.token;
    return token?.name ? `Só os pagamentos do token ${token.name}${token.prefix ? ' (' + token.prefix + ')' : ''}` : 'Só os pagamentos de um token da integração';
  }

  protected channel(p: ReportedPaymentItem): string {
    return channelText(p);
  }

  protected clear(): void {
    this.filters.reset({ ...this.period, creditor: [], channel: '', token: '', status: '', minAmount: null, maxAmount: null });
  }

  private current(): ReportedPaymentFilter {
    const v = this.filters.getRawValue();
    return {
      from: v.from ?? '',
      to: v.to ?? '',
      creditorId: v.creditor[0] ?? null,
      channel: v.channel || null,
      // filtro por token só para quem vê os tokens (sem a permissão, o parâmetro da URL é ignorado)
      tokenId: this.canViewTokens() ? v.token || null : null,
      minAmount: v.minAmount,
      maxAmount: v.maxAmount,
      status: v.status || null,
    };
  }

  /** "Filtros (n)" do `app-filter-bar` no celular (ux.md §12.3). */
  protected readonly activeFilters = activeFilterCount(this.filters);
}
