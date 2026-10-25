import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { QueuesApi } from '../data/collection.api';
import { ORDERING_LABELS, STRATEGY_LABELS } from '../data/collection.labels';
import { QueueNames, TeamLookup } from '../data/collection.lookups';
import { QueueView } from '../data/collection.models';
import { DistributeDialog, RedistributeDialog } from './distribution-dialogs';
import { QueueForm } from './queue-form';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { DialogService } from '../../../shared/dialog/dialog.service';

/** Texto dos critérios de faixa ("31 a 90 dias", "a partir de R$ 500,00"). */
export function rangeText(min: number | null, max: number | null, format: (v: number) => string): string | null {
  if (min === null && max === null) {
    return null;
  }
  if (min !== null && max !== null) {
    return `${format(min)} a ${format(max)}`;
  }
  return min !== null ? `a partir de ${format(min)}` : `até ${format(max!)}`;
}

/** Critérios da fila em uma linha, sem os ids (credor/carteira resolvidos pela tela). */
export function criteriaText(q: QueueView): string {
  const parts = [
    rangeText(q.minDaysOverdue, q.maxDaysOverdue, (v) => `${v} dias`),
    rangeText(q.minBalance, q.maxBalance, (v) => formatAmount(v)),
    rangeText(q.minScore, q.maxScore, (v) => `score ${v}`),
  ].filter((p): p is string => !!p);
  return parts.length ? parts.join(' · ') : 'Qualquer atraso, saldo e score';
}

/**
 * Filas de cobrança (F07): o caso pertence à primeira fila ativa, por prioridade, cujos critérios
 * satisfaz. Supervisores distribuem em lote e redistribuem casos de um operador.
 */
