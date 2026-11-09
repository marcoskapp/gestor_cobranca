import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
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
import { syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { activeFilterCount } from '../../../shared/data/filter-count';

import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { DebtorLookup, DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { ProposalsApi } from '../data/agreement.api';
import { ProposalFilter, ProposalView } from '../data/agreement.models';
import { actorText, validityLapsed, violationText } from '../data/agreement.rules';
import { authorityProfileText } from '../data/agreement.labels';
import { percent, planText } from '../data/negotiation.labels';
import { deadlineText, discountShare } from './proposal-approval';

/** Valor do filtro "Criada por" que restringe às propostas do próprio usuário (`mine=true` na API). */
export const MINE = 'MINE';

/** Valores do filtro "Criada por" aceitos na URL (`?autor=`). */
export const ACTOR_FILTERS = ['USER', MINE, 'ASSISTANT'] as const;

export function toProposalFilter(v: { creditor: string[]; debtor: string[]; status: string; actorType: string }): ProposalFilter {
  const mine = v.actorType === MINE;
  return {
    creditorId: v.creditor[0] ?? null,
    debtorId: v.debtor[0] ?? null,
    status: v.status || null,
    actorType: mine ? null : v.actorType || null,
    ...(mine ? { mine: true } : {}),
  };
}

/**
 * Propostas de negociação. Com `queue` (rota `/negociacao/aprovacoes`) mostra a fila de aprovação
 * (F16): pendentes nos credores do escopo que cabem na alçada do aprovador, exceto as próprias.
 */
@Component({
  selector: 'app-proposal-list',
  imports: [
    FilterBar,
    Icon,
    NgbTooltip,
    ...FORM_FIELD,
    ReactiveFormsModule,
    RouterLink,
    Amount,
    CellDef,
    DataTable,
    EntityPicker,
    PageHeader,
    StatusChip,
    AppDatePipe,
  ],
  template: `
    @if (queue()) {
      <app-page-header
        title="Aprovações de propostas"
        subtitle="Propostas acima da alçada de quem negociou e dentro da sua. Revise uma a uma: ao decidir, a próxima abre sozinha."
      />
      <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar a fila de aprovação">
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
      </form>
    } @else {
      <app-page-header title="Propostas" subtitle="Condições propostas ao devedor: prontas para aceite, aguardando aprovação, aceitas e formalizadas.">
        @if (canApprove()) {
          <a class="btn btn-outline-secondary d-inline-flex align-items-center gap-2" routerLink="/negociacao/aprovacoes">
            <app-icon name="person-check" />
            Fila de aprovação
          </a>
        }
        @if (canSimulate()) {
          <a class="btn btn-primary d-inline-flex align-items-center gap-2" routerLink="/negociacao/simulador">
            <app-icon name="calculator" />
            Nova simulação
          </a>
        }
      </app-page-header>

      <app-filter-bar [activeCount]="activeFilters()">
        <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar propostas">
          @if (canSearchDebtors()) {
            <div class="app-filters__wide">
              <app-entity-picker [control]="filters.controls.debtor" [source]="debtorSource" label="Devedor (nome ou CPF/CNPJ)" [single]="true" />
            </div>
          }
          <div class="app-filters__wide">
            <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
          </div>
          <app-field label="Situação">
            <select appInput formControlName="status">
              <option value="">Todas</option>
              @for (s of statuses; track s.value) {
                <option [ngValue]="s.value">{{ s.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Criada por">
            <select appInput formControlName="actorType">
              <option value="">Qualquer autor</option>
              <option value="USER">Operadores</option>
              <option [value]="mine">Somente as minhas</option>
              <option value="ASSISTANT">Assistente</option>
            </select>
          </app-field>
        </form>
      </app-filter-bar>
    }

    <app-data-table
      [caption]="queue() ? 'Propostas aguardando a sua aprovação' : 'Propostas'"
      [rows]="query.rows()"
      [columns]="columns()"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      [sort]="query.sort()"
      (pageChange)="query.setPage($event)"
      (sortChange)="query.setSort($event)"
      (retry)="query.reload()"
      [emptyIcon]="queue() ? 'task_alt' : 'assignment'"
      [emptyMessage]="queue() ? 'Nenhuma proposta aguardando a sua aprovação.' : 'Nenhuma proposta encontrada.'"
      [emptyHint]="queue() ? 'Quando um negociador pedir aprovação de condições dentro da sua alçada, ela aparece aqui.' : 'Propostas nascem no simulador, a partir de condições validadas.'"
    >
      <ng-template appCell="createdAt" [appCellOf]="query.rows()" let-p>
        <a [routerLink]="link(p)" [queryParams]="linkParams()">{{ p.createdAt | appDate }}</a>
        <!-- na fila, o prazo tem coluna própria -->
        @if (!queue() && lapsed(p)) {
          <div class="pl-late">Validade vencida</div>
        } @else if (!queue() && p.validUntil) {
          <div class="app-muted">válida até {{ p.validUntil | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-p>
        {{ debtors.name(p.debtorId) }}
        <div class="app-muted">{{ creditors.name(p.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="total" [appCellOf]="query.rows()" let-p>
        <app-amount [value]="p.totalAmount" />
        <!-- na fila, o desconto tem coluna própria -->
        @if (!queue()) {
          <div class="app-muted">desconto <app-amount [value]="p.discountAmount" /></div>
        }
      </ng-template>
      <ng-template appCell="plan" [appCellOf]="query.rows()" let-p>
        {{ plan(p) }}
        <div class="app-muted">nível {{ p.level }}{{ p.renegotiationNumber > 0 ? ' · renegociação ' + p.renegotiationNumber : '' }}</div>
      </ng-template>
      <ng-template appCell="actor" [appCellOf]="query.rows()" let-p>
        @if (p.actor.type === 'ASSISTANT') {
          <span class="pl-assistant"><app-icon name="robot" />Assistente</span>
        } @else {
          {{ author(p) }}
        }
        @if (queue() && p.actor.profileCode) {
          <div class="app-muted">alçada {{ profile(p.actor.profileCode) }}</div>
        }
        @if (p.authorityViolations.length) {
          <div class="app-muted">{{ violations(p) }}</div>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-p>
        <app-status-chip domain="proposal" [status]="p.status" />
      </ng-template>
      <ng-template appCell="discount" [appCellOf]="query.rows()" let-p>
        <span class="app-numeric">{{ percentText(discount(p)) }}</span>
        <div class="app-muted"><app-amount [value]="p.discountAmount" /></div>
      </ng-template>
      <ng-template appCell="deadline" [appCellOf]="query.rows()" let-p>
        @let d = deadline(p);
        <span [class.pl-late]="d.tone === 'danger'" [class.pl-soon]="d.tone === 'warning'">{{ d.text }}</span>
        @if (p.validUntil) {
          <div class="app-muted">{{ p.validUntil | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-p>
        @if (queue()) {
          <a class="btn btn-sm btn-outline-primary" [routerLink]="link(p)" [queryParams]="linkParams()">Revisar</a>
        } @else {
          <a class="btn btn-sm btn-link" [routerLink]="['/negociacao/propostas', p.id]" aria-label="Abrir proposta" ngbTooltip="Abrir">
            <app-icon name="chevron-right" />
          </a>
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .pl-late {
      font-weight: var(--app-weight-semibold);
      color: var(--bs-danger-text-emphasis);
    }
    .pl-soon {
      font-weight: var(--app-weight-semibold);
      color: var(--bs-warning-text-emphasis);
    }
    .pl-assistant {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      app-icon {
        font-size: 1rem;
      }
    }
  `,
})
export class ProposalList {
  /** Fila de aprovação (dado da rota). */
  readonly queue = input(false);

  private readonly api = inject(ProposalsApi);
  private readonly auth = inject(AuthService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly debtorSource = inject(DebtorLookup);
  protected readonly statuses = statusOptions('proposal');
  protected readonly canApprove = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_APPROVE));
  protected readonly canSimulate = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE));
  protected readonly canSearchDebtors = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  private readonly today = zonedNow().date;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly mine = MINE;
  /**
   * Filtros na URL (`?credor=&devedor=&situacao=&autor=`), preservados ao voltar; "Minhas pendências" (servidor) gera
   * `?situacao=APPROVED&autor=MINE`.
   */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      creditor: this.fb.control<string[]>([]),
      debtor: this.fb.control<string[]>([]),
      status: [''],
      actorType: [''],
    }),
    {
      creditor: urlParam.ids('credor'),
      debtor: urlParam.ids('devedor'),
      status: urlParam.choice('situacao', statusOptions('proposal').map((s) => s.value)),
      actorType: urlParam.choice<string>('autor', ACTOR_FILTERS),
    },
  );
  /** Filtros aplicados (botão "Filtros (n)" no celular; ux.md §12.3). */
  protected readonly activeFilters = activeFilterCount(this.filters);
  private readonly formFilter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => toProposalFilter(this.filters.getRawValue())),
    ),
    { initialValue: toProposalFilter(this.filters.getRawValue()), equal: sameValue },
  );
  private readonly filter = computed(() => ({ queue: this.queue(), ...this.formFilter() }), { equal: sameValue });
  protected readonly query = pagedQuery(this.filter, (f, page) => (f.queue ? this.api.approvalQueue(page, { creditorId: f.creditorId }) : this.api.search(f, page)), {
    url: true,
    sortFields: { createdAt: 'createdAt', total: 'totalAmount' },
  });

  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((p) => p.debtorId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((p) => p.creditorId));
  private readonly users = nameMap(inject(UserNames), () => this.query.rows().filter((p) => p.actor.type === 'USER').map((p) => p.actor.id), { batch: USER_NAMES_BATCH });

  /**
   * Ordenação por coluna só na consulta de propostas (a fila de aprovação tem a ordem própria do servidor, das mais
   * antigas para as mais novas). Na fila: desconto em % da dívida e prazo da aprovação (validade da proposta).
   */
  protected readonly columns = computed((): TableColumn[] =>
    this.queue()
      ? [
          { key: 'createdAt', header: 'Pedida em', mobile: 'title' },
          { key: 'debtor', header: 'Devedor', mobile: 'meta' },
          { key: 'total', header: 'Valor', align: 'end', mobile: 'meta' },
          { key: 'discount', header: 'Desconto', align: 'end', mobile: 'meta' },
          { key: 'deadline', header: 'Prazo', mobile: 'meta' },
          { key: 'actor', header: 'Negociador e motivo', mobile: 'hidden' },
          { key: 'actions', header: 'Ações', actions: true },
        ]
      : [
          { key: 'createdAt', header: 'Criada em', sortable: true, mobile: 'title' },
          { key: 'debtor', header: 'Devedor', mobile: 'meta' },
          { key: 'total', header: 'Total', align: 'end', sortable: true, mobile: 'meta' },
          { key: 'plan', header: 'Forma de pagamento', mobile: 'meta' },
          { key: 'actor', header: 'Negociador', mobile: 'hidden' },
          { key: 'status', header: 'Situação', mobile: 'badge' },
          { key: 'actions', header: 'Ações', actions: true },
        ],
  );

  /** Na fila, a proposta abre na tela de decisão (`/negociacao/aprovacoes/:id`) com o filtro de credor. */
  protected link(p: ProposalView): string[] {
    return this.queue() ? ['/negociacao/aprovacoes', p.id] : ['/negociacao/propostas', p.id];
  }

  protected linkParams(): Record<string, string> {
    const creditor = this.filters.controls.creditor.value[0];
    return this.queue() && creditor ? { credor: creditor } : {};
  }

  protected discount(p: ProposalView): number {
    return discountShare(p);
  }

  protected percentText(value: number): string {
    return percent(value);
  }

  protected deadline(p: ProposalView) {
    return deadlineText(p.validUntil, this.today);
  }

  protected plan(p: ProposalView): string {
    return planText({ installments: p.installments, downPayment: p.downPayment });
  }

  protected lapsed(p: ProposalView): boolean {
    return validityLapsed(p, this.today);
  }

  protected author(p: ProposalView): string {
    return actorText(p.actor.type, p.actor.id, this.auth.user()?.id ?? null, (id) => this.users.name(id));
  }

  protected profile(code: string): string {
    return authorityProfileText(code);
  }

  protected violations(p: ProposalView): string {
    return p.authorityViolations.map(violationText).join(' · ');
  }
}
