import { Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { debounceTime, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { Icon } from '../../../shared/components/icon/icon';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { activeFilterCount } from '../../../shared/data/filter-count';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { FORM_FIELD } from '../../../shared/forms/field';
import { emptyPage } from '../../../shared/models/page';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { channelText, defaultPeriod, periodProblem } from './creditor-reported.rules';
import { ReleaseHoldDialog, ReleaseHoldDialogData } from './release-hold-dialog';
import { HeldReversalItem, HoldFilter, HoldState, ReversalHoldsApi } from './reversal-holds.api';
import { HOLD_STATES, HOLD_STATE_LABELS, holdAge, holdOverdue, reversalReasonText } from './reversal-holds.rules';

/**
 * Aba "Estornos em conferência" (H07 parte 3): estornos de pagamento recebido pelo credor que pararam a cobrança desse
 * credor com o devedor até uma pessoa do financeiro conferir com o credor. `PAYMENT_VIEW` para ver; "Liberar a
 * cobrança" só quando o servidor diz que a pessoa pode (`PAYMENT_REVIEW`, não estornou nem informou). Os pendentes
 * aparecem todos, sem período (a pendência não vence — decisão 1671); liberados e encerrados pedem o período. Filtro do
 * pagamento só pela URL, a partir da ficha do pagamento (`?aba=estornos&pagamento=`). Nunca mostra a justificativa do
 * estorno, documento, contato ou comprovante.
 */
@Component({
  selector: 'app-reversal-holds-tab',
  imports: [FilterBar, Icon, NgbInputDatepicker, NgbTooltip, ...FORM_FIELD, ReactiveFormsModule, RouterLink, Amount, AppDatePipe, CellDef, DataTable, EntityPicker, StatusChip],
  template: `
    <app-filter-bar [activeCount]="activeFilters()">
      <form class="app-filters" data-tour="reversal-holds-filters" [formGroup]="filters" role="search" aria-label="Filtrar estornos em conferência">
        <app-field label="Situação">
          <select appInput formControlName="state">
            @for (s of states; track s) {
              <option [ngValue]="s">{{ stateLabels[s] }}</option>
            }
          </select>
        </app-field>
        <app-field label="De" [hint]="pending() ? 'Os que aguardam conferência aparecem todos, de qualquer data.' : 'Dia do estorno.'">
          <div class="input-group">
            <input appInput ngbDatepicker #from="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" />
            <button type="button" class="btn btn-outline-secondary" (click)="from.toggle()" [disabled]="pending()" aria-label="Abrir calendário de início"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <app-field label="Até">
          <div class="input-group">
            <input appInput ngbDatepicker #to="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" />
            <button type="button" class="btn btn-outline-secondary" (click)="to.toggle()" [disabled]="pending()" aria-label="Abrir calendário de fim"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <div class="app-filters__actions">
          <button class="btn btn-link" type="button" (click)="clear()">Limpar filtros</button>
        </div>
      </form>
    </app-filter-bar>
    @if (filters.controls.payment.value) {
      <p class="cs-chip" data-testid="payment-filter">
        <app-icon name="funnel" /> Só os estornos de um pagamento
        <button class="btn btn-sm btn-link" type="button" (click)="filters.controls.payment.setValue('')">Ver todos os pagamentos</button>
      </p>
    }
    @if (problem(); as message) {
      <div class="alert alert-warning d-flex gap-2 align-items-start" role="alert" data-testid="filter-problem">
        <app-icon name="exclamation-triangle" /><span>{{ message }}</span>
      </div>
    }
    <app-data-table
      caption="Estornos de pagamento ao credor em conferência"
      [rows]="query.rows()"
      [columns]="columns()"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="shield-check"
      [emptyMessage]="problem() ? 'Ajuste o período para consultar.' : 'Nenhum estorno de pagamento ao credor nesta situação.'"
      emptyHint="Quando um pagamento recebido pelo credor é estornado, a cobrança desse credor com o devedor fica parada aqui até alguém do financeiro conferir."
    >
      <ng-template appCell="placedAt" [appCellOf]="query.rows()" let-r>
        {{ r.hold.placedAt | appDate: 'date' }}
        @if (r.hold.state === 'PENDING') {
          <div class="cs-small d-flex align-items-center gap-1" [class.cs-warn]="overdue(r)">
            @if (overdue(r)) {
              <app-icon name="exclamation-triangle" />
              <span>Aguardando {{ age(r) }}: confira com o credor</span>
            } @else {
              <span>Aguardando {{ age(r) }}</span>
            }
          </div>
        }
      </ng-template>
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-r>
        {{ creditors.name(r.hold.creditorId) }}
        @if (r.hold.reversalsInWindow > 1) {
          <div class="cs-small app-muted">{{ r.hold.reversalsInWindow }} estornos deste tipo em 90 dias</div>
        }
      </ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-r>
        @if (r.hold.debtorName) {
          <a [routerLink]="['/devedores', r.hold.debtorId]" [attr.aria-label]="'Abrir devedor ' + r.hold.debtorName">{{ r.hold.debtorName }}</a>
        } @else {
          <span class="app-muted">—</span>
        }
      </ng-template>
      <ng-template appCell="target" [appCellOf]="query.rows()" let-r>
        @if (canViewAgreement()) {
          @for (id of r.hold.agreementIds; track id; let i = $index) {
            <a class="cs-link" [routerLink]="['/negociacao/acordos', id]" [attr.aria-label]="'Abrir acordo ' + (i + 1) + ' do ' + rowContext(r)">{{ r.hold.agreementIds.length === 1 ? 'Acordo' : 'Acordo ' + (i + 1) }}</a>
          }
        }
        @if (r.hold.titleIds.length && canViewTitle()) {
          @for (id of r.hold.titleIds.slice(0, 3); track id; let i = $index) {
            <a class="cs-link" [routerLink]="['/titulos', id]" [attr.aria-label]="'Abrir título ' + (i + 1) + ' do ' + rowContext(r)">{{ r.hold.titleIds.length === 1 ? 'Título' : 'Título ' + (i + 1) }}</a>
          }
          @if (r.hold.titleIds.length > 3) {
            <span class="app-muted">e mais {{ r.hold.titleIds.length - 3 }}</span>
          }
        } @else if (!r.hold.agreementIds.length || !canViewAgreement()) {
          <span class="app-muted">{{ r.hold.titleCount === 1 ? '1 título' : r.hold.titleCount + ' títulos' }}</span>
        }
      </ng-template>
      <ng-template appCell="payment" [appCellOf]="query.rows()" let-r>
        <a [routerLink]="['/pagamentos', r.hold.paymentId]" [attr.aria-label]="'Abrir ' + rowContext(r)">
          @if (r.amount !== null) {
            <app-amount [value]="r.amount" />
          } @else {
            Pagamento
          }
        </a>
        @if (r.paidAt) {
          <div class="cs-small app-muted">pago em {{ r.paidAt | appDate: 'date' }}</div>
        }
        @if (r.channel) {
          <div class="cs-small app-muted">{{ channel(r) }}</div>
        }
      </ng-template>
      <ng-template appCell="reversal" [appCellOf]="query.rows()" let-r>
        {{ reversalLabel(r) }}
        <div class="cs-small app-muted">{{ r.hold.placedByType === 'SYSTEM' ? 'pelo provedor de pagamento' : 'por uma pessoa da assessoria' }}</div>
      </ng-template>
      <ng-template appCell="state" [appCellOf]="query.rows()" let-r><app-status-chip domain="reversalHold" [status]="r.hold.state" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-r>
        @if (r.hold.canRelease && canReview()) {
          <button type="button" class="btn btn-sm btn-outline-primary" (click)="release(r)" [attr.aria-label]="'Liberar a cobrança do ' + rowContext(r)">Liberar a cobrança</button>
        }
        <a class="btn btn-sm btn-link" [routerLink]="['/pagamentos', r.hold.paymentId]" [attr.aria-label]="'Abrir ' + rowContext(r)" ngbTooltip="Abrir pagamento">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .cs-small { font-size: var(--app-text-xs); }
    .cs-warn { color: var(--bs-warning-text-emphasis); font-weight: var(--app-weight-semibold); }
    .cs-link + .cs-link { margin-left: 8px; }
    .cs-chip { margin: 0 0 var(--app-gap); }
  `,
})
export class ReversalHoldsTab {
  private readonly api = inject(ReversalHoldsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  private readonly date = new AppDatePipe();
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly states = HOLD_STATES;
  protected readonly stateLabels = HOLD_STATE_LABELS;
  protected readonly canViewDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  protected readonly canViewTitle = computed(() => this.auth.hasAnyPermission([Permission.DEBTOR_VIEW, Permission.CASE_VIEW]));
  protected readonly canViewAgreement = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));
  protected readonly canReview = computed(() => this.auth.hasPermission(Permission.PAYMENT_REVIEW));

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly period = defaultPeriod();
  /** `?aba=estornos&situacao=&de=&ate=&credor=&pagamento=` (a situação padrão e o período padrão não vão para a URL). */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      state: this.fb.control<HoldState>('PENDING'),
      from: this.fb.control<string | null>(this.period.from),
      to: this.fb.control<string | null>(this.period.to),
      creditor: this.fb.control<string[]>([]),
      payment: [''],
    }),
    {
      state: urlParam.choice('situacao', HOLD_STATES),
      from: urlParam.date('de'),
      to: urlParam.date('ate'),
      creditor: urlParam.ids('credor'),
      payment: urlParam.id('pagamento'),
    },
  );
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => this.current())),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly pending = computed(() => this.filter().state === 'PENDING');
  protected readonly problem = computed(() => (this.pending() ? null : periodProblem(this.filter().from, this.filter().to)));
  protected readonly query = pagedQuery(
    this.filter,
    (f, page) => (f.state !== 'PENDING' && periodProblem(f.from, f.to) ? of(emptyPage<HeldReversalItem>(page.size)) : this.api.search(f, page)),
    { url: true },
  );
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((r) => r.hold.creditorId));

  constructor() {
    // pendentes não usam período (UX-02): De e Até ficam desabilitados, com a dica
    effect(() => {
      const options = { emitEvent: false };
      if (this.pending()) {
        this.filters.controls.from.disable(options);
        this.filters.controls.to.disable(options);
      } else {
        this.filters.controls.from.enable(options);
        this.filters.controls.to.enable(options);
      }
    });
  }

  protected readonly columns = computed<TableColumn[]>(() => [
    { key: 'placedAt', header: 'Estornado em', mobile: 'meta' },
    { key: 'creditor', header: 'Credor', mobile: 'meta' },
    ...(this.canViewDebtor() ? [{ key: 'debtor', header: 'Devedor', mobile: 'title' } as TableColumn] : []),
    { key: 'target', header: 'Títulos ou acordo', mobile: 'meta' },
    { key: 'payment', header: 'Pagamento', align: 'end', mobile: 'meta' },
    { key: 'reversal', header: 'Motivo do estorno', mobile: 'meta' },
    { key: 'state', header: 'Situação', mobile: 'badge' },
    { key: 'actions', header: 'Ações', actions: true },
  ]);

  protected age(r: HeldReversalItem): string {
    return holdAge(r.hold.placedAt);
  }

  protected overdue(r: HeldReversalItem): boolean {
    return holdOverdue(r.hold.placedAt);
  }

  protected channel(r: HeldReversalItem): string {
    return r.channel ? channelText({ channel: r.channel, token: r.token }) : '';
  }

  protected reversalLabel(r: HeldReversalItem): string {
    return reversalReasonText(r.hold.reversalReason);
  }

  protected rowContext(r: HeldReversalItem): string {
    return `estorno de ${this.date.transform(r.hold.placedAt, 'date')}${r.amount === null ? '' : ', ' + formatAmount(r.amount)}`;
  }

  protected release(r: HeldReversalItem): void {
    this.dialog
      .open<ReleaseHoldDialog, ReleaseHoldDialogData, HeldReversalItem>(ReleaseHoldDialog, {
        data: { item: r, creditorName: this.creditors.name(r.hold.creditorId) ?? 'Credor' },
      })
      .afterClosed()
      .subscribe((released) => {
        if (released) {
          this.notifications.success('Cobrança liberada. Os títulos voltam à cobrança, respeitando a política de contato.');
          this.query.reload();
        }
      });
  }

  protected clear(): void {
    this.filters.reset({ state: 'PENDING', ...this.period, creditor: [], payment: '' });
  }

  private current(): HoldFilter {
    const v = this.filters.getRawValue();
    const pending = v.state === 'PENDING';
    return {
      from: pending ? null : (v.from ?? null),
      to: pending ? null : (v.to ?? null),
      creditorId: v.creditor[0] ?? null,
      state: v.state,
      paymentId: v.payment || null,
    };
  }

  protected readonly activeFilters = activeFilterCount(this.filters);
}
