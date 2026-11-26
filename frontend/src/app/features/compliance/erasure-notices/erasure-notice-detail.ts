import { Clipboard } from '@angular/cdk/clipboard';
import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { recordLabel } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NavigationHistory } from '../../../core/layout/breadcrumbs/navigation-history';
import { NotificationService } from '../../../core/notification/notification.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ErasureNoticesApi } from '../data/compliance.api';
import { ERASURE_NOTICE_CHANNEL_LABELS, ERASURE_NOTICE_ORIGIN_LABELS } from '../data/compliance.labels';
import { ErasureNoticeView, RegisterNoticeSent } from '../data/compliance.models';
import { RegisterSentDialog, RegisterSentDialogData } from './register-sent-dialog';

/** Aviso a um credor: títulos citados, texto pronto para copiar e o registro do envio. */
@Component({
  selector: 'app-erasure-notice-detail',
  imports: [RouterLink, PageHeader, StatusChip, EmptyState, Skeleton, AppDatePipe, Icon],
  templateUrl: './erasure-notice-detail.html',
  styleUrl: '../compliance.scss',
})
export class ErasureNoticeDetail {
  readonly id = input.required<string>();

  private readonly api = inject(ErasureNoticesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  protected readonly history = inject(NavigationHistory);
  private readonly clipboard = inject(Clipboard);

  protected readonly originLabels = ERASURE_NOTICE_ORIGIN_LABELS;
  protected readonly channelLabels = ERASURE_NOTICE_CHANNEL_LABELS;

  protected readonly notice = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.notice, undefined);
  /** Mesmas permissões da rota do credor (`/organizacao/credores/:id`). */
  protected readonly canOpenCreditor = computed(
    () => this.auth.hasPermission(Permission.ORGANIZATION_AREA_VIEW) && this.auth.hasPermission(Permission.CREDITOR_VIEW),
  );
  protected readonly canOpenDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));

  protected readonly crumb = recordLabel(() => {
    const n = this.current();
    return n ? `Aviso a ${n.creditorName ?? 'credor'}` : null;
  });

  protected copy(n: ErasureNoticeView): void {
    if (this.clipboard.copy(n.message)) {
      this.notifications.success('Texto copiado. Envie ao credor e depois registre o aviso.');
    } else {
      this.notifications.error('Não foi possível copiar. Selecione o texto e copie manualmente.');
    }
  }

  protected register(n: ErasureNoticeView): void {
    this.dialog
      .open<RegisterSentDialog, RegisterSentDialogData, RegisterNoticeSent>(RegisterSentDialog, {
        data: { creditorName: n.creditorName ?? 'credor', count: 1, latestErasedAt: n.erasedAt },
      })
      .afterClosed()
      .pipe(
        filter((body): body is RegisterNoticeSent => !!body),
        switchMap((body) => this.api.registerSent(n.id, body)),
      )
      .subscribe({
        next: (saved) => {
          this.notice.set(saved);
          this.notifications.success('Aviso registrado.');
        },
        error: (err: unknown) => {
          this.notifications.error(userMessage(toApiError(err)));
          this.notice.reload();
        },
      });
  }
}
