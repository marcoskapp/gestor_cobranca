import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { endOfDayInstant, fromLocalDate, startOfDayInstant } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { DebtorLookup, DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { PaymentLauncher } from '../data/payment-launcher';
import { PaymentsApi } from '../data/payment.api';
import { METHOD_LABELS, ORIGIN_LABELS, SOURCE_LABELS } from '../data/payment.labels';
import { PaymentFilter, PaymentView } from '../data/payment.models';

/** Pagamentos recebidos (Pix integrado, extrato e informados), filtráveis por situação, origem, credor e período. */
@Component({
  selector: 'app-payment-list',
  imports: [
    Icon,
    NgbInputDatepicker,
    NgbTooltip,
    ...FORM_FIELD,
    ReactiveFormsModule,
    RouterLink,
    Amount,
    AppDatePipe,
    CellDef,
    DataTable,
    EntityPicker,
    PageHeader,
    StatusChip,
  ],
  template: `
    <app-page-header title="Pagamentos" subtitle="Tudo o que entrou: Pix integrado, confirmações por extrato e pagamentos informados com comprovante.">
      @if (canRegister()) {
        <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="register()" data-tour="payment-register">
          <app-icon name="credit-card" />
          Informar pagamento
        </button>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar pagamentos">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      @if (canSearchDebtors()) {
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.debtor" [source]="debtorSource" label="Devedor" [single]="true" />
        </div>
      }
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Origem">
        <select appInput formControlName="source">
          <option value="">Todas</option>
          @for (s of sources; track s[0]) {
            <option [ngValue]="s[0]">{{ s[1] }}</option>
          }
        </select>
      </app-field>
      <app-field label="Meio">
        <select appInput formControlName="method">
          <option value="">Todos</option>
          @for (m of methods; track m[0]) {
            <option [ngValue]="m[0]">{{ m[1] }}</option>
          }
        </select>
      </app-field>
      <app-field label="Pago de">
        <div class="input-group">
          <input appInput ngbDatepicker #from="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" />
          <button type="button" class="btn btn-outline-secondary" (click)="from.toggle()" aria-label="Abrir calendário de início"><app-icon name="calendar-event" /></button>
        </div>
      </app-field>
      <app-field label="Pago até">
        <div class="input-group">
          <input appInput ngbDatepicker #to="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" />
          <button type="button" class="btn btn-outline-secondary" (click)="to.toggle()" aria-label="Abrir calendário de fim"><app-icon name="calendar-event" /></button>
        </div>
      </app-field>
      <div class="app-filters__actions">
        <div class="form-check mb-0">
          <input class="form-check-input" type="checkbox" id="pl-with-balance" formControlName="withBalance" />
          <label class="form-check-label" for="pl-with-balance">Com saldo a realocar</label>
        </div>
        <button class="btn btn-link" type="button" (click)="clear()">Limpar filtros</button>
      </div>
    </form>
    <app-data-table
      caption="Pagamentos"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="cash-stack"
      emptyMessage="Nenhum pagamento encontrado."
      emptyHint="Ajuste os filtros. Pagamentos Pix aparecem sozinhos quando o PSP confirma."
    >
      <ng-template appCell="paidAt" [appCellOf]="query.rows()" let-p>{{ p.paidAt | appDate }}</ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-p>
        {{ debtors.name(p.debtorId) }}
        <div class="app-muted pl-small">{{ creditors.name(p.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-p>
        <app-amount [value]="p.amount" />
        @if (p.pendingReallocation > 0) {
          <div class="pl-small pl-warn">a realocar <app-amount [value]="p.pendingReallocation" /></div>
        }
      </ng-template>
      <ng-template appCell="source" [appCellOf]="query.rows()" let-p>
        {{ methodLabels[p.method] }}
        <div class="app-muted pl-small">{{ sourceText(p) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-p>
        <app-status-chip domain="payment" [status]="p.status" />
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-p>
        <a class="btn btn-sm btn-link" [routerLink]="['/pagamentos', p.id]" aria-label="Abrir pagamento" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .pl-small {
      font-size: var(--app-text-xs);
    }
    .pl-warn {
      color: var(--bs-warning-text-emphasis);
      font-weight: 600;
    }
  `,
})
export class PaymentList {
  private readonly api = inject(PaymentsApi);
  private readonly auth = inject(AuthService);
  private readonly launcher = inject(PaymentLauncher);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly debtorSource = inject(DebtorLookup);
  protected readonly statuses = statusOptions('payment');
  protected readonly sources = Object.entries(SOURCE_LABELS);
  protected readonly methods = Object.entries(METHOD_LABELS);
  protected readonly methodLabels = METHOD_LABELS;
  protected readonly canRegister = computed(() => this.auth.hasPermission(Permission.PAYMENT_REGISTER_EXTERNAL));
  protected readonly canSearchDebtors = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly params = this.route.snapshot.queryParamMap;
  protected readonly filters = this.fb.group({
    creditor: this.fb.control<string[]>(this.params.get('credor') ? [this.params.get('credor')!] : []),
    debtor: this.fb.control<string[]>(this.params.get('devedor') ? [this.params.get('devedor')!] : []),
    status: [this.params.get('situacao') ?? ''],
    source: [''],
    method: [''],
    // datas ISO yyyy-MM-dd (datepicker do ng-bootstrap)
    from: this.fb.control<string | null>(null),
    to: this.fb.control<string | null>(null),
    withBalance: [this.params.get('saldo') === '1'],
  });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => this.current()),
    ),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((p) => p.creditorId));
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((p) => p.debtorId), { batch: 100 });

  protected readonly columns: TableColumn[] = [
    { key: 'paidAt', header: 'Pago em' },
    { key: 'debtor', header: 'Devedor e credor' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'source', header: 'Meio e origem' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected sourceText(p: PaymentView): string {
    if (p.source === 'EXTERNAL') {
      return p.externalOrigin ? `Informado · ${ORIGIN_LABELS[p.externalOrigin]}` : 'Informado';
    }
    return p.reviewMode === 'STATEMENT' ? 'Confirmado pelo extrato' : 'Integrado (PSP)';
  }

  protected clear(): void {
    this.filters.reset({ creditor: [], debtor: [], status: '', source: '', method: '', from: null, to: null, withBalance: false });
  }

  protected register(): void {
    const debtorId = this.filters.controls.debtor.value[0] ?? null;
    this.launcher.external({ debtorId: debtorId, debtorName: debtorId ? this.debtors.name(debtorId) : null }).subscribe((payment) => {
      if (payment) {
        void this.router.navigate(['/pagamentos', payment.id]);
      }
    });
  }

  private current(): PaymentFilter {
    const v = this.filters.getRawValue();
    return {
      creditorId: v.creditor[0] ?? null,
      debtorId: v.debtor[0] ?? null,
      status: v.status || null,
      source: v.source || null,
      method: v.method || null,
      paidFrom: startOfDayInstant(fromLocalDate(v.from)),
      paidTo: endOfDayInstant(fromLocalDate(v.to)),
      withBalance: v.withBalance || null,
    };
  }
}
