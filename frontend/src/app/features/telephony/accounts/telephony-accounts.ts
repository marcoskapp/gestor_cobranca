import { DecimalPipe, PercentPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbDropdownModule, NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { DialerCampaignsApi, TelephonyAccountsApi, TelephonyRoutingApi } from '../data/telephony.api';
import { finalize } from 'rxjs';
import {
  CONNECTION_TEST_LABELS,
  PROVIDER_LABELS,
  READINESS_LABELS,
  ROUTE_SCOPE_LABELS,
  ROUTE_SCOPE_ORDER,
  TELEPHONY_CREDENTIAL_LABELS,
  TELEPHONY_ERROR_LABELS,
} from '../data/telephony.labels';
import { AccountComparison, RouteView, TelephonyAccountView } from '../data/telephony.models';
import {
  RouteDialog,
  RouteDialogData,
  TelephonyAccountDialog,
  TelephonyAccountDialogData,
  TelephonyCredentialsDialog,
  TelephonyCredentialsDialogData,
  WebhookUrlsDialog,
  WebhookUrlsDialogData,
} from './account-dialogs';

type Tab = 'accounts' | 'routes' | 'comparison';

/**
 * Plataformas de telefonia da assessoria em três abas: contas (várias, de provedores diferentes,
 * cada uma com bina, credenciais, gravação, tarifa e webhook próprio), roteamento (qual conta disca
 * em cada escopo e a contingência) e comparação entre plataformas no período.
 */
@Component({
  selector: 'app-telephony-accounts',
  imports: [NgbNavModule, NgbDropdownModule, Icon, CellDef, DataTable, PageHeader, StatusChip, AppDatePipe, DecimalPipe, PercentPipe],
  template: `
    <app-page-header title="Plataformas de telefonia" subtitle="Contas de cada plataforma, qual delas disca em cada credor, carteira ou campanha, e a comparação entre elas.">
      @if (canManage()) {
        @if (tab() === 'routes') {
          <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="editRoute(null)" [disabled]="!accountRows().length">
            <app-icon name="signpost-split" />
            Nova regra
          </button>
        } @else {
          <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="editAccount(null)" [disabled]="!providers.hasValue()">
            <app-icon name="plus-lg" />
            Nova conta
          </button>
        }
      }
    </app-page-header>

    <ul ngbNav #nav="ngbNav" class="nav-tabs mb-3" [activeId]="tab()" (activeIdChange)="tab.set($event)" data-tour="telephony-accounts-tabs">
      <li [ngbNavItem]="'accounts'">
        <button ngbNavLink type="button" class="d-inline-flex align-items-center gap-2"><app-icon name="plug" />Contas</button>
        <ng-template ngbNavContent>
          <app-data-table
            caption="Contas de telefonia"
            [rows]="accountRows()"
            [columns]="accountColumns"
            [loading]="accounts.isLoading()"
            [failed]="accounts.status() === 'error'"
            (retry)="accounts.reload()"
            emptyIcon="telephone"
            emptyMessage="Nenhuma conta de telefonia cadastrada."
            emptyHint="Cadastre a conta de cada plataforma (Twilio, Plivo, Alvaria) com as credenciais da própria plataforma."
          >
            <ng-template appCell="name" [appCellOf]="accountRows()" let-a>
              <strong>{{ a.name }}</strong>
              <div class="app-muted">{{ a.creditorId ? 'Só ' + creditors.name(a.creditorId) : 'Assessoria' }} · bina {{ a.callerId }}</div>
            </ng-template>
            <ng-template appCell="provider" [appCellOf]="accountRows()" let-a>
              {{ providerLabels[a.provider] }}
              <div class="small" [class.text-warning-emphasis]="a.readiness !== 'READY'" [class.app-muted]="a.readiness === 'READY'">
                {{ readinessLabels[a.readiness] }}{{ a.readiness === 'CONTRACT_PENDING' && a.dialable ? ' (homologação ligada)' : '' }}
              </div>
            </ng-template>
            <ng-template appCell="settings" [appCellOf]="accountRows()" let-a>
              {{ a.recordingEnabled ? 'Grava, com aviso' : 'Sem gravação' }}
              <div class="app-muted small">{{ a.ratePerMinute === null ? 'sem tarifa informada' : 'R$ ' + (a.ratePerMinute | number: '1.2-4') + '/min' }}</div>
            </ng-template>
            <ng-template appCell="credentials" [appCellOf]="accountRows()" let-a>
              @if (a.credentialKeys.length) {
                <span>{{ keyText(a) }}</span>
                <div class="app-muted small">trocadas em {{ a.credentialsUpdatedAt | appDate }}</div>
              } @else {
                <span class="fw-semibold text-danger-emphasis">Não configuradas</span>
              }
            </ng-template>
            <ng-template appCell="status" [appCellOf]="accountRows()" let-a>
              <app-status-chip domain="active" [status]="a.active" />
              @if (usage(a.id); as n) {
                <div class="app-muted small">{{ n }} regra(s)</div>
              }
            </ng-template>
            <ng-template appCell="actions" [appCellOf]="accountRows()" let-a>
              @if (canManage()) {
                <div ngbDropdown placement="bottom-end" class="d-inline-flex">
                  <button class="btn btn-link" type="button" ngbDropdownToggle [attr.aria-label]="'Ações da conta ' + a.name">
                    <app-icon name="three-dots-vertical" />
                  </button>
                  <div ngbDropdownMenu class="shadow app-menu" [attr.aria-label]="'Ações da conta ' + a.name">
                    <button ngbDropdownItem type="button" (click)="editAccount(a)"><app-icon name="pencil" />Editar</button>
                    <button ngbDropdownItem type="button" (click)="rotate(a)"><app-icon name="key" />Trocar credenciais</button>
                    <button ngbDropdownItem type="button" (click)="showUrls(a)"><app-icon name="link-45deg" />Endereços para o console</button>
                    @if (verifiable(a)) {
                      <button ngbDropdownItem type="button" (click)="test(a)" [disabled]="testing() === a.id"><app-icon name="plug" />Testar conexão</button>
                    }
                    <button ngbDropdownItem type="button" (click)="toggle(a)" [disabled]="!a.active && !a.dialable">
                      <app-icon [name]="a.active ? 'pause-circle' : 'play-circle'" />{{ a.active ? 'Desativar' : 'Ativar' }}
                    </button>
                  </div>
                </div>
              }
            </ng-template>
          </app-data-table>
        </ng-template>
      </li>
      <li [ngbNavItem]="'routes'">
        <button ngbNavLink type="button" class="d-inline-flex align-items-center gap-2"><app-icon name="signpost-split" />Roteamento</button>
        <ng-template ngbNavContent>
          <p class="app-muted">
            Cada chamada usa a regra mais específica: campanha, depois carteira, credor e o padrão da assessoria. Conta inativa ou fora do ar
            passa a vez para a contingência e, sem ela, para a regra seguinte. No clique para ligar, vale a conta em que o operador tem ramal.
          </p>
          <app-data-table
            caption="Regras de roteamento"
            [rows]="routeRows()"
            [columns]="routeColumns"
            [loading]="routes.isLoading()"
            [failed]="routes.status() === 'error'"
            (retry)="routes.reload()"
            emptyIcon="signpost-split"
            emptyMessage="Nenhuma regra de roteamento."
            emptyHint="A primeira conta ativa vira o padrão da assessoria; crie regras para credores, carteiras ou campanhas."
          >
            <ng-template appCell="scope" [appCellOf]="routeRows()" let-r>
              <strong>{{ scopeLabels[r.scopeType] }}</strong>
              <div class="app-muted">{{ scopeName(r) }}</div>
            </ng-template>
            <ng-template appCell="primary" [appCellOf]="routeRows()" let-r>{{ accountName(r.primaryAccountId) }}</ng-template>
            <ng-template appCell="fallback" [appCellOf]="routeRows()" let-r>{{ r.fallbackAccountId ? accountName(r.fallbackAccountId) : '—' }}</ng-template>
            <ng-template appCell="actions" [appCellOf]="routeRows()" let-r>
              @if (canManage()) {
                <button class="btn btn-link" type="button" (click)="editRoute(r)" [attr.aria-label]="'Editar regra ' + scopeLabels[r.scopeType]"><app-icon name="pencil" /></button>
                <button class="btn btn-link" type="button" (click)="removeRoute(r)" [attr.aria-label]="'Excluir regra ' + scopeLabels[r.scopeType]"><app-icon name="trash3" /></button>
              }
            </ng-template>
          </app-data-table>
        </ng-template>
      </li>
      <li [ngbNavItem]="'comparison'">
        <button ngbNavLink type="button" class="d-inline-flex align-items-center gap-2"><app-icon name="bar-chart" />Comparação</button>
        <ng-template ngbNavContent>
          <p class="app-muted">Últimos 30 dias, chamadas de saída. Custo estimado = minutos do atendimento ao fim × tarifa informada na conta (a fatura do fornecedor é a referência).</p>
          <app-data-table
            caption="Comparação entre plataformas"
            [rows]="comparisonRows()"
            [columns]="comparisonColumns"
            [loading]="comparison.isLoading()"
            [failed]="comparison.status() === 'error'"
            (retry)="comparison.reload()"
            emptyIcon="bar-chart"
            emptyMessage="Sem chamadas no período."
          >
            <ng-template appCell="account" [appCellOf]="comparisonRows()" let-c>
              <strong>{{ c.name }}</strong>
              <div class="app-muted">{{ providerLabels[c.provider] }}{{ c.active ? '' : ' · inativa' }}</div>
            </ng-template>
            <ng-template appCell="volume" [appCellOf]="comparisonRows()" let-c>
              <span class="app-numeric">{{ c.dialed | number }}</span>
              <div class="app-muted small">{{ c.fallbackIn ? c.fallbackIn + ' pela contingência' : '' }}</div>
            </ng-template>
            <ng-template appCell="asr" [appCellOf]="comparisonRows()" let-c><span class="app-numeric">{{ c.asr === null ? '—' : (c.asr | percent: '1.0-1') }}</span></ng-template>
            <ng-template appCell="failures" [appCellOf]="comparisonRows()" let-c>
              <span class="app-numeric">{{ c.failureRate === null ? '—' : (c.failureRate | percent: '1.0-1') }}</span>
              <div class="app-muted small">{{ c.rejected }} recusadas · {{ c.failed }} falhas</div>
            </ng-template>
            <ng-template appCell="times" [appCellOf]="comparisonRows()" let-c>
              <span class="app-numeric">{{ seconds(c.averageRingSeconds) }} / {{ seconds(c.averageTalkSeconds) }}</span>
            </ng-template>
            <ng-template appCell="cost" [appCellOf]="comparisonRows()" let-c>
              <span class="app-numeric">{{ c.estimatedCost === null ? '—' : 'R$ ' + (c.estimatedCost | number: '1.2-2') }}</span>
              <div class="app-muted small">{{ c.billableMinutes | number: '1.0-0' }} min</div>
            </ng-template>
          </app-data-table>
        </ng-template>
      </li>
    </ul>
    <div [ngbNavOutlet]="nav"></div>
  `,
})
export class TelephonyAccounts {
  private readonly api = inject(TelephonyAccountsApi);
  private readonly routingApi = inject(TelephonyRoutingApi);
  private readonly campaignsApi = inject(DialerCampaignsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly providerLabels = PROVIDER_LABELS;
  protected readonly readinessLabels = READINESS_LABELS;
  protected readonly scopeLabels = ROUTE_SCOPE_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.TELEPHONY_MANAGE));
  protected readonly tab = signal<Tab>('accounts');
  protected readonly testing = signal<string | null>(null);

  protected readonly accounts = rxResource({ stream: () => this.api.list() });
  protected readonly accountRows = valueOr(this.accounts, []);
  protected readonly providers = rxResource({ stream: () => this.api.providers() });
  protected readonly routes = rxResource({ stream: () => this.routingApi.list() });
  private readonly routeValues = valueOr(this.routes, []);
  protected readonly routeRows = computed(() =>
    [...this.routeValues()].sort((a, b) => ROUTE_SCOPE_ORDER.indexOf(a.scopeType) - ROUTE_SCOPE_ORDER.indexOf(b.scopeType)),
  );
  protected readonly comparison = rxResource({ params: () => this.tab() === 'comparison' || undefined, stream: () => this.api.comparison(null, null) });
  protected readonly comparisonRows = valueOr(this.comparison, [] as AccountComparison[]);
  private readonly campaignList = rxResource({ stream: () => this.campaignsApi.list() });
  private readonly campaigns = valueOr(this.campaignList, []);

  protected readonly creditors = nameMap(inject(CreditorNames), () => [
    ...this.accountRows().map((a) => a.creditorId),
    ...this.routeRows().filter((r) => r.scopeType === 'CREDITOR').map((r) => r.scopeId),
  ]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.routeRows().filter((r) => r.scopeType === 'PORTFOLIO').map((r) => r.scopeId));

  protected readonly accountColumns: TableColumn[] = [
    { key: 'name', header: 'Conta' },
    { key: 'provider', header: 'Plataforma' },
    { key: 'settings', header: 'Gravação e tarifa' },
    { key: 'credentials', header: 'Credenciais' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  protected readonly routeColumns: TableColumn[] = [
    { key: 'scope', header: 'Escopo' },
    { key: 'primary', header: 'Conta principal' },
    { key: 'fallback', header: 'Contingência' },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  protected readonly comparisonColumns: TableColumn[] = [
    { key: 'account', header: 'Conta' },
    { key: 'volume', header: 'Discadas', align: 'end' },
    { key: 'asr', header: 'Atendimento (ASR)', align: 'end' },
    { key: 'failures', header: 'Falhas', align: 'end' },
    { key: 'times', header: 'Toque / conversa (média)', align: 'end' },
    { key: 'cost', header: 'Custo estimado', align: 'end' },
  ];

  protected keyText(a: TelephonyAccountView): string {
    return a.credentialKeys.map((k) => TELEPHONY_CREDENTIAL_LABELS[k] ?? k).join(', ');
  }

  protected usage(accountId: string): number {
    return this.routeValues().filter((r) => r.primaryAccountId === accountId || r.fallbackAccountId === accountId).length;
  }

  protected accountName(id: string): string {
    const a = this.accountRows().find((x) => x.id === id);
    return a ? `${a.name} (${PROVIDER_LABELS[a.provider]})${a.active ? '' : ' — inativa'}` : 'conta';
  }

  protected scopeName(r: RouteView): string {
    switch (r.scopeType) {
      case 'CREDITOR':
        return this.creditors.name(r.scopeId);
      case 'PORTFOLIO':
        return this.portfolios.name(r.scopeId);
      case 'CAMPAIGN':
        return this.campaigns().find((c) => c.id === r.scopeId)?.name ?? 'campanha';
      default:
        return 'Todas as chamadas sem regra mais específica';
    }
  }

  protected seconds(value: number | null): string {
    return value === null ? '—' : `${Math.round(value)} s`;
  }

  protected editAccount(account: TelephonyAccountView | null): void {
    this.dialog
      .open<TelephonyAccountDialog, TelephonyAccountDialogData, TelephonyAccountView>(TelephonyAccountDialog, {
        data: { account, providers: this.providers.value() ?? [] },
      })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success(account ? 'Conta atualizada.' : saved.active ? 'Conta cadastrada e ativa.' : 'Conta cadastrada inativa: aguarda a homologação da plataforma.');
          this.accounts.reload();
          this.routes.reload();
        }
      });
  }

  protected rotate(account: TelephonyAccountView): void {
    const provider = this.providers.value()?.find((p) => p.provider === account.provider);
    const keys = provider?.requiredCredentials ?? account.credentialKeys;
    const optionalKeys = provider?.optionalCredentials ?? [];
    this.dialog
      .open<TelephonyCredentialsDialog, TelephonyCredentialsDialogData, TelephonyAccountView>(TelephonyCredentialsDialog, {
        data: { account, keys, optionalKeys },
      })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Credenciais trocadas.');
          this.accounts.reload();
        }
      });
  }

  protected toggle(a: TelephonyAccountView): void {
    const rules = this.usage(a.id);
    this.confirm
      .confirm({
        title: a.active ? 'Desativar conta' : 'Ativar conta',
        message: a.active
          ? `As regras que usam esta conta (${rules}) passam para a contingência ou para a regra seguinte. Chamadas em andamento seguem; os eventos desta conta deixam de ser aceitos.`
          : 'A conta volta a discar pelas regras que a usam e a receber eventos.',
        confirmLabel: a.active ? 'Desativar' : 'Ativar',
        destructive: a.active,
        details: [
          { label: 'Conta', value: a.name },
          { label: 'Plataforma', value: PROVIDER_LABELS[a.provider] },
        ],
      })
      .subscribe(() =>
        (a.active ? this.api.deactivate(a.id) : this.api.activate(a.id)).subscribe({
          next: () => {
            this.notifications.success(a.active ? 'Conta desativada.' : 'Conta ativada.');
            this.accounts.reload();
            this.routes.reload();
          },
          error: (err: unknown) => this.notifications.error(this.errorText(err)),
        }),
      );
  }

  protected verifiable(a: TelephonyAccountView): boolean {
    return !!this.providers.value()?.find((p) => p.provider === a.provider)?.verifiable;
  }

  protected showUrls(account: TelephonyAccountView): void {
    this.dialog.open<WebhookUrlsDialog, WebhookUrlsDialogData, void>(WebhookUrlsDialog, { data: { account } });
  }

  /** Teste de conexão: leitura da própria conta no provedor; nunca liga para ninguém. */
  protected test(a: TelephonyAccountView): void {
    this.testing.set(a.id);
    this.api
      .testConnection(a.id)
      .pipe(finalize(() => this.testing.set(null)))
      .subscribe({
        next: (r) => {
          const text = CONNECTION_TEST_LABELS[r.code] ?? `A plataforma recusou as credenciais (${r.code}).`;
          if (r.ok) {
            this.notifications.success(text);
          } else {
            this.notifications.error(text);
          }
        },
        error: (err: unknown) => this.notifications.error(this.errorText(err)),
      });
  }

  protected editRoute(route: RouteView | null): void {
    this.dialog
      .open<RouteDialog, RouteDialogData, RouteView>(RouteDialog, { data: { route, accounts: this.accountRows(), campaigns: this.campaigns() } })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Regra de roteamento salva.');
          this.routes.reload();
        }
      });
  }

  protected removeRoute(r: RouteView): void {
    this.confirm
      .confirm({
        title: 'Excluir regra de roteamento',
        message: 'As chamadas deste escopo passam a seguir a regra seguinte (até o padrão da assessoria).',
        confirmLabel: 'Excluir regra',
        destructive: true,
        details: [
          { label: 'Escopo', value: `${ROUTE_SCOPE_LABELS[r.scopeType]} — ${this.scopeName(r)}` },
          { label: 'Conta principal', value: this.accountName(r.primaryAccountId) },
        ],
      })
      .subscribe(() =>
        this.routingApi.delete(r.id).subscribe({
          next: () => {
            this.notifications.success('Regra excluída.');
            this.routes.reload();
          },
          error: (err: unknown) => this.notifications.error(this.errorText(err)),
        }),
      );
  }

  private errorText(err: unknown): string {
    const e = toApiError(err);
    return (e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e);
  }
}
