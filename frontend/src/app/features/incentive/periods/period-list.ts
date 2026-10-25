import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { CommissionPeriodsApi } from '../data/incentive.api';
import { PeriodView } from '../data/incentive.models';
import { canClosePeriod, monthLabel } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';

const TRIGGER_LABELS: Record<string, string> = { AUTO: 'automático', MANUAL: 'manual', SCHEDULED: 'automático' };

/**
 * Períodos de apuração (mensais): o fechamento libera os lançamentos vencidos, aplica o plano e
 * gera os extratos. Só depois da carência do último dia; períodos são fechados em ordem.
 */
@Component({
  selector: 'app-incentive-period-list',
  imports: [RouterLink, NgbTooltip, AppDatePipe, CellDef, DataTable, Icon, PageHeader, StatusChip],
  template: `
    <app-page-header title="Períodos de apuração" subtitle="Fechamento mensal da remuneração variável: gera os extratos para revisão, contestação e aprovação." />
    <div class="alert alert-secondary d-flex align-items-start gap-2" role="note" data-tour="period-rules">
      <app-icon name="info-circle" />
      <span>O período fecha sozinho quando termina a carência do último dia (job diário). Aqui você pode fechá-lo assim que o fechamento for permitido. Quem fecha o período não aprova os extratos dele.</span>
    </div>
    @if (error()) {
      <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
    }
    <app-data-table
      caption="Períodos de apuração"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="calendar-check"
      emptyMessage="Nenhum período de apuração ainda."
      emptyHint="O período é criado com o primeiro pagamento creditado a um negociador no mês."
    >
      <ng-template appCell="period" [appCellOf]="rows()" let-p>
        <strong>{{ month(p.period) }}</strong>
        <div class="app-muted in-small">{{ p.start | appDate: 'date' }} a {{ p.end | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-p><app-status-chip domain="commissionPeriod" [status]="p.status" /></ng-template>
      <ng-template appCell="close" [appCellOf]="rows()" let-p>
        @if (p.status === 'CLOSED') {
          {{ p.closedAt | appDate }}
          <div class="app-muted in-small">{{ trigger(p.closeTrigger) }}{{ p.closedBy ? ' · ' + users.name(p.closedBy) : '' }}</div>
        } @else if (p.closeableFrom) {
          a partir de {{ p.closeableFrom | appDate: 'date' }}
        } @else {
          —
        }
      </ng-template>
      <ng-template appCell="statements" [appCellOf]="rows()" let-p>{{ p.statements ?? '—' }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-p>
        @if (closable(p)) {
          <button type="button" class="btn btn-sm btn-primary" (click)="close(p)" [disabled]="busy()">Fechar período</button>
        }
        @if (p.status === 'CLOSED') {
          <a class="btn btn-sm btn-outline-secondary border-0" routerLink="/remuneracao/extratos" [queryParams]="{ periodo: p.period }" [attr.aria-label]="'Ver os extratos de ' + month(p.period)" ngbTooltip="Ver extratos">
            <app-icon name="receipt" />
          </a>
        }
      </ng-template>
    </app-data-table>
  `,
  styleUrl: '../incentive.scss',
})
export class PeriodList {
  private readonly api = inject(CommissionPeriodsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly month = monthLabel;
  protected readonly list = rxResource({ stream: () => this.api.list() });
  private readonly raw = valueOr(this.list, []);
  protected readonly rows = computed(() => [...this.raw()].sort((a, b) => b.period.localeCompare(a.period)));
  protected readonly users = nameMap(inject(UserNames), () => this.rows().map((p) => p.closedBy), { batch: USER_NAMES_BATCH });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly today = zonedNow().date;

  protected readonly columns: TableColumn[] = [
    { key: 'period', header: 'Período' },
    { key: 'status', header: 'Situação' },
    { key: 'close', header: 'Fechamento' },
    { key: 'statements', header: 'Extratos', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected closable(p: PeriodView): boolean {
    return canClosePeriod(p, this.today);
  }

  protected trigger(code: string | null): string {
    return code ? (TRIGGER_LABELS[code] ?? code.toLowerCase()) : '';
  }

  protected close(p: PeriodView): void {
    this.confirm
      .confirm({
        title: `Fechar ${monthLabel(p.period)}`,
        message: 'Libera os lançamentos com carência vencida, aplica a versão vigente de cada plano (metas, deflatores, pool e tetos) e gera um extrato por negociador para revisão. O período não reabre: correções entram como ajuste.',
        confirmLabel: 'Fechar período',
        details: [
          { label: 'Período', value: `${p.start.split('-').reverse().join('/')} a ${p.end.split('-').reverse().join('/')}` },
          { label: 'Fechamento permitido desde', value: p.closeableFrom ? p.closeableFrom.split('-').reverse().join('/') : '—' },
        ],
        warning: 'Você não poderá aprovar os extratos deste período (segregação de funções).',
        confirmationText: 'FECHAR',
      })
      .subscribe(() => {
        this.busy.set(true);
        this.error.set(null);
        this.api
          .close(p.period)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: (closed) => {
              this.notifications.success(`${monthLabel(closed.period)} fechado: ${closed.statements ?? 0} extrato(s) gerado(s).`);
              this.list.reload();
            },
            error: (err: unknown) => this.error.set(incentiveError(err)),
          });
      });
  }
}