@Component({
  selector: 'app-queue-list',
  imports: [CellDef, DataTable, PageHeader, StatusChip, Icon, NgbTooltip],
  template: `
    <app-page-header title="Filas de cobrança" subtitle="Critérios, prioridade, ordem de entrega e distribuição dos casos entre os operadores.">
      @if (canAssign()) {
        <button class="btn btn-outline-primary" type="button" (click)="redistribute()">
          <app-icon name="arrow-left-right" />
          Redistribuir casos
        </button>
      }
      @if (canManage()) {
        <button class="btn btn-outline-primary" type="button" (click)="reroute()" ngbTooltip="Reavalia a fila de todos os casos ativos">
          <app-icon name="signpost-split" />
          Reprocessar roteamento
        </button>
        <button class="btn btn-primary" type="button" (click)="edit(null)">
          <app-icon name="plus-lg" />
          Nova fila
        </button>
      }
    </app-page-header>

    <app-data-table
      caption="Filas de cobrança"
      [rows]="rows()"
      [columns]="columns"
      [loading]="queues.isLoading()"
      [failed]="queues.status() === 'error'"
      (retry)="queues.reload()"
      emptyIcon="view-list"
      emptyMessage="Nenhuma fila cadastrada."
      emptyHint="Sem filas, os casos ficam sem fila e só chegam aos operadores por atribuição manual."
    >
      <ng-template appCell="priority" [appCellOf]="rows()" let-q><span class="app-numeric">{{ q.priority }}</span></ng-template>
      <ng-template appCell="name" [appCellOf]="rows()" let-q>
        <strong>{{ q.name }}</strong>&ngsp;<span class="app-muted app-mono">{{ q.code }}</span>
        @if (q.description) {
          <div class="app-muted">{{ q.description }}</div>
        }
      </ng-template>
      <ng-template appCell="criteria" [appCellOf]="rows()" let-q>
        {{ q.creditorId ? creditors.name(q.creditorId) : 'Todos os credores' }}{{ q.portfolioId ? ' · ' + portfolios.name(q.portfolioId) : '' }}
        <div class="app-muted">{{ criteria(q) }}</div>
      </ng-template>
      <ng-template appCell="delivery" [appCellOf]="rows()" let-q>
        {{ orderings[q.ordering] }}
        <div class="app-muted">{{ strategies[q.strategy] }}{{ q.maxCasesPerUser ? ' · até ' + q.maxCasesPerUser + ' por operador' : '' }}</div>
      </ng-template>
      <ng-template appCell="teams" [appCellOf]="rows()" let-q>{{ q.teamIds.length ? teamNames(q) : 'Qualquer operador' }}</ng-template>
      <ng-template appCell="active" [appCellOf]="rows()" let-q><app-status-chip domain="active" [status]="q.active" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-q>
        @if (canAssign() && q.active) {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="distribute(q)" [attr.aria-label]="'Distribuir fila ' + q.name" ngbTooltip="Situação e distribuição">
            <app-icon name="person-plus" />
          </button>
        }
        @if (canManage()) {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="edit(q)" [attr.aria-label]="'Editar fila ' + q.name" ngbTooltip="Editar">
            <app-icon name="pencil" />
          </button>
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="toggle(q)" [attr.aria-label]="(q.active ? 'Desativar fila ' : 'Ativar fila ') + q.name" [ngbTooltip]="q.active ? 'Desativar' : 'Ativar'">
            <app-icon [name]="q.active ? 'toggle-on' : 'toggle-off'" />
          </button>
        }
      </ng-template>
    </app-data-table>
  `,
})
export class QueueList {
  private readonly api = inject(QueuesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly queueNames = inject(QueueNames);
  private readonly teamLookup = inject(TeamLookup);

  protected readonly orderings = ORDERING_LABELS;
  protected readonly strategies = STRATEGY_LABELS;
  protected readonly criteria = criteriaText;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.QUEUE_MANAGE));
  protected readonly canAssign = computed(() => this.auth.hasPermission(Permission.CASE_ASSIGN));

  protected readonly queues = rxResource({ stream: () => this.api.list() });
  protected readonly rows = valueOr(this.queues, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((q) => q.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.rows().map((q) => q.portfolioId));
  protected readonly teams = nameMap(this.teamLookup, () => this.rows().flatMap((q) => q.teamIds));

  protected readonly columns: TableColumn[] = [
    { key: 'priority', header: 'Prioridade', align: 'end' },
    { key: 'name', header: 'Fila' },
    { key: 'criteria', header: 'Critérios' },
    { key: 'delivery', header: 'Entrega' },
    { key: 'teams', header: 'Equipes' },
    { key: 'active', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected teamNames(q: QueueView): string {
    return q.teamIds.map((id) => this.teams.name(id)).join(', ');
  }

  protected edit(queue: QueueView | null): void {
    this.dialog
      .open<QueueForm, QueueView | null, QueueView>(QueueForm, { data: queue, size: 'lg' })
      .afterClosed()
      .subscribe((saved) => saved && this.changed());
  }

  protected toggle(queue: QueueView): void {
    this.confirm
      .confirm({
        title: queue.active ? 'Desativar fila' : 'Ativar fila',
        message: queue.active
          ? 'Os casos desta fila são reencaminhados para a próxima fila compatível (ou ficam sem fila).'
          : 'Casos compatíveis passam a entrar nesta fila conforme a prioridade.',
        confirmLabel: queue.active ? 'Desativar' : 'Ativar',
        destructive: queue.active,
        details: [
          { label: 'Fila', value: queue.name },
          { label: 'Prioridade', value: String(queue.priority) },
        ],
      })
      .subscribe(() =>
        this.api.setActive(queue.id, !queue.active).subscribe(() => {
          this.notifications.success(queue.active ? 'Fila desativada.' : 'Fila ativada.');
          this.changed();
        }),
      );
  }

  protected reroute(): void {
    this.confirm
      .confirm({
        title: 'Reprocessar roteamento',
        message: 'Todos os casos ativos são reavaliados contra as filas, em segundo plano. Use após mudanças em lote nas carteiras.',
        confirmLabel: 'Reprocessar',
      })
      .subscribe(() => this.api.reroute().subscribe(() => this.notifications.success('Roteamento em reprocessamento.')));
  }

  protected distribute(queue: QueueView): void {
    this.dialog.open(DistributeDialog, { data: queue });
  }

  protected redistribute(): void {
    this.dialog.open(RedistributeDialog);
  }

  private changed(): void {
    this.queueNames.invalidate();
    this.queues.reload();
  }
}
