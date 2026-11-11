import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Observable, catchError, debounceTime, forkJoin, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { nameMap } from '../../../../shared/data/name-map';
import { DebtorNames } from '../../../debt/data/debt.lookups';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { DocumentDisplay } from '../../../../shared/components/document-display/document-display';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { sameValue } from '../../../../shared/data/same-value';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { UrlCodec, syncFormWithUrl, urlParam } from '../../../../shared/data/url-state';
import { DocumentMask } from '../../../../shared/directives/document-mask';
import { PageRequest, PageResponse, emptyPage } from '../../../../shared/models/page';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../../shared/status/status';
import { isValidDocument, onlyDigits } from '../../../../shared/validation/document';
import { DataSubjectRequestsApi } from '../../data/compliance.api';
import { REQUEST_CHANNEL_LABELS, REQUEST_TYPE_LABELS, daysUntil, dueText } from '../../data/compliance.labels';
import { DataSubjectRequestView } from '../../data/compliance.models';
import { RequestForm } from '../request-form/request-form';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { DialogService } from '../../../../shared/dialog/dialog.service';

/** Filtro da fila; `document` só é usado no corpo de `POST /lookup` (nunca na URL). */
export interface RequestQueueFilter {
  status: string | null;
  type: string | null;
  overdue: boolean | null;
  /** Titular (devedor) vindo da ficha do devedor ou da solicitação: só o id, na URL (`?devedor=`). */
  debtorId: string | null;
  document: string | null;
  /** Documento digitado mas incompleto/ inválido: não consulta. */
  incomplete: boolean;
}

export function toQueueFilter(v: { status: string; type: string; overdue: boolean; document: string; debtor?: string }): RequestQueueFilter {
  const digits = onlyDigits(v.document);
  const complete = (digits.length === 11 || digits.length === 14) && isValidDocument(digits);
  return {
    status: v.status || null,
    type: v.type || null,
    overdue: v.overdue ? true : null,
    debtorId: v.debtor || null,
    document: complete ? digits : null,
    incomplete: digits.length > 0 && !complete,
  };
}

/** `?vencidas=true` (formato que os links de "Minhas pendências" já usam). */
const OVERDUE_PARAM: UrlCodec<boolean> = {
  param: 'vencidas',
  parse: (raw) => (raw === 'true' ? true : raw === 'false' ? false : undefined),
  format: (v) => (v ? 'true' : 'false'),
};

/** Fila de solicitações de titulares com prazo legal e atraso em destaque. */
@Component({
  selector: 'app-request-list',
  imports: [ReactiveFormsModule, RouterLink, DataTable, CellDef, DocumentDisplay, PageHeader, StatusChip, AppDatePipe, DocumentMask, Icon, NgbTooltip, ...FORM_FIELD],
  templateUrl: './request-list.html',
  styleUrl: '../../compliance.scss',
})
export class RequestList {
  private readonly api = inject(DataSubjectRequestsApi);
  private readonly dialog = inject(DialogService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  protected readonly typeLabels = REQUEST_TYPE_LABELS;
  protected readonly channelLabels = REQUEST_CHANNEL_LABELS;
  protected readonly types = Object.entries(REQUEST_TYPE_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly statuses = statusOptions('dataSubjectRequest');
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);

  private readonly fb = inject(FormBuilder).nonNullable;
  /**
   * Filtros na URL (`?situacao=&pedido=&vencidas=true`): `vencidas` vem de "Minhas pendências" e tudo se preserva ao
   * voltar. O CPF/CNPJ do titular nunca vai para a URL (dado pessoal).
   */
  protected readonly filters = syncFormWithUrl(this.fb.group({ status: [''], type: [''], overdue: [false], document: [''], debtor: [''] }), {
    status: urlParam.choice('situacao', this.statuses.map((s) => s.value)),
    type: urlParam.choice('pedido', this.types.map((t) => t.value)),
    overdue: OVERDUE_PARAM,
    debtor: urlParam.id('devedor'),
  });

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(300),
      map(() => toQueueFilter(this.filters.getRawValue())),
    ),
    { initialValue: toQueueFilter(this.filters.getRawValue()), equal: sameValue },
  );
  protected readonly incomplete = computed(() => this.filter().incomplete);
  protected readonly debtorId = computed(() => this.filter().debtorId);
  protected readonly canOpenDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  protected readonly debtorNames = nameMap(inject(DebtorNames), () => [this.canOpenDebtor() ? this.debtorId() : null]);
  protected readonly query = pagedQuery(this.filter, (f, page) => this.load(f, page), { url: true });

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
    { key: 'due', header: 'Prazo', info: 'compliance.requests.deadline' },
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

  /** Volta à fila completa (o filtro do titular vem de outra tela e não tem campo próprio). */
  protected clearDebtor(): void {
    this.filters.patchValue({ debtor: '' });
  }

  protected showOverdue(): void {
    this.filters.patchValue({ overdue: true, status: '' });
  }

  protected showStatus(status: string): void {
    this.filters.patchValue({ status, overdue: false });
  }

  protected open(): void {
    this.dialog
      .open<RequestForm, void, DataSubjectRequestView>(RequestForm, { size: 'lg' })
      .afterClosed()
      .subscribe((created) => created && void this.router.navigate(['/compliance/solicitacoes-titulares', created.id]));
  }

  private load(f: RequestQueueFilter, page: PageRequest): Observable<PageResponse<DataSubjectRequestView>> {
    // Pelo titular, basta o devedor visível (o servidor confere o escopo); a fila completa exige acesso a todos os
    // credores (o direito do titular alcança todos os seus dados).
    if (f.incomplete || (!this.allCreditors() && !f.debtorId)) {
      return of(emptyPage<DataSubjectRequestView>(page.size));
    }
    if (f.document) {
      return this.api.lookup(f.document, page);
    }
    return this.api.search({ status: f.status, type: f.type, overdue: f.overdue, debtorId: f.debtorId }, page);
  }
}
