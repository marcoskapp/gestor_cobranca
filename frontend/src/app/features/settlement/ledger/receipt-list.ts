import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, finalize, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { activeFilterCount } from '../../../shared/data/filter-count';
import { clearOnChange } from '../../../shared/data/dependent-filter';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { isoDate } from '../../../shared/models/dates';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { ReceiptTable } from '../components/receipt-table';
import { CommissionLedgerApi } from '../data/settlement.api';
import { RatingStatus, ReceiptFilter, ReceiptKind } from '../data/settlement.models';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';

/**
 * Razão de comissão: recebimentos e estornos apurados (append-only), com regra, versão e memória de
 * cálculo; os aguardando regra podem ser reapurados por quem gerencia comissão.
 */
@Component({
  selector: 'app-receipt-list',
  imports: [ReactiveFormsModule, NgbInputDatepicker, NgbTooltip, ...FORM_FIELD, Icon, EntityPicker, FilterBar, PageHeader, ReceiptTable],
  template: `
    <app-page-header title="Razão de comissão" subtitle="Cada recebimento confirmado vira um lançamento com a regra vigente na data do pagamento. Lançamentos não mudam: estornos compensam.">
      @if (canManage()) {
        <button class="btn btn-outline-secondary" type="button" (click)="ratePending()" [disabled]="!creditorId() || rating()" [ngbTooltip]="creditorId() ? 'Tenta apurar os recebimentos que aguardam regra' : 'Escolha um credor'" data-tour="ledger-rate">
          <app-icon name="arrow-repeat" />
          Reapurar pendentes
        </button>
      }
    </app-page-header>
    <app-filter-bar [activeCount]="activeFilters()">
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar razão de comissão">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.portfolio" [source]="portfolioSource" label="Carteira" [single]="true" />
      </div>
      <app-field label="Lançamento">
        <select appInput formControlName="kind">
          <option value="">Recebimentos e estornos</option>
          <option value="PAYMENT">Recebimentos</option>
          <option value="REVERSAL">Estornos</option>
        </select>
      </app-field>
      <app-field label="Apuração">
        <select appInput formControlName="rating">
          <option value="">Todas</option>
          <option value="RATED">Apurados</option>
          <option value="PENDING_RULES">Aguardando regra</option>
        </select>
      </app-field>
      <app-field label="Prestação de contas">
        <select appInput formControlName="inStatement">
          <option value="">Todos</option>
          <option value="false">Ainda não prestados</option>
          <option value="true">Já prestados</option>
        </select>
      </app-field>
      <app-field label="Pago de">
        <div class="input-group">
          <input appInput ngbDatepicker #from="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" autocomplete="off" />
          <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário (pago de)" (click)="from.toggle()">
            <app-icon name="calendar-event" />
          </button>
        </div>
      </app-field>
      <app-field label="Pago até">
        <div class="input-group">
          <input appInput ngbDatepicker #to="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" autocomplete="off" />
          <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário (pago até)" (click)="to.toggle()">
            <app-icon name="calendar-event" />
          </button>
        </div>
      </app-field>
      <div class="app-filters__actions">
        <button class="btn btn-link" type="button" (click)="clear()">Limpar filtros</button>
      </div>
    </form>
    </app-filter-bar>
    <app-receipt-table [query]="query" />
  `,
})
export class ReceiptList {
  private readonly api = inject(CommissionLedgerApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  private readonly portfolioLookup = inject(PortfolioLookup);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.COMMISSION_MANAGE));
  protected readonly rating = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  /**
   * Filtros na URL (`?credor=&carteira=&tipo=&apuracao=&prestado=&pago-de=&pago-ate=`), preservados ao voltar e usados
   * pelo drill-down do painel de recuperação (recuperado, recebido e estornado do período: a mesma base).
   */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      creditor: this.fb.control<string[]>([]),
      portfolio: this.fb.control<string[]>([]),
      kind: [''],
      rating: [''],
      inStatement: [''],
      from: this.fb.control<string | null>(null),
      to: this.fb.control<string | null>(null),
    }),
    {
      creditor: urlParam.ids('credor'),
      portfolio: urlParam.ids('carteira'),
      kind: urlParam.choice('tipo', ['PAYMENT', 'REVERSAL']),
      rating: urlParam.choice('apuracao', ['RATED', 'PENDING_RULES']),
      inStatement: urlParam.choice('prestado', ['true', 'false']),
      from: urlParam.date('pago-de'),
      to: urlParam.date('pago-ate'),
    },
  );
  protected readonly activeFilters = activeFilterCount(this.filters);
  protected readonly portfolioSource = this.portfolioLookup.source(() => this.filters.controls.creditor.value[0] ?? null);

  constructor() {
    // a carteira é do credor: trocar o credor limpa a carteira escolhida
    clearOnChange(this.filters.controls.creditor, this.filters.controls.portfolio, [] as string[]);
  }
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => this.current()),
    ),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly creditorId = computed(() => this.filter().creditorId);
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page), { url: true });

  protected clear(): void {
    this.filters.reset({ creditor: [], portfolio: [], kind: '', rating: '', inStatement: '', from: null, to: null });
  }

  protected ratePending(): void {
    const id = this.creditorId();
    if (!id) {
      return;
    }
    this.rating.set(true);
    this.api
      .ratePending(id)
      .pipe(finalize(() => this.rating.set(false)))
      .subscribe((r) => {
        this.notifications.success(r.rated ? `${r.rated} recebimento(s) apurado(s).` : 'Nenhum recebimento pendente pôde ser apurado: publique a regra vigente na data do pagamento.');
        this.query.reload();
      });
  }

  private current(): ReceiptFilter {
    const v = this.filters.getRawValue();
    return {
      creditorId: v.creditor[0] ?? null,
      portfolioId: v.portfolio[0] ?? null,
      kind: (v.kind || null) as ReceiptKind | null,
      ratingStatus: (v.rating || null) as RatingStatus | null,
      inStatement: v.inStatement === '' ? null : v.inStatement === 'true',
      from: isoDate(v.from),
      to: isoDate(v.to),
    };
  }
}
