import { Clipboard } from '@angular/cdk/clipboard';
import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Observable, filter, map, of, startWith, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Permission } from '../../../core/auth/permission';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { sameValue } from '../../../shared/data/same-value';
import { syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { PageRequest, PageResponse, emptyPage } from '../../../shared/models/page';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { ErasureNoticesApi } from '../data/compliance.api';
import { ERASURE_NOTICE_CHANNEL_LABELS, ERASURE_NOTICE_ORIGIN_LABELS } from '../data/compliance.labels';
import { ErasureNoticeFilter, ErasureNoticeRemittance, ErasureNoticeSummary, RegisterNoticeSent } from '../data/compliance.models';
import { RegisterSentDialog, RegisterSentDialogData } from './register-sent-dialog';

interface NoticeQueueFilter {
  status: string | null;
  creditorId: string | null;
  originId: string | null;
}

/**
 * Avisos ao credor da anonimização do titular (LGPD art. 18 § 6º): credores a avisar, remessa pronta por credor
 * (decisão 1484) e o histórico. Filtros na URL (`?situacao=&credor=&origem=`); "Minhas pendências" abre aqui.
 */
@Component({
  selector: 'app-erasure-notice-list',
  imports: [ReactiveFormsModule, RouterLink, DataTable, EntityPicker, CellDef, PageHeader, StatusChip, Skeleton, AppDatePipe, Icon, NgbTooltip, ...FORM_FIELD],
  templateUrl: './erasure-notice-list.html',
  styleUrl: '../compliance.scss',
})
export class ErasureNoticeList {
  private readonly api = inject(ErasureNoticesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  private readonly clipboard = inject(Clipboard);

  protected readonly originLabels = ERASURE_NOTICE_ORIGIN_LABELS;
  protected readonly channelLabels = ERASURE_NOTICE_CHANNEL_LABELS;
  protected readonly statuses = statusOptions('creditorErasureNotice');
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly creditorSource = inject(CreditorLookup);
  /** O seletor busca credores (`CREDITOR_VIEW`); sem ela, o credor se escolhe pelos cartões. */
  protected readonly canPickCreditor = computed(() => this.auth.hasPermission(Permission.CREDITOR_VIEW));
  protected readonly filters = syncFormWithUrl(
    this.fb.group({ status: [''], creditor: this.fb.control<string[]>([]), origin: [''] }),
    {
      status: urlParam.choice('situacao', this.statuses.map((s) => s.value)),
      creditor: urlParam.ids('credor'),
      origin: urlParam.id('origem'),
    },
  );

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      map((): NoticeQueueFilter => {
        const v = this.filters.getRawValue();
        return { status: v.status || null, creditorId: v.creditor[0] ?? null, originId: v.origin || null };
      }),
    ),
    { initialValue: { status: null, creditorId: null, originId: null } as NoticeQueueFilter, equal: sameValue },
  );
  protected readonly creditorId = computed(() => this.filter().creditorId);
  protected readonly originId = computed(() => this.filter().originId);

  protected readonly query = pagedQuery(this.filter, (f, page) => this.load(f, page), { url: true });

  protected readonly pendingResource = rxResource({
    params: () => (this.allCreditors() ? true : undefined),
    stream: () => this.api.pendingByCreditor(),
  });
  protected readonly pending = valueOr(this.pendingResource, []);

  /** Remessa do credor escolhido (só quando o filtro não é "Credor avisado"). */
  protected readonly remittanceResource = rxResource({
    params: () => {
      const f = this.filter();
      return this.allCreditors() && f.creditorId && f.status !== 'NOTIFIED' ? f.creditorId : undefined;
    },
    stream: ({ params }) => this.api.remittance(params),
  });
  protected readonly remittance = valueOr(this.remittanceResource, undefined);

  /** Nome do credor filtrado: da remessa, dos pendentes ou da própria lista. */
  protected readonly creditorName = computed(() => {
    const id = this.creditorId();
    if (!id) {
      return null;
    }
    return (
      this.remittance()?.creditorName ??
      this.pending().find((p) => p.creditorId === id)?.creditorName ??
      this.query.rows().find((r) => r.creditorId === id)?.creditorName ??
      'credor'
    );
  });

  protected readonly columns: TableColumn[] = [
    { key: 'creditor', header: 'Credor' },
    { key: 'erasedAt', header: 'Dados eliminados em' },
    { key: 'origin', header: 'Origem' },
    { key: 'status', header: 'Situação' },
    { key: 'notifiedAt', header: 'Avisado em' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected chooseCreditor(creditorId: string): void {
    this.filters.patchValue({ creditor: [creditorId], status: 'PENDING', origin: '' });
  }

  protected clearCreditor(): void {
    this.filters.patchValue({ creditor: [] });
  }

  protected clearOrigin(): void {
    this.filters.patchValue({ origin: '' });
  }

  protected copy(text: string): void {
    if (this.clipboard.copy(text)) {
      this.notifications.success('Texto copiado. Envie ao credor e depois registre o aviso.');
    } else {
      this.notifications.error('Não foi possível copiar. Selecione o texto e copie manualmente.');
    }
  }

  protected registerRemittance(r: ErasureNoticeRemittance): void {
    this.dialog
      .open<RegisterSentDialog, RegisterSentDialogData, RegisterNoticeSent>(RegisterSentDialog, {
        data: { creditorName: r.creditorName, count: r.noticeIds.length, latestErasedAt: r.latestErasedAt },
      })
      .afterClosed()
      .pipe(
        filter((body): body is RegisterNoticeSent => !!body),
        switchMap((body) => this.api.registerSentBatch(r.noticeIds, body)),
      )
      .subscribe({
        next: (saved) => {
          this.notifications.success(saved.length === 1 ? 'Aviso registrado.' : `${saved.length} avisos registrados.`);
          this.reloadAll();
        },
        error: (err: unknown) => {
          this.notifications.error(userMessage(toApiError(err)));
          this.reloadAll();
        },
      });
  }

  private reloadAll(): void {
    this.query.reload();
    this.pendingResource.reload();
    this.remittanceResource.reload();
  }

  private load(f: NoticeQueueFilter, page: PageRequest): Observable<PageResponse<ErasureNoticeSummary>> {
    if (!this.allCreditors()) {
      return of(emptyPage<ErasureNoticeSummary>(page.size));
    }
    const params: ErasureNoticeFilter = { status: f.status, creditorId: f.creditorId, originId: f.originId };
    return this.api.search(params, page);
  }
}
