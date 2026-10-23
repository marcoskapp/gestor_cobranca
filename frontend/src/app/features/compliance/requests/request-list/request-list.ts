import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Observable, catchError, debounceTime, forkJoin, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { DocumentDisplay } from '../../../../shared/components/document-display/document-display';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { DocumentMask } from '../../../../shared/directives/document-mask';
import { PageRequest, PageResponse, emptyPage } from '../../../../shared/models/page';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../../shared/status/status';
import { isValidDocument, onlyDigits } from '../../../../shared/validation/document';
import { DataSubjectRequestsApi } from '../../data/compliance.api';
import { REQUEST_CHANNEL_LABELS, REQUEST_TYPE_LABELS, daysUntil, dueText } from '../../data/compliance.labels';
import { DataSubjectRequestView } from '../../data/compliance.models';
import { RequestForm } from '../request-form/request-form';

/** Filtro da fila; `document` só é usado no corpo de `POST /lookup` (nunca na URL). */
export interface RequestQueueFilter {
  status: string | null;
  type: string | null;
  overdue: boolean | null;
  document: string | null;
  /** Documento digitado mas incompleto/ inválido: não consulta. */
  incomplete: boolean;
}

export function toQueueFilter(v: { status: string; type: string; overdue: boolean; document: string }): RequestQueueFilter {
  const digits = onlyDigits(v.document);
  const complete = (digits.length === 11 || digits.length === 14) && isValidDocument(digits);
  return {
    status: v.status || null,
    type: v.type || null,
    overdue: v.overdue ? true : null,
    document: complete ? digits : null,
    incomplete: digits.length > 0 && !complete,
  };
}

/** Fila de solicitações de titulares com prazo legal e atraso em destaque. */
@Component({
  selector: 'app-request-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTooltipModule,
    DataTable,
    CellDef,
    DocumentDisplay,
    PageHeader,
    StatusChip,
    AppDatePipe,
    DocumentMask,
  ],
  templateUrl: './request-list.html',
  styleUrl: '../../compliance.scss',
})
export class RequestList {
  private readonly api = inject(DataSubjectRequestsApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  protected readonly typeLabels = REQUEST_TYPE_LABELS;
  protected readonly channelLabels = REQUEST_CHANNEL_LABELS;
  protected readonly types = Object.entries(REQUEST_TYPE_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly statuses = statusOptions('dataSubjectRequest');
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ status: [''], type: [''], overdue: [false], document: [''] });

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(300),
      map(() => toQueueFilter(this.filters.getRawValue())),
    ),
    { initialValue: toQueueFilter({ status: '', type: '', overdue: false, document: '' }) },
  );
  protected readonly incomplete = computed(() => this.filter().incomplete);
  protected readonly query = pagedQuery(this.filter, (f, page) => this.load(f, page));

  /** Indicadores da fila (totais do servidor; uma consulta de 1 item por indicador). */
  protected readonly counters = rxResource({
    params: () => (this.allCreditors() ? true : undefined),
    stream: () => {
      const total = (filter: { status?: string; overdue?: boolean }) =>
        this.api.search(filter, { page: 0, size: 1 }).pipe(
          map((p) => p.totalElements),
          catchError(() => of(null)),
        );
      return forkJoin({
        overdue: total({ overdue: true }),
        received: total({ status: 'RECEIVED' }),
        verified: total({ status: 'IDENTITY_VERIFIED' }),
        inProgress: total({ status: 'IN_PROGRESS' }),
      });
    },
  });

  protected readonly columns: TableColumn[] = [
    { key: 'due', header: 'Prazo' },
    { key: 'type', header: 'Pedido' },
    { key: 'requester', header: 'Requerente' },
    { key: 'receivedAt', header: 'Recebida em' },
    { key: 'channel', header: 'Canal' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected days(r: DataSubjectRequestView): number {
    return daysUntil(r.dueAt);
  }

  protected due(r: DataSubjectRequestView): string {
    return dueText(this.days(r), r.overdue);
  }

  protected isOpen(r: DataSubjectRequestView): boolean {
    return r.status !== 'COMPLETED' && r.status !== 'REJECTED';
  }

  protected showOverdue(): void {
    this.filters.patchValue({ overdue: true, status: '' });
  }

  protected showStatus(status: string): void {
    this.filters.patchValue({ status, overdue: false });
  }

  protected open(): void {
    this.dialog
      .open<RequestForm, void, DataSubjectRequestView>(RequestForm, { width: '720px' })
      .afterClosed()
      .subscribe((created) => created && void this.router.navigate(['/compliance/solicitacoes-titulares', created.id]));
  }

  private load(f: RequestQueueFilter, page: PageRequest): Observable<PageResponse<DataSubjectRequestView>> {
    // A fila completa exige acesso a todos os credores (o direito do titular alcança todos os seus dados).
    if (f.incomplete || !this.allCreditors()) {
      return of(emptyPage<DataSubjectRequestView>(page.size));
    }
    if (f.document) {
      return this.api.lookup(f.document, page);
    }
    return this.api.search({ status: f.status, type: f.type, overdue: f.overdue }, page);
  }
}
