import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { UserNames } from '../../collection/data/collection.lookups';
import { zonedNow } from '../../collection/data/collection.rules';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { ProposalsApi } from '../data/agreement.api';
import { ProposalFilter, ProposalView } from '../data/agreement.models';
import { actorText, validityLapsed, violationText } from '../data/agreement.rules';
import { planText } from '../data/negotiation.labels';

export function toProposalFilter(v: { creditor: string[]; status: string; actorType: string }): ProposalFilter {
  return { creditorId: v.creditor[0] ?? null, debtorId: null, status: v.status || null, actorType: v.actorType || null };
}

/**
 * Propostas de negociação. Com `queue` (rota `/negociacao/aprovacoes`) mostra a fila de aprovação
 * (F16): pendentes nos credores do escopo que cabem na alçada do aprovador, exceto as próprias.
 */
@Component({
  selector: 'app-proposal-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTooltipModule,
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
        subtitle="Propostas acima da alçada de quem negociou e dentro da sua. Confira as condições e a justificativa antes de decidir."
      />
    } @else {
      <app-page-header title="Propostas" subtitle="Condições propostas ao devedor: prontas para aceite, aguardando aprovação, aceitas e formalizadas.">
        @if (canApprove()) {
          <a mat-stroked-button routerLink="/negociacao/aprovacoes">
            <mat-icon aria-hidden="true">how_to_reg</mat-icon>
            Fila de aprovação
          </a>
        }
        @if (canSimulate()) {
          <a mat-flat-button routerLink="/negociacao/simulador">
            <mat-icon aria-hidden="true">calculate</mat-icon>
            Nova simulação
          </a>
        }
      </app-page-header>

      <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar propostas">
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <mat-form-field class="app-compact">
          <mat-label>Situação</mat-label>
          <mat-select formControlName="status">
            <mat-option value="">Todas</mat-option>
            @for (s of statuses; track s.value) {
              <mat-option [value]="s.value">{{ s.label }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="app-compact">
          <mat-label>Criada por</mat-label>
          <mat-select formControlName="actorType">
            <mat-option value="">Qualquer autor</mat-option>
            <mat-option value="USER">Operadores</mat-option>
            <mat-option value="ASSISTANT">Assistente</mat-option>
          </mat-select>
        </mat-form-field>
      </form>
    }

    <app-data-table
      [caption]="queue() ? 'Propostas aguardando a sua aprovação' : 'Propostas'"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      [emptyIcon]="queue() ? 'task_alt' : 'assignment'"
      [emptyMessage]="queue() ? 'Nenhuma proposta aguardando a sua aprovação.' : 'Nenhuma proposta encontrada.'"
      [emptyHint]="queue() ? 'Quando um negociador pedir aprovação de condições dentro da sua alçada, ela aparece aqui.' : 'Propostas nascem no simulador, a partir de condições validadas.'"
    >
      <ng-template appCell="createdAt" [appCellOf]="query.rows()" let-p>
        <a [routerLink]="['/negociacao/propostas', p.id]">{{ p.createdAt | appDate }}</a>
        @if (lapsed(p)) {
          <div class="pl-late">Validade vencida</div>
        } @else if (p.validUntil) {
          <div class="app-muted">válida até {{ p.validUntil | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-p>
        {{ debtors.name(p.debtorId) }}
        <div class="app-muted">{{ creditors.name(p.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="total" [appCellOf]="query.rows()" let-p>
        <app-amount [value]="p.totalAmount" />
        <div class="app-muted">desconto <app-amount [value]="p.discountAmount" /></div>
      </ng-template>
      <ng-template appCell="plan" [appCellOf]="query.rows()" let-p>
        {{ plan(p) }}
        <div class="app-muted">nível {{ p.level }}{{ p.renegotiationNumber > 0 ? ' · renegociação ' + p.renegotiationNumber : '' }}</div>
      </ng-template>
      <ng-template appCell="actor" [appCellOf]="query.rows()" let-p>
        @if (p.actor.type === 'ASSISTANT') {
          <span class="pl-assistant"><mat-icon aria-hidden="true">smart_toy</mat-icon>Assistente</span>
        } @else {
          {{ author(p) }}
        }
        @if (p.authorityViolations.length) {
          <div class="app-muted">{{ violations(p) }}</div>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-p>
        <app-status-chip domain="proposal" [status]="p.status" />
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-p>
        <a mat-icon-button [routerLink]="['/negociacao/propostas', p.id]" aria-label="Abrir proposta" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .pl-late {
      font-weight: 600;
      color: var(--app-danger-fg);
    }
    .pl-assistant {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      mat-icon {
        width: 18px;
        height: 18px;
        font-size: 18px;
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
  protected readonly statuses = statusOptions('proposal');
  protected readonly canApprove = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_APPROVE));
  protected readonly canSimulate = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE));
  private readonly today = zonedNow().date;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), status: [''], actorType: [''] });
  private readonly formFilter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => toProposalFilter(this.filters.getRawValue())),
    ),
    { initialValue: toProposalFilter(this.filters.getRawValue()), equal: sameValue },
  );
  private readonly filter = computed(() => ({ queue: this.queue(), ...this.formFilter() }), { equal: sameValue });
  protected readonly query = pagedQuery(this.filter, (f, page) => (f.queue ? this.api.approvalQueue(page) : this.api.search(f, page)));

  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((p) => p.debtorId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((p) => p.creditorId));
  private readonly users = nameMap(inject(UserNames), () => this.query.rows().filter((p) => p.actor.type === 'USER').map((p) => p.actor.id));

  protected readonly columns: TableColumn[] = [
    { key: 'createdAt', header: 'Criada em' },
    { key: 'debtor', header: 'Devedor' },
    { key: 'total', header: 'Total', align: 'end' },
    { key: 'plan', header: 'Forma de pagamento' },
    { key: 'actor', header: 'Negociador' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected plan(p: ProposalView): string {
    return planText({ installments: p.installments, downPayment: p.downPayment });
  }

  protected lapsed(p: ProposalView): boolean {
    return validityLapsed(p, this.today);
  }

  protected author(p: ProposalView): string {
    return actorText(p.actor.type, p.actor.id, this.auth.user()?.id ?? null, (id) => this.users.name(id));
  }

  protected violations(p: ProposalView): string {
    return p.authorityViolations.map(violationText).join(' · ');
  }
}
