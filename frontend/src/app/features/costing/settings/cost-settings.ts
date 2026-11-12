import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { TabsMore } from '../../../shared/components/tabs-more/tabs-more';
import { NameSource, nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { urlTab } from '../../../shared/data/url-state';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, USER_NAMES_PERMISSIONS, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { CostSettingsApi } from '../data/costing.api';
import { ALLOCATION_LABELS, CHANNEL_ITEM_LABELS, CHANNEL_LABELS, CHANNEL_UNITS, CostTab, MARGIN_ROUTE } from '../data/costing.labels';
import { ChannelTariff, CostBand, FxRate, Overhead, UserCostBand } from '../data/costing.models';
import {
  InvoiceRow,
  assignableBandNames,
  differenceText,
  invoiceRows,
  isCurrent,
  isOpen,
  lastClosedMonthStart,
  monthOptions,
  normalizeMonth,
  sortBands,
  usageText,
  validityText,
} from '../data/cost-settings.rules';
import { monthLabel } from '../data/margin.rules';
import {
  AssignBandDialog,
  AssignBandDialogData,
  AssignBandResult,
  BandDialog,
  BandDialogData,
  EndValidityDialog,
  EndValidityDialogData,
  FxRateDialog,
  FxRateDialogData,
  InvoiceDialog,
  InvoiceDialogData,
  OverheadDialog,
  OverheadDialogData,
  TariffDialog,
  TariffDialogData,
} from './cost-dialogs';

const TAB_LABELS: Record<CostTab, string> = {
  faixas: 'Faixas de custo de pessoas',
  tarifas: 'Tarifas de canal',
  cambio: 'Câmbio da IA',
  fixos: 'Custos fixos',
  faturas: 'Faturas',
};

/**
 * Configuração "Custos operacionais" (margem-por-carteira.md §3 e §6.3): faixas de custo de pessoas e quem está em
 * cada faixa (`COST_RATE_MANAGE`), tarifas de canal, câmbio da IA e custos fixos com a regra de rateio (escrita com
 * `COST_MANAGE`). Abas sem permissão ficam ocultas. Vigências são encerradas, nunca apagadas (o histórico vale para
 * as apurações já feitas); o custo fixo pode ser excluído em qualquer mês, e o servidor refaz o rateio do mês na hora
 * (decisão 1247), com motivo opcional na auditoria. A aba Faturas (fase 2, §13) recebe a fatura do mês de cada canal ao
 * lado da estimativa; ela existe só para quem vê todos os credores (os valores da fatura são da assessoria).
 *
 * Escopo: os cadastros valem para a assessoria inteira, e o servidor exige todos os credores para alterá-los. Quem
 * tem escopo restrito vê as abas pela permissão, mas sem as ações de escrita — exceto o custo fixo direto a um credor
 * do próprio escopo (criar e excluir).
 *
 * LGPD: o valor-hora existe só na lista de faixas; a lista de pessoas mostra o nome da faixa de cada uma, nunca o
 * valor (decisão do responsável, Q1 — faixa por cargo, sem valor individual).
 */
@Component({
  selector: 'app-cost-settings',
  imports: [NgbNavModule, RouterLink, TabsMore, DataTable, CellDef, Icon, PageHeader, Amount, AppDatePipe],
  template: `
    <app-page-header title="Custos operacionais" subtitle="Faixas de custo de pessoas, tarifas de canal, câmbio da IA, custos fixos e faturas de canal usados no painel de margem.">
      @switch (tab.value()) {
        @case ('faixas') {
          @if (canRates()) {
            <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="newBand(null)" data-tour="costs-new">
              <app-icon name="plus-lg" />
              Nova faixa
            </button>
          }
        }
        @case ('tarifas') {
          @if (canManage()) {
            <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="newTariff(null)" data-tour="costs-new">
              <app-icon name="plus-lg" />
              Nova tarifa
            </button>
          }
        }
        @case ('cambio') {
          @if (canManage()) {
            <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="fxRate(null)" data-tour="costs-new">
              <app-icon name="plus-lg" />
              Informar câmbio
            </button>
          }
        }
        @case ('fixos') {
          @if (canOverheads()) {
            <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="newOverhead()" data-tour="costs-new">
              <app-icon name="plus-lg" />
              Novo custo fixo
            </button>
          }
        }
        @case ('faturas') {
          @if (canManage()) {
            <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="invoice(null)" data-tour="costs-new">
              <app-icon name="plus-lg" />
              Informar fatura
            </button>
          }
        }
      }
    </app-page-header>

    <ul ngbNav #nav="ngbNav" class="nav-underline app-tabs" [activeId]="tab.value()" (activeIdChange)="tab.set($event)" data-tour="costs-tabs">
      @if (tabs.includes('faixas')) {
        <li [ngbNavItem]="'faixas'">
          <button ngbNavLink type="button">{{ labels.faixas }}</button>
          <ng-template ngbNavContent>
            <p class="app-muted">
              Custo-empresa por hora de cada cargo (salário, encargos e benefícios ÷ horas contratadas). Uma nova vigência com o mesmo nome vale a
              partir da data escolhida; as anteriores ficam no histórico.
            </p>
            <app-data-table
              caption="Faixas de custo"
              [rows]="bandRows()"
              [columns]="bandColumns"
              [loading]="bands.isLoading()"
              [failed]="bands.status() === 'error'"
              (retry)="bands.reload()"
              emptyIcon="receipt-cutoff"
              emptyMessage="Nenhuma faixa de custo cadastrada."
              emptyHint="Sem faixa, as horas de trabalho entram com custo zero e a margem aparece maior do que é."
            >
              <ng-template appCell="name" [appCellOf]="bandRows()" let-b><strong>{{ b.name }}</strong></ng-template>
              <ng-template appCell="hourlyCost" [appCellOf]="bandRows()" let-b>
                @if (b.hourlyCost !== null) {
                  <app-amount [value]="b.hourlyCost" /> por hora
                } @else {
                  —
                }
              </ng-template>
              <ng-template appCell="validity" [appCellOf]="bandRows()" let-b>
                {{ validity(b) }}
                <div class="app-muted small">{{ situation(b) }}</div>
              </ng-template>
              <ng-template appCell="actions" [appCellOf]="bandRows()" let-b>
                @if (canRates() && open(b)) {
                  <button class="btn btn-link" type="button" (click)="newBand(b)" [attr.aria-label]="'Nova vigência da faixa ' + b.name"><app-icon name="calendar-plus" /></button>
                  <button class="btn btn-link" type="button" (click)="endBand(b)" [attr.aria-label]="'Encerrar a faixa ' + b.name"><app-icon name="calendar-x" /></button>
                }
              </ng-template>
            </app-data-table>

            <h2 class="app-section-title mt-4 d-flex align-items-center justify-content-between gap-2 flex-wrap" data-tour="costs-people">
              <span>Pessoas e faixas</span>
              @if (canPickUsers()) {
                <button class="btn btn-outline-primary btn-sm" type="button" (click)="assign(null)" [disabled]="!bandNames().length">
                  <app-icon name="person-plus" />
                  Atribuir faixa
                </button>
              }
            </h2>
            <p class="app-muted">Cada pessoa aparece só com o nome da faixa: o valor-hora fica na lista acima e nunca é mostrado por pessoa.</p>
            <app-data-table
              caption="Faixa de custo de cada pessoa"
              [rows]="userRows()"
              [columns]="userColumns"
              [loading]="userBands.isLoading()"
              [failed]="userBands.status() === 'error'"
              (retry)="userBands.reload()"
              emptyIcon="people"
              emptyMessage="Nenhuma pessoa com faixa de custo."
              [emptyHint]="canPickUsers() ? 'Use Atribuir faixa para começar.' : 'As pessoas com horas registradas aparecem aqui.'"
            >
              <ng-template appCell="user" [appCellOf]="userRows()" let-u>{{ userName(u) }}</ng-template>
              <ng-template appCell="band" [appCellOf]="userRows()" let-u>
                @if (u.bandName) {
                  {{ u.bandName }}
                } @else {
                  <span class="fw-semibold text-warning-emphasis">Sem faixa: horas com custo zero</span>
                }
              </ng-template>
              <ng-template appCell="since" [appCellOf]="userRows()" let-u>{{ u.validFrom ? (u.validFrom | appDate: 'date') : '—' }}</ng-template>
              <ng-template appCell="actions" [appCellOf]="userRows()" let-u>
                @if (canRates()) {
                  <button class="btn btn-link" type="button" (click)="assign(u)" [disabled]="!bandNames().length" [attr.aria-label]="'Trocar a faixa de ' + userName(u)">
                    <app-icon name="pencil" />
                  </button>
                }
              </ng-template>
            </app-data-table>
          </ng-template>
        </li>
      }
      @if (tabs.includes('tarifas')) {
        <li [ngbNavItem]="'tarifas'">
          <button ngbNavLink type="button">{{ labels.tarifas }}</button>
          <ng-template ngbNavContent>
            <p class="app-muted">
              Quanto cada envio custa, como o provedor cobra: WhatsApp por conversa iniciada pela assessoria (por categoria do template), SMS e
              e-mail por mensagem (o e-mail costuma ser desprezível e pode ficar zerado). A telefonia usa a tarifa por minuto de cada conta em
              Plataformas de telefonia.
            </p>
            <app-data-table
              caption="Tarifas de canal"
              [rows]="tariffRows()"
              [columns]="tariffColumns"
              [loading]="tariffs.isLoading()"
              [failed]="tariffs.status() === 'error'"
              (retry)="tariffs.reload()"
              emptyIcon="envelope"
              emptyMessage="Nenhuma tarifa de canal cadastrada."
              emptyHint="Sem tarifa, os envios entram com custo zero e o painel avisa."
            >
              <ng-template appCell="channel" [appCellOf]="tariffRows()" let-t>
                <strong>{{ channelLabels[t.channel] }}</strong>
                <div class="app-muted small">{{ channelUnits[t.channel] }}</div>
              </ng-template>
              <ng-template appCell="unitCost" [appCellOf]="tariffRows()" let-t><span class="app-numeric">{{ unitCost(t.unitCost) }}</span></ng-template>
              <ng-template appCell="validity" [appCellOf]="tariffRows()" let-t>
                {{ validity(t) }}
                <div class="app-muted small">{{ situation(t) }}</div>
              </ng-template>
              <ng-template appCell="actions" [appCellOf]="tariffRows()" let-t>
                @if (canManage() && open(t)) {
                  <button class="btn btn-link" type="button" (click)="newTariff(t)" [attr.aria-label]="'Nova vigência da tarifa ' + channelLabels[t.channel]"><app-icon name="calendar-plus" /></button>
                  <button class="btn btn-link" type="button" (click)="endTariff(t)" [attr.aria-label]="'Encerrar a tarifa ' + channelLabels[t.channel]"><app-icon name="calendar-x" /></button>
                }
              </ng-template>
            </app-data-table>
          </ng-template>
        </li>
      }
      @if (tabs.includes('cambio')) {
        <li [ngbNavItem]="'cambio'">
          <button ngbNavLink type="button">{{ labels.cambio }}</button>
          <ng-template ngbNavContent>
            <p class="app-muted">O custo de inteligência artificial vem em dólar. Informe o câmbio de cada mês; mês sem câmbio usa o último informado.</p>
            <app-data-table
              caption="Câmbio do dólar por mês"
              [rows]="fxRows()"
              [columns]="fxColumns"
              [loading]="fxRates.isLoading()"
              [failed]="fxRates.status() === 'error'"
              (retry)="fxRates.reload()"
              emptyIcon="globe"
              emptyMessage="Nenhum câmbio informado."
              emptyHint="Sem câmbio, o custo de IA fica zerado e o painel avisa."
            >
              <ng-template appCell="month" [appCellOf]="fxRows()" let-f>{{ month(f.month) }}</ng-template>
              <ng-template appCell="usdBrl" [appCellOf]="fxRows()" let-f><span class="app-numeric">US$ 1 = {{ unitCost(f.usdBrl) }}</span></ng-template>
              <ng-template appCell="actions" [appCellOf]="fxRows()" let-f>
                @if (canManage()) {
                  <button class="btn btn-link" type="button" (click)="fxRate(f)" [attr.aria-label]="'Corrigir o câmbio de ' + month(f.month)"><app-icon name="pencil" /></button>
                }
              </ng-template>
            </app-data-table>
          </ng-template>
        </li>
      }
      @if (tabs.includes('fixos')) {
        <li [ngbNavItem]="'fixos'">
          <button ngbNavLink type="button">{{ labels.fixos }}</button>
          <ng-template ngbNavContent>
            <p class="app-muted">
              Custos que não dependem de um caso (aluguel, licenças, infraestrutura, gestão). Por padrão são rateados pelas horas trabalhadas de cada
              credor no mês e entram só na margem líquida.
            </p>
            <app-data-table
              caption="Custos fixos por mês"
              [rows]="overheadRows()"
              [columns]="overheadColumns"
              [loading]="overheads.isLoading()"
              [failed]="overheads.status() === 'error'"
              (retry)="overheads.reload()"
              emptyIcon="building"
              emptyMessage="Nenhum custo fixo informado."
              emptyHint="Sem custos fixos, a margem líquida considera só o custo direto e a ociosidade."
            >
              <ng-template appCell="month" [appCellOf]="overheadRows()" let-o>{{ month(o.month) }}</ng-template>
              <ng-template appCell="description" [appCellOf]="overheadRows()" let-o>{{ o.description }}</ng-template>
              <ng-template appCell="amount" [appCellOf]="overheadRows()" let-o><app-amount [value]="o.amount" /></ng-template>
              <ng-template appCell="allocation" [appCellOf]="overheadRows()" let-o>
                {{ allocationLabels[o.allocation] }}
                @if (o.allocation === 'CREDITOR') {
                  <div class="app-muted small">{{ creditors.name(o.creditorId) }}</div>
                }
              </ng-template>
              <ng-template appCell="actions" [appCellOf]="overheadRows()" let-o>
                @if (canRemove(o)) {
                  <button class="btn btn-link" type="button" (click)="removeOverhead(o)" [attr.aria-label]="'Excluir o custo fixo ' + o.description"><app-icon name="trash3" /></button>
                }
              </ng-template>
            </app-data-table>
          </ng-template>
        </li>
      }
      @if (tabs.includes('faturas')) {
        <li [ngbNavItem]="'faturas'">
          <button ngbNavLink type="button">{{ labels.faturas }}</button>
          <ng-template ngbNavContent>
            <p class="app-muted">
              Valor real cobrado por cada canal no mês, em reais (a fatura de IA em dólar entra convertida pela assessoria). A diferença para a
              estimativa é rateada pelos credores e carteiras na proporção do custo estimado de cada um, e o painel de margem passa a mostrar a
              base "Conciliado com a fatura". A estimativa continua guardada.
            </p>
            <div class="d-flex flex-wrap align-items-end gap-3 mb-3">
              <div>
                <label class="form-label" for="cs-invoice-month">Mês</label>
                <select class="form-select" id="cs-invoice-month" [value]="invoiceMonth.value()" (change)="invoiceMonth.set($any($event.target).value)">
                  @for (m of invoiceMonths; track m.value) {
                    <option [value]="m.value">{{ m.label }}</option>
                  }
                </select>
              </div>
              @if (canSeeMargin()) {
                <a class="mb-2" [routerLink]="marginRoute" [queryParams]="marginQuery()">Ver a margem conciliada do mês</a>
              }
            </div>
            <app-data-table
              caption="Faturas de canal do mês"
              [rows]="invoiceRows()"
              [columns]="invoiceColumns"
              [loading]="invoiceData.isLoading()"
              [failed]="invoiceData.status() === 'error'"
              (retry)="invoiceData.reload()"
              emptyIcon="receipt-cutoff"
              emptyMessage="Nenhum canal neste mês."
              emptyHint="Escolha outro mês."
            >
              <ng-template appCell="item" [appCellOf]="invoiceRows()" let-r>
                <strong>{{ itemLabels[r.item] }}</strong>
                @if (r.invoice?.supplier) {
                  <div class="app-muted small">{{ r.invoice.supplier }}</div>
                }
              </ng-template>
              <ng-template appCell="usage" [appCellOf]="invoiceRows()" let-r>{{ usage(r) }}</ng-template>
              <ng-template appCell="estimated" [appCellOf]="invoiceRows()" let-r><app-amount [value]="r.estimate?.estimated ?? 0" /></ng-template>
              <ng-template appCell="invoiced" [appCellOf]="invoiceRows()" let-r>
                @if (r.invoice) {
                  <app-amount [value]="r.invoice.amount" />
                } @else {
                  <span class="app-muted">Sem fatura</span>
                }
              </ng-template>
              <ng-template appCell="difference" [appCellOf]="invoiceRows()" let-r>
                @if (r.invoice) {
                  <span class="app-numeric">{{ difference(r) }}</span>
                } @else {
                  —
                }
              </ng-template>
              <ng-template appCell="actions" [appCellOf]="invoiceRows()" let-r>
                @if (canManage()) {
                  @if (r.invoice) {
                    <button class="btn btn-link" type="button" (click)="invoice(r)" [attr.aria-label]="'Corrigir a fatura de ' + itemLabels[r.item]"><app-icon name="pencil" /></button>
                    <button class="btn btn-link" type="button" (click)="removeInvoice(r)" [attr.aria-label]="'Excluir a fatura de ' + itemLabels[r.item]"><app-icon name="trash3" /></button>
                  } @else {
                    <button class="btn btn-link" type="button" (click)="invoice(r)" [attr.aria-label]="'Informar a fatura de ' + itemLabels[r.item]"><app-icon name="plus-lg" /></button>
                  }
                }
              </ng-template>
            </app-data-table>
          </ng-template>
        </li>
      }
      <li class="nav-item app-tabs-more-item d-none" role="presentation"><app-tabs-more [nav]="nav" /></li>
    </ul>
    <div [ngbNavOutlet]="nav"></div>
  `,
})
export class CostSettings {
  private readonly api = inject(CostSettingsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  /** Todos os credores: o servidor exige escopo total para alterar os cadastros da assessoria. */
  private readonly allCreditors = computed(() => this.auth.user()?.allCreditors !== false);
  private readonly hasRates = computed(() => this.auth.hasPermission(Permission.COST_RATE_MANAGE));
  private readonly hasManage = computed(() => this.auth.hasPermission(Permission.COST_MANAGE));
  protected readonly canRates = computed(() => this.hasRates() && this.allCreditors());
  protected readonly canManage = computed(() => this.hasManage() && this.allCreditors());
  /** Custo fixo: com escopo restrito, só o direto a um credor do próprio escopo (o servidor confere o credor). */
  protected readonly canOverheads = computed(() => this.hasManage());
  /** Leitura das tarifas, do câmbio e dos fixos: quem gerencia ou quem vê a margem (`GET` com `COST_VIEW`). */
  private readonly canReadCosts = computed(() => this.hasManage() || this.auth.hasPermission(Permission.COST_VIEW));
  /** Seletor de pessoas = cadastro de usuários (`USER_VIEW`); sem ele, troca-se a faixa a partir da lista. */
  protected readonly canPickUsers = computed(() => this.canRates() && this.auth.hasPermission(Permission.USER_VIEW));

  /** Abas que o perfil vê (sem permissão, a aba não existe — nem na URL). */
  protected readonly tabs: readonly CostTab[] = [
    ...(this.hasRates() ? (['faixas'] as const) : []),
    ...(this.canReadCosts() ? (['tarifas', 'cambio', 'fixos'] as const) : []),
    // faturas: valores da assessoria inteira — só com todos os credores, como o servidor exige (§13)
    ...(this.canReadCosts() && this.allCreditors() ? (['faturas'] as const) : []),
  ];
  protected readonly tab = urlTab<CostTab>(this.tabs, this.tabs[0] ?? 'tarifas');
  protected readonly labels = TAB_LABELS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly channelUnits = CHANNEL_UNITS;
  protected readonly allocationLabels = ALLOCATION_LABELS;
  protected readonly itemLabels = CHANNEL_ITEM_LABELS;
  private readonly today = zonedNow().date;
  /** Mês da aba Faturas na URL (`?mes=AAAA-MM-01`); padrão: último mês fechado; só meses já começados. */
  protected readonly invoiceMonths = monthOptions(new Date(), 24, 0);
  protected readonly invoiceMonth = urlTab<string>(
    this.invoiceMonths.map((m) => m.value),
    lastClosedMonthStart(new Date()),
    'mes',
  );
  protected readonly marginRoute = MARGIN_ROUTE;
  protected readonly canSeeMargin = computed(() => this.auth.hasPermission(Permission.COST_VIEW));
  /** Painel no mês escolhido, já na base conciliada. */
  protected readonly marginQuery = computed(() => {
    const [y, m] = this.invoiceMonth.value().split('-').map(Number);
    const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
    const month = this.invoiceMonth.value().slice(0, 7);
    return { de: `${month}-01`, ate: `${month}-${String(last).padStart(2, '0')}`, base: 'conciliado' };
  });

  // ------------------------------------------------------------ dados (carregados na aba aberta)
  private readonly on = (t: CostTab) => () => (this.tab.value() === t && this.tabs.includes(t)) || undefined;
  protected readonly bands = rxResource({ params: this.on('faixas'), stream: () => this.api.bands() });
  protected readonly userBands = rxResource({ params: this.on('faixas'), stream: () => this.api.userBands() });
  protected readonly tariffs = rxResource({ params: this.on('tarifas'), stream: () => this.api.tariffs() });
  protected readonly fxRates = rxResource({ params: this.on('cambio'), stream: () => this.api.fxRates() });
  protected readonly overheads = rxResource({ params: this.on('fixos'), stream: () => this.api.overheads() });
  protected readonly invoiceData = rxResource({
    params: () => (this.on('faturas')() ? this.invoiceMonth.value() : undefined),
    stream: ({ params }) =>
      forkJoin({ estimates: this.api.invoiceEstimates(params, params), invoices: this.api.invoices(params, params) }),
  });

  private readonly bandValues = valueOr(this.bands, [] as CostBand[]);
  protected readonly bandRows = computed(() => sortBands(this.bandValues()));
  protected readonly bandNames = computed(() => assignableBandNames(this.bandValues(), this.today));
  private readonly userValues = valueOr(this.userBands, [] as UserCostBand[]);
  private readonly tariffValues = valueOr(this.tariffs, [] as ChannelTariff[]);
  private readonly fxValues = valueOr(this.fxRates, [] as FxRate[]);
  private readonly overheadValues = valueOr(this.overheads, [] as Overhead[]);
  protected readonly invoiceRows = computed(() => {
    const value = this.invoiceData.value();
    return value ? invoiceRows(value.estimates, value.invoices) : [];
  });
  protected readonly tariffRows = computed(() =>
    [...this.tariffValues()].sort((a, b) => a.channel.localeCompare(b.channel) || b.validFrom.localeCompare(a.validFrom)),
  );
  protected readonly fxRows = computed(() => [...this.fxValues()].sort((a, b) => b.month.localeCompare(a.month)));
  protected readonly overheadRows = computed(() =>
    [...this.overheadValues()].sort((a, b) => b.month.localeCompare(a.month) || a.description.localeCompare(b.description, 'pt-BR')),
  );

  // nomes: das pessoas pelo serviço de nomes de exibição (só id e nome), dos credores pelo cadastro
  private readonly noNames: NameSource = { resolve: () => of([]) };
  private readonly users = nameMap(
    this.auth.hasAnyPermission(USER_NAMES_PERMISSIONS) ? inject(UserNames) : this.noNames,
    () => this.userValues().filter((u) => !u.userName).map((u) => u.userId),
    { batch: USER_NAMES_BATCH },
  );
  protected readonly creditors = nameMap(inject(CreditorNames), () =>
    this.auth.hasPermission(Permission.CREDITOR_VIEW) ? this.overheadRows().map((o) => o.creditorId) : [],
  );
  /** Sem faixa primeiro (precisam de atenção), depois por nome. */
  protected readonly userRows = computed(() =>
    [...this.userValues()].sort((a, b) => Number(!!a.bandName) - Number(!!b.bandName) || this.userName(a).localeCompare(this.userName(b), 'pt-BR')),
  );

  protected readonly bandColumns: TableColumn[] = [
    { key: 'name', header: 'Faixa' },
    { key: 'hourlyCost', header: 'Custo por hora', align: 'end' },
    { key: 'validity', header: 'Vigência' },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  protected readonly userColumns: TableColumn[] = [
    { key: 'user', header: 'Pessoa' },
    { key: 'band', header: 'Faixa vigente' },
    { key: 'since', header: 'Desde' },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  protected readonly tariffColumns: TableColumn[] = [
    { key: 'channel', header: 'Canal' },
    { key: 'unitCost', header: 'Tarifa', align: 'end' },
    { key: 'validity', header: 'Vigência' },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  protected readonly fxColumns: TableColumn[] = [
    { key: 'month', header: 'Mês' },
    { key: 'usdBrl', header: 'Câmbio', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  protected readonly invoiceColumns: TableColumn[] = [
    { key: 'item', header: 'Canal' },
    { key: 'usage', header: 'Uso no mês' },
    { key: 'estimated', header: 'Estimativa', align: 'end', info: 'analytics.margin.estimated' },
    { key: 'invoiced', header: 'Fatura', align: 'end' },
    { key: 'difference', header: 'Diferença', align: 'end', info: 'analytics.margin.invoice-adjustment' },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  protected readonly overheadColumns: TableColumn[] = [
    { key: 'month', header: 'Mês' },
    { key: 'description', header: 'Descrição' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'allocation', header: 'Rateio' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected userName(u: UserCostBand): string {
    return u.userName || this.users.name(u.userId) || 'Pessoa';
  }

  protected validity(item: { validFrom: string; validTo: string | null }): string {
    return validityText(item);
  }

  protected situation(item: { validFrom: string; validTo: string | null }): string {
    if (isCurrent(item, this.today)) {
      return 'Em vigor';
    }
    return item.validFrom > this.today ? 'Programada' : 'Encerrada';
  }

  protected open(item: { validFrom: string; validTo: string | null }): boolean {
    return isOpen(item, this.today);
  }

  protected month(value: string): string {
    return monthLabel(normalizeMonth(value));
  }

  /** Tarifa e câmbio com 4 casas (frações de centavo). */
  protected unitCost(value: number | string): string {
    const n = typeof value === 'number' ? value : Number(value);
    return Number.isFinite(n) ? `R$ ${n.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}` : '—';
  }

  // ------------------------------------------------------------ ações
  protected newBand(band: CostBand | null): void {
    this.dialog
      .open<BandDialog, BandDialogData, CostBand>(BandDialog, { data: { band } })
      .afterClosed()
      .subscribe((saved) => this.done(saved, band ? 'Nova vigência da faixa registrada.' : 'Faixa de custo cadastrada.', () => this.bands.reload()));
  }

  protected endBand(band: CostBand): void {
    this.end({
      title: 'Encerrar faixa de custo',
      subject: band.name,
      effect: 'depois do último dia, quem estiver nesta faixa passa a ter as horas com custo zero até receber outra faixa.',
      minDate: band.validFrom,
      end: (validTo) => this.api.endBand(band.id, validTo),
    }, 'Faixa encerrada.', () => this.bands.reload());
  }

  protected assign(user: UserCostBand | null): void {
    this.dialog
      .open<AssignBandDialog, AssignBandDialogData, AssignBandResult>(AssignBandDialog, {
        data: { userId: user?.userId ?? null, userName: user ? this.userName(user) : null, currentBand: user?.bandName ?? null, bandNames: this.bandNames() },
      })
      .afterClosed()
      .subscribe((saved) => this.done(saved, 'Faixa da pessoa atualizada.', () => this.userBands.reload()));
  }

  protected newTariff(tariff: ChannelTariff | null): void {
    this.dialog
      .open<TariffDialog, TariffDialogData, unknown>(TariffDialog, { data: { channel: tariff?.channel ?? null } })
      .afterClosed()
      .subscribe((saved) => this.done(saved, tariff ? 'Nova vigência da tarifa registrada.' : 'Tarifa cadastrada.', () => this.tariffs.reload()));
  }

  protected endTariff(tariff: ChannelTariff): void {
    this.end({
      title: 'Encerrar tarifa de canal',
      subject: CHANNEL_LABELS[tariff.channel],
      effect: 'depois do último dia, os envios deste canal entram com custo zero até uma nova tarifa.',
      minDate: tariff.validFrom,
      end: (validTo) => this.api.endTariff(tariff.id, validTo),
    }, 'Tarifa encerrada.', () => this.tariffs.reload());
  }

  protected fxRate(rate: FxRate | null): void {
    this.dialog
      .open<FxRateDialog, FxRateDialogData, unknown>(FxRateDialog, { data: { month: rate?.month ?? null } })
      .afterClosed()
      .subscribe((saved) => this.done(saved, 'Câmbio do mês salvo.', () => this.fxRates.reload()));
  }

  protected canRemove(o: Overhead): boolean {
    return this.canManage() || (this.hasManage() && o.allocation === 'CREDITOR');
  }

  protected newOverhead(): void {
    this.dialog
      .open<OverheadDialog, OverheadDialogData, unknown>(OverheadDialog, { data: { creditorOnly: !this.allCreditors() } })
      .afterClosed()
      .subscribe((saved) => this.done(saved, 'Custo fixo cadastrado.', () => this.overheads.reload()));
  }

  protected removeOverhead(o: Overhead): void {
    this.confirm
      .confirm({
        title: 'Excluir custo fixo',
        message:
          'O custo sai do mês e o rateio do mês é refeito na hora, mesmo que o mês já tenha sido apurado: a margem líquida dos credores do rateio muda. A exclusão fica registrada na auditoria.',
        confirmLabel: 'Excluir',
        destructive: true,
        reason: 'optional',
        reasonLabel: 'Motivo (opcional)',
        details: [
          { label: 'Mês', value: this.month(o.month) },
          { label: 'Descrição', value: o.description },
        ],
      })
      .subscribe((reason) =>
        this.api.deleteOverhead(o.id, reason).subscribe({
          next: () => this.done(true, 'Custo fixo excluído.', () => this.overheads.reload()),
          error: (err: unknown) => this.notifications.error(userMessage(toApiError(err))),
        }),
      );
  }

  // ------------------------------------------------------------ faturas de canal (§13)
  protected usage(row: InvoiceRow): string {
    return usageText(row.estimate);
  }

  protected difference(row: InvoiceRow): string {
    return row.invoice ? differenceText(row.invoice.amount, row.estimate?.estimated ?? 0) : '—';
  }

  /** Informar (linha sem fatura ou botão do cabeçalho) ou corrigir a fatura do mês escolhido. */
  protected invoice(row: InvoiceRow | null): void {
    this.dialog
      .open<InvoiceDialog, InvoiceDialogData, unknown>(InvoiceDialog, {
        data: { month: this.invoiceMonth.value(), item: row?.item ?? null, invoice: row?.invoice ?? null },
      })
      .afterClosed()
      .subscribe((saved) =>
        this.done(saved, 'Fatura salva. O ajuste do mês foi refeito e já aparece no painel de margem.', () => this.invoiceData.reload()),
      );
  }

  protected removeInvoice(row: InvoiceRow): void {
    const invoice = row.invoice;
    if (!invoice) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Excluir fatura',
        message:
          'O ajuste deste canal no mês é desfeito na hora e o painel volta a usar a estimativa para ele. A exclusão fica registrada na auditoria.',
        confirmLabel: 'Excluir',
        destructive: true,
        reason: 'optional',
        reasonLabel: 'Motivo (opcional)',
        details: [
          { label: 'Mês', value: this.month(invoice.month) },
          { label: 'Canal', value: CHANNEL_ITEM_LABELS[invoice.item] },
        ],
      })
      .subscribe((reason) =>
        this.api.deleteInvoice(invoice.id, reason).subscribe({
          next: () => this.done(true, 'Fatura excluída.', () => this.invoiceData.reload()),
          error: (err: unknown) => this.notifications.error(userMessage(toApiError(err))),
        }),
      );
  }

  private end(data: EndValidityDialogData, message: string, reload: () => void): void {
    this.dialog
      .open<EndValidityDialog, EndValidityDialogData, unknown>(EndValidityDialog, { data })
      .afterClosed()
      .subscribe((saved) => this.done(saved, message, reload));
  }

  private done(saved: unknown, message: string, reload: () => void): void {
    if (saved) {
      this.notifications.success(message);
      reload();
    }
  }
}
