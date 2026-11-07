import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { nameMap } from '../../../shared/data/name-map';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { DisputesApi } from '../data/collection.api';
import { MAIL_QUARANTINE_REASON_LABELS } from '../data/collection.labels';
import { MailReplyView } from '../data/collection.models';
import { protocolOf } from './dispute.rules';

/**
 * Quarentena dos e-mails do credor (gestão): o que falhou em alguma verificação — remetente fora do cadastro,
 * autenticação, vírus, excesso, tamanho — fica aqui, nunca é descartado em silêncio. Conferido o original, a gestão
 * libera para o analista (motivos liberáveis) ou descarta com o motivo.
 */
@Component({
  selector: 'app-dispute-mail-quarantine',
  imports: [RouterLink, EmptyState, Icon, AppDatePipe],
  template: `
    <section class="app-card" aria-labelledby="dq-title" data-tour="dispute-config-mail-quarantine">
      <h2 class="app-card__title" id="dq-title">E-mails do credor em quarentena</h2>
      <p class="app-muted">
        Respostas por e-mail que não passaram nas verificações. Baixe o original para conferir; libere para o analista quando for seguro (falha de autenticação, vírus, e-mail
        ilegível e contestação encerrada não são liberados) ou descarte com o motivo.
      </p>
      @if (list.error()) {
        <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a quarentena.">
          <button class="btn btn-outline-primary" type="button" (click)="list.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else {
        <ul class="list-unstyled dq-rows">
          @for (r of rows(); track r.id) {
            <li>
              <div class="dq-head">
                <a [routerLink]="['/cobranca/contestacoes', r.disputeId]">Contestação {{ protocol(r.disputeId) }}</a>
                <span class="app-muted">{{ creditors.name(r.creditorId) }} · {{ r.receivedAt | appDate }}</span>
              </div>
              <div class="dq-reason"><app-icon name="shield-exclamation" /> {{ r.quarantineReason ? reasons[r.quarantineReason] : '—' }}</div>
              <div class="app-muted">De {{ r.sender }} · {{ r.subject || '(sem assunto)' }} · {{ r.attachments }} anexo(s)</div>
              <div class="d-flex flex-wrap gap-2 mt-1">
                <button class="btn btn-sm btn-outline-secondary" type="button" (click)="original(r)" aria-label="Baixar o e-mail original (acesso registrado)">
                  <app-icon name="download" />
                  Original
                </button>
                @if (r.releasable) {
                  <button class="btn btn-sm btn-outline-primary" type="button" (click)="release(r)" [disabled]="busy()">Liberar para o analista</button>
                }
                <button class="btn btn-sm btn-outline-danger" type="button" (click)="dismiss(r)" [disabled]="busy()">Descartar</button>
              </div>
            </li>
          } @empty {
            <li class="app-muted">Nenhum e-mail em quarentena.</li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    .dq-rows {
      display: grid;
      gap: 12px;
      margin: 0;
    }
    .dq-head {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 12px;
      align-items: baseline;
    }
    .dq-reason {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
  `,
})
export class DisputeMailQuarantine {
  private readonly api = inject(DisputesApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly reasons = MAIL_QUARANTINE_REASON_LABELS;
  protected readonly busy = signal(false);

  protected readonly list = rxResource({ stream: () => this.api.mailQuarantine({ page: 0, size: 100 }) });
  protected readonly rows = () => (this.list.hasValue() ? this.list.value().content : []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((r) => r.creditorId));

  protected protocol(id: string): string {
    return protocolOf(id);
  }

  protected original(r: MailReplyView): void {
    this.api.downloadMailOriginal(r.disputeId, r).subscribe();
  }

  protected release(r: MailReplyView): void {
    this.confirm
      .confirm({
        title: 'Liberar e-mail para o analista',
        message: 'Confira o original antes. Liberado, o e-mail aguarda a confirmação do analista e os anexos passam pela verificação de segurança.',
        confirmLabel: 'Liberar',
        reason: 'required',
        reasonLabel: 'Por que é seguro liberar',
      })
      .subscribe((note) => this.run(r, () => this.api.releaseMailReply(r.disputeId, r.id, note), 'E-mail liberado para o analista.'));
  }

  protected dismiss(r: MailReplyView): void {
    this.confirm
      .confirm({
        title: 'Descartar e-mail do credor',
        message: 'O e-mail sai da quarentena sem virar resposta do credor. O original continua guardado para a cadeia de custódia.',
        confirmLabel: 'Descartar',
        destructive: true,
        reason: 'required',
      })
      .subscribe((note) => this.run(r, () => this.api.dismissMailReply(r.disputeId, r.id, note), 'E-mail descartado.'));
  }

  private run(_: MailReplyView, call: () => ReturnType<DisputesApi['dismissMailReply']>, message: string): void {
    this.busy.set(true);
    call()
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe(() => {
        this.notifications.success(message);
        this.list.reload();
      });
  }
}
