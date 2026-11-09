import { Component, computed, effect, inject, signal } from '@angular/core';
import { syncFormWithUrl, urlParam, urlTab } from '../../../../shared/data/url-state';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, map } from 'rxjs';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { RouterLink } from '@angular/router';
import { NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { NotificationService } from '../../../../core/notification/notification.service';
import { Amount } from '../../../../shared/components/amount/amount';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { nameMap } from '../../../../shared/data/name-map';
import { valueOr } from '../../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { CreditorLookup, CreditorNames } from '../../../organization/data/organization.lookups';
import { TitleAmountApi } from '../../data/debt.api';
import { AMOUNT_REASON_LABELS } from '../../data/debt.labels';
import { canConfirmThreshold } from './title-amount.rules';
import { TabsMore } from '../../../../shared/components/tabs-more/tabs-more';

/**
 * Correções de valor de título: pedidos aguardando a aprovação de outra pessoa (a fila não mostra os próprios), cargas
 * do credor divergentes do valor corrigido e o limite da assessoria para aplicar redução sem segunda pessoa (aumento do
 * limite só vale depois da confirmação de outra pessoa).
 */
@Component({
  selector: 'app-title-amount-page',
  imports: [TabsMore, ReactiveFormsModule, RouterLink, NgbNavModule, Amount, PageHeader, AppDatePipe, CellDef, DataTable, EntityPicker, ...FORM_FIELD],
  template: `
    <app-page-header title="Correções de valor" subtitle="Aprovação das correções manuais e divergências com a carga do credor." />
    <ul ngbNav #tabs="ngbNav" [activeId]="tab.value()" (activeIdChange)="tab.set($event)" class="nav-underline app-tabs" data-tour="title-amount-tabs">
      @if (canApprove()) {
        <li ngbNavItem="aprovacoes">
          <button ngbNavLink type="button">Para aprovar ({{ pendingRows().length }})</button>
          <ng-template ngbNavContent>
            <form class="app-filters" [formGroup]="filters" role="search" aria-label="Filtrar correções para aprovar">
              <div class="app-filters__wide">
                <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
              </div>
            </form>
            <app-data-table
              caption="Correções aguardando a sua aprovação"
              [rows]="pendingRows()"
              [columns]="pendingColumns"
              [loading]="pending.isLoading()"
              [failed]="pending.status() === 'error'"
              (retry)="pending.reload()"
              emptyIcon="check2-circle"
              emptyMessage="Nenhuma correção aguardando a sua aprovação."
              emptyHint="Pedidos acima do limite ou com aumento de componente aparecem aqui. Os seus não aparecem: quem pede não decide."
            >
              <ng-template appCell="requestedAt" [appCellOf]="pendingRows()" let-a>
                <a [routerLink]="['/titulos/correcoes-de-valor', a.id]" [queryParams]="linkParams()">{{ a.requestedAt | appDate }}</a>
                <div class="app-muted">por {{ a.requestedByName ?? 'usuário' }}</div>
              </ng-template>
              <ng-template appCell="title" [appCellOf]="pendingRows()" let-a>
                Título {{ a.titleLabel }}
                <div class="app-muted">{{ creditors.name(a.creditorId) }}</div>
              </ng-template>
              <ng-template appCell="reason" [appCellOf]="pendingRows()" let-a>{{ reasons[a.reason] }}</ng-template>
              <ng-template appCell="balance" [appCellOf]="pendingRows()" let-a>
                <app-amount [value]="a.previousOpenBalance" /> → <app-amount [value]="a.newOpenBalance" />
              </ng-template>
              <ng-template appCell="delta" [appCellOf]="pendingRows()" let-a><app-amount [value]="a.delta" /></ng-template>
              <ng-template appCell="actions" [appCellOf]="pendingRows()" let-a>
                <a class="btn btn-sm btn-outline-primary" [routerLink]="['/titulos/correcoes-de-valor', a.id]" [queryParams]="linkParams()">Revisar</a>
              </ng-template>
            </app-data-table>
          </ng-template>
        </li>
      }
      @if (canAdjust()) {
        <li ngbNavItem="divergencias">
          <button ngbNavLink type="button">Divergências com a carga ({{ divergenceRows().length }})</button>
          <ng-template ngbNavContent>
            <section class="app-card" aria-label="Divergências com a carga do credor">
              <p class="app-muted">A carga do credor não sobrescreve a correção: abra o título para manter a correção ou aceitar o valor do credor.</p>
              <ul class="list-unstyled ta-rows">
                @for (d of divergenceRows(); track d.id) {
                  <li>
                    <a [routerLink]="['/titulos', d.titleId]">Título {{ d.titleLabel }}</a>
                    <span class="app-muted">{{ creditors.name(d.creditorId) }}</span>
                    <span>corrigido <app-amount [value]="d.currentPrincipal" /> · credor <app-amount [value]="d.reportedPrincipal" /></span>
                    <span class="app-muted">{{ d.occurrences }} carga(s) desde {{ d.detectedAt | appDate: 'date' }}</span>
                  </li>
                } @empty {
                  <li class="app-muted">Nenhuma divergência em aberto.</li>
                }
              </ul>
            </section>
          </ng-template>
        </li>
      }
      <li ngbNavItem="limite">
        <button ngbNavLink type="button">Limite</button>
        <ng-template ngbNavContent>
          <section class="app-card" aria-labelledby="ta-limit" data-tour="title-amount-limit">
            <h2 class="app-card__title" id="ta-limit">Limite para aplicar sem segunda pessoa</h2>
            <p class="app-muted">
              Reduções até este valor (somando os componentes reduzidos, sem nenhum componente aumentar) são aplicadas por quem corrige; acima dele, e quando algum componente
              aumenta, outra pessoa com permissão de aprovação decide, com evidência. O limite vale também por título: as reduções aplicadas sem segunda pessoa no mesmo título
              nos últimos {{ settingsValue()?.cumulativeWindowDays ?? 30 }} dias somam com a nova. Zero: toda correção passa por aprovação. Reduzir o limite vale na hora;
              aumentar só vale depois da confirmação de outra pessoa, dentro do prazo da proposta.
            </p>
            <div class="row g-3 align-items-start">
              <app-field class="col-sm-6 col-lg-4" label="Limite por correção">
                <div class="input-group">
                  <span class="input-group-text" aria-hidden="true">R$</span>
                  <input appInput appMoney [formControl]="threshold" [readonly]="!canManageLimit()" />
                </div>
              </app-field>
              @if (canManageLimit()) {
                <div class="col-sm-6 app-control-offset">
                  <button class="btn btn-primary" type="button" (click)="saveThreshold()" [disabled]="busy() || threshold.pristine || threshold.invalid">Salvar limite</button>
                </div>
              }
            </div>
            @if (settingsValue(); as s) {
              <p class="app-muted">{{ s.customized ? 'Definido pela assessoria em ' + (s.updatedAt | appDate) + '.' : 'Valendo o padrão da plataforma.' }}</p>
              @if (s.proposedThreshold !== null) {
                <div class="alert alert-warning mb-0 d-flex flex-wrap align-items-center gap-2" role="status" data-tour="title-amount-proposal">
                  <span class="me-auto">
                    Aumento para <app-amount [value]="s.proposedThreshold" /> proposto por {{ s.proposedByName ?? 'usuário' }} em {{ s.proposedAt | appDate }}.
                    @if (s.proposalExpired) {
                      Venceu em {{ s.proposalExpiresAt | appDate }} sem confirmação e não pode mais ser confirmado; o limite atual continua valendo. Para aumentar, proponha de novo.
                    } @else {
                      {{ ownProposal() ? 'Aguardando a confirmação de outra pessoa com permissão de aprovação' : 'O limite atual vale até a confirmação' }}, até
                      {{ s.proposalExpiresAt | appDate }}; depois disso a proposta vence.
                    }
                  </span>
                  @if (canConfirm()) {
                    <button class="btn btn-warning" type="button" (click)="confirmThreshold()" [disabled]="busy()">Confirmar aumento</button>
                  }
                </div>
              }
            }
          </section>
        </ng-template>
      </li>
      <!-- abas que não cabem vão para "Mais N ▾" (ux.md §12) -->
      <li [appTabsMore]="tabs"></li>
    </ul>
    <div [ngbNavOutlet]="tabs"></div>
  `,
  styles: `
    .ta-rows {
      display: grid;
      gap: 8px;
      margin: 0;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 4px 12px;
      }
    }
  `,
})
export class TitleAmountPage {
  private readonly api = inject(TitleAmountApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  protected readonly reasons = AMOUNT_REASON_LABELS;
  protected readonly canApprove = computed(() => this.auth.hasPermission(Permission.TITLE_AMOUNT_APPROVE));
  protected readonly canAdjust = computed(() => this.auth.hasPermission(Permission.TITLE_AMOUNT_ADJUST));
  /** O limite vale para toda a assessoria: altera e confirma quem aprova e vê todos os credores (como no servidor). */
  protected readonly canManageLimit = computed(() => this.canApprove() && (this.auth.user()?.allCreditors ?? false));
  /** Aba ativa na URL (`?aba=`). */
  protected readonly tab = urlTab(['aprovacoes', 'divergencias', 'limite'], this.canApprove() ? 'aprovacoes' : 'divergencias');
  protected readonly busy = signal(false);

  protected readonly creditorSource = inject(CreditorLookup);
  private readonly fb = inject(FormBuilder).nonNullable;
  /** Filtro de credor na URL (`?credor=`): segue para a tela de decisão e volta com ela. */
  protected readonly filters = syncFormWithUrl(this.fb.group({ creditor: this.fb.control<string[]>([]) }), { creditor: urlParam.ids('credor') });
  private readonly creditorFilter = toSignal(this.filters.valueChanges.pipe(map(() => this.filters.getRawValue().creditor[0] ?? null)), {
    initialValue: this.filters.getRawValue().creditor[0] ?? null,
  });
  protected readonly pending = rxResource({
    params: () => (this.canApprove() ? { creditorId: this.creditorFilter() } : undefined),
    stream: ({ params }) => this.api.pending({ page: 0, size: 100 }, params),
  });
  protected readonly pendingColumns: TableColumn[] = [
    { key: 'requestedAt', header: 'Pedida em', mobile: 'title' },
    { key: 'title', header: 'Título e credor', mobile: 'meta' },
    { key: 'reason', header: 'Motivo', mobile: 'meta' },
    { key: 'balance', header: 'Saldo em aberto', mobile: 'meta' },
    { key: 'delta', header: 'Diferença', align: 'end', mobile: 'hidden' },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  protected readonly pendingRows = computed(() => (this.pending.hasValue() ? this.pending.value().content : []));
  private readonly divergences = rxResource({ params: () => (this.canAdjust() ? true : undefined), stream: () => this.api.divergences({ page: 0, size: 100 }) });
  protected readonly divergenceRows = computed(() => (this.divergences.hasValue() ? this.divergences.value().content : []));
  private readonly settings = rxResource({ stream: () => this.api.settings() });
  protected readonly settingsValue = valueOr(this.settings, undefined);
  private readonly userId = computed(() => this.auth.user()?.id ?? null);
  protected readonly ownProposal = computed(() => {
    const s = this.settingsValue();
    return !!s && s.proposedThreshold !== null && s.proposedBy === this.userId();
  });
  protected readonly canConfirm = computed(() => {
    const s = this.settingsValue();
    return !!s && canConfirmThreshold(s, this.userId(), this.canManageLimit());
  });
  protected readonly threshold = new FormControl<number | null>(null, { validators: [Validators.required, Validators.min(0), Validators.max(100000)] });
  protected readonly creditors = nameMap(inject(CreditorNames), () => [...this.pendingRows().map((a) => a.creditorId), ...this.divergenceRows().map((d) => d.creditorId)]);

  /** O filtro da fila segue para a tela de decisão ("1 de N" na mesma fila e volta com ele). */
  protected linkParams(): Record<string, string> {
    const creditor = this.filters.getRawValue().creditor[0];
    return creditor ? { credor: creditor } : {};
  }

  constructor() {
    effect(() => {
      const s = this.settingsValue();
      if (s) {
        this.threshold.reset(s.approvalThreshold);
      }
    });
  }

  protected saveThreshold(): void {
    const value = this.threshold.value;
    if (value === null || this.threshold.invalid) {
      return;
    }
    this.busy.set(true);
    this.api
      .updateSettings(value)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe((s) => {
        this.notifications.success(
          s.proposedThreshold !== null && s.proposedThreshold === value
            ? 'Aumento proposto: o novo limite vale depois da confirmação de outra pessoa com permissão de aprovação.'
            : 'Limite salvo.',
        );
        this.settings.reload();
      });
  }

  protected confirmThreshold(): void {
    const proposed = this.settingsValue()?.proposedThreshold;
    if (proposed === null || proposed === undefined) {
      return;
    }
    this.busy.set(true);
    this.api
      .confirmSettings(proposed)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe(() => {
        this.notifications.success('Aumento do limite confirmado.');
        this.settings.reload();
      });
  }
}

