import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { RequestedTarget } from '../components/requested-target';
import { PaymentsApi } from '../data/payment.api';
import { METHOD_LABELS, ORIGIN_LABELS } from '../data/payment.labels';
import { PaymentView } from '../data/payment.models';

/**
 * Fila de aprovação dos pagamentos informados acima do limite. O servidor já omite os
 * pagamentos que o próprio revisor informou (segregação de funções); a tela explica.
 */
@Component({
  selector: 'app-review-queue',
  imports: [Icon, NgbTooltip, RouterLink, Amount, AppDatePipe, CellDef, DataTable, PageHeader, RequestedTarget],
  template: `
    <app-page-header title="Aprovação de pagamentos" subtitle="Pagamentos informados acima do limite da assessoria aguardando uma segunda pessoa." />
    <div class="alert alert-info d-flex gap-2 align-items-start" role="note" data-tour="review-segregation">
      <app-icon name="people" />
      <span>Os pagamentos que <strong>você</strong> informou não aparecem aqui: quem informa não aprova (o sistema recusa, inclusive para administradores). Confira o comprovante e <strong>para onde o valor vai</strong> (destino solicitado) antes de aprovar.</span>
    </div>
    <app-data-table
      caption="Pagamentos aguardando aprovação"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="check2-circle"
      emptyMessage="Nada aguardando a sua aprovação."
      emptyHint="Pagamentos informados acima do limite aparecem aqui assim que registrados."
    >
      <ng-template appCell="informedAt" [appCellOf]="query.rows()" let-p>{{ p.informedAt | appDate }}</ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-p>
        {{ debtors.name(p.debtorId) }}
        <div class="app-muted rq-small">{{ creditors.name(p.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-p>
        <app-amount [value]="p.amount" />
        <div class="app-muted rq-small">pago em {{ p.paidAt | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="origin" [appCellOf]="query.rows()" let-p>
        {{ methods[p.method] }}
        <div class="app-muted rq-small">{{ p.externalOrigin ? origins[p.externalOrigin] : '—' }}</div>
      </ng-template>
      <ng-template appCell="target" [appCellOf]="query.rows()" let-p>
        @if (p.requestedTarget) {
          <app-requested-target [target]="p.requestedTarget" />
        } @else {
          <span class="app-muted">—</span>
        }
      </ng-template>
      <ng-template appCell="informedBy" [appCellOf]="query.rows()" let-p>{{ users.name(p.informedBy) }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-p>
        @if (p.evidenceAvailable) {
          <button class="btn btn-sm btn-link" type="button" (click)="download(p)" [disabled]="downloading() === p.id" aria-label="Baixar comprovante" ngbTooltip="Baixar comprovante">
            <app-icon name="paperclip" />
          </button>
        }
        <a class="btn btn-outline-primary" [routerLink]="['/pagamentos', p.id]">Revisar</a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .rq-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class ReviewQueue {
  private readonly api = inject(PaymentsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly methods = METHOD_LABELS;
  protected readonly origins = ORIGIN_LABELS;
  protected readonly downloading = signal<string | null>(null);

  protected readonly query = pagedQuery(signal(null), (_, page) => this.api.reviewQueue(page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((p) => p.creditorId));
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((p) => p.debtorId), { batch: 100 });
  protected readonly users = nameMap(inject(UserNames), () => this.query.rows().map((p) => p.informedBy), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'informedAt', header: 'Informado em' },
    { key: 'debtor', header: 'Devedor e credor' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'origin', header: 'Meio e origem' },
    { key: 'target', header: 'Destino solicitado' },
    { key: 'informedBy', header: 'Informado por' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected download(p: PaymentView): void {
    this.downloading.set(p.id);
    this.api.downloadEvidence(p.id).subscribe({
      next: (name) => {
        this.downloading.set(null);
        this.notifications.success(`Comprovante baixado: ${name}`);
      },
      error: () => this.downloading.set(null),
    });
  }
}
