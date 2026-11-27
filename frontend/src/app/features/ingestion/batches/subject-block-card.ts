import { Clipboard } from '@angular/cdk/clipboard';
import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, USER_NAMES_PERMISSIONS, UserNames } from '../../admin/data/user-names';
import { ERASURE_NOTICE_CHANNEL_LABELS } from '../../compliance/data/compliance.labels';
import { RegisterNoticeSent } from '../../compliance/data/compliance.models';
import { RegisterSentDialog, RegisterSentDialogData } from '../../compliance/erasure-notices/register-sent-dialog';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ImportBatchesApi } from '../data/ingestion.api';
import { ImportBatchView } from '../data/ingestion.models';

/**
 * Lote bloqueado por titular com dados eliminados (LGPD): por que o arquivo não pode ser usado, as linhas do titular
 * (referência mascarada), o pedido de novo arquivo pronto para copiar e enviar ao credor e o registro do envio
 * (`IMPORT_EXECUTE`). Mostra também a situação do aviso da eliminação ao credor (compliance).
 */
@Component({
  selector: 'app-subject-block-card',
  imports: [RouterLink, Icon, Skeleton, AppDatePipe],
  template: `
    <div class="alert alert-warning d-flex gap-2 align-items-start" role="note" data-tour="batch-subject-block">
      <app-icon name="shield-exclamation" />
      <span>
        <strong>Linhas recusadas: o arquivo traz um titular cujos dados foram eliminados (LGPD).</strong>
        @if (batch().mode === 'IMPORT' && batch().counters.titlesCreated + batch().counters.titlesUpdated > 0) {
          As demais linhas foram importadas normalmente.
        }
        @if (batch().fileDeletedAt) {
          O arquivo foi apagado e não pode ser reprocessado nem conferido de novo.
        } @else {
          O arquivo será apagado ao fim do processamento e não poderá ser reprocessado nem conferido de novo.
        }
        Peça ao credor um novo arquivo sem as linhas desse titular; o que já foi importado não se repete.
      </span>
    </div>

    @if (block.isLoading() && !view()) {
      <app-skeleton variant="card" [rows]="4" />
    } @else if (block.status() === 'error') {
      <div class="alert alert-danger d-flex flex-wrap gap-2 align-items-center" role="alert">
        <app-icon name="cloud-slash" />
        <span class="flex-grow-1">Não foi possível carregar o pedido de novo arquivo.</span>
        <button class="btn btn-sm btn-outline-danger" type="button" (click)="block.reload()">Tentar novamente</button>
      </div>
    } @else if (view(); as v) {
      <section class="app-card" aria-labelledby="sb-title">
        <div class="d-flex flex-wrap gap-2 align-items-start mb-2">
          <div class="flex-grow-1">
            <h2 class="app-card__title mb-1" id="sb-title">Pedido de novo arquivo ao credor</h2>
            <p class="app-muted small mb-0">
              Protocolo <span class="app-mono">{{ v.protocol }}</span> · {{ v.linesTotal }}
              {{ v.linesTotal === 1 ? 'linha' : 'linhas' }} do titular · recusado em {{ v.blockedAt | appDate }}
            </p>
          </div>
          <button class="btn btn-outline-secondary" type="button" (click)="copy(v.message)">
            <app-icon name="clipboard" />
            Copiar texto
          </button>
          @if (!v.request && canExecute() && !v.inProgress) {
            <button class="btn btn-primary" type="button" (click)="register()">
              <app-icon name="check2-circle" />
              Registrar pedido enviado
            </button>
          }
        </div>

        @if (v.inProgress) {
          <div class="alert alert-info d-flex gap-2 align-items-start sb-small" role="status">
            <app-icon name="hourglass-split" />
            <span>O lote ainda está em processamento: mais linhas desse titular podem aparecer. Envie o pedido ao credor
              quando o processamento terminar.</span>
          </div>
        }
        <dl class="app-definition-list sb-small">
          <dt>Pedido ao credor</dt>
          <dd>
            @if (v.request; as r) {
              Enviado em {{ r.sentAt | appDate }} · {{ channelLabels[r.channel] }}
              @if (canViewUsers()) {
                · registrado por {{ users.name(r.sentBy) }}
              }
            } @else {
              <span class="app-muted">Ainda não enviado</span>
            }
          </dd>
          <dt>Aviso da eliminação ao credor</dt>
          <dd>
            @if (v.notice; as n) {
              @if (n.status === 'NOTIFIED') {
                Credor avisado em {{ n.notifiedAt | appDate }}
              } @else {
                <span class="app-muted">Ainda não avisado</span>
              }
              @if (canOpenNotices()) {
                · <a [routerLink]="['/compliance/avisos-credores', n.id]">abrir o aviso</a>
              }
            } @else {
              <span class="app-muted">O credor não tinha título desse titular conservado</span>
            }
          </dd>
        </dl>

        <div class="table-responsive sb-lines mb-3" tabindex="0" role="region" aria-label="Linhas do arquivo com o titular">
          <table class="table table-sm align-middle mb-0">
            <caption class="visually-hidden">Linhas do arquivo com o titular</caption>
            <thead>
              <tr>
                <th scope="col" class="text-end">Linha</th>
                <th scope="col">Título</th>
              </tr>
            </thead>
            <tbody>
              @for (l of v.lines; track l.rowNumber) {
                <tr>
                  <td class="text-end app-numeric">{{ l.rowNumber }}</td>
                  <td class="app-mono">{{ l.reference }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="2" class="app-muted">As linhas não ficaram registradas; o texto do pedido identifica o arquivo pelo protocolo.</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (v.linesTotal > v.lines.length) {
          <p class="app-muted small">E mais {{ v.linesTotal - v.lines.length }} linhas (o texto informa a quantidade).</p>
        }
        <h3 class="h6" id="sb-text">Texto do pedido</h3>
        <pre class="sb-text" tabindex="0" role="region" aria-labelledby="sb-text">{{ v.message }}</pre>
      </section>
    }
  `,
  styles: `
    .sb-lines {
      max-height: 16rem;
      overflow: auto;
    }
    .sb-lines thead th {
      position: sticky;
      top: 0;
      background: var(--bs-body-bg);
    }
    .sb-small {
      font-size: var(--app-text-sm);
    }
    .sb-text {
      margin: 0;
      max-height: 20rem;
      overflow: auto;
      padding: 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--app-surface-raised);
      font-family: inherit;
      font-size: 0.875rem;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
  `,
})
export class SubjectBlockCard {
  readonly batch = input.required<ImportBatchView>();

  private readonly api = inject(ImportBatchesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  private readonly clipboard = inject(Clipboard);

  protected readonly channelLabels = ERASURE_NOTICE_CHANNEL_LABELS;
  protected readonly canExecute = computed(() => this.auth.hasPermission(Permission.IMPORT_EXECUTE));
  protected readonly canViewUsers = computed(() => this.auth.hasAnyPermission(USER_NAMES_PERMISSIONS));
  /** Mesmas permissões da rota do aviso (`/compliance/avisos-credores/:id`). */
  protected readonly canOpenNotices = computed(
    () => this.auth.hasPermission(Permission.DATA_SUBJECT_REQUEST_MANAGE) && (this.auth.user()?.allCreditors ?? false),
  );

  /** Recarrega quando o lote muda (o processamento ainda pode achar linhas do titular até encerrar). */
  protected readonly block = rxResource({
    params: () => ({ id: this.batch().id, status: this.batch().status, rows: this.batch().lastProcessedRow, deleted: this.batch().fileDeletedAt }),
    stream: ({ params }) => this.api.subjectBlock(params.id),
  });
  protected readonly view = valueOr(this.block, undefined);
  private readonly creditors = nameMap(inject(CreditorNames), () => [this.batch().creditorId]);
  protected readonly users = nameMap(inject(UserNames), () => (this.canViewUsers() ? [this.view()?.request?.sentBy] : []), {
    batch: USER_NAMES_BATCH,
  });

  protected copy(text: string): void {
    if (this.clipboard.copy(text)) {
      this.notifications.success('Texto copiado. Envie ao credor e depois registre o pedido.');
    } else {
      this.notifications.error('Não foi possível copiar. Selecione o texto e copie manualmente.');
    }
  }

  protected register(): void {
    const v = this.view();
    if (!v) {
      return;
    }
    this.dialog
      .open<RegisterSentDialog, RegisterSentDialogData, RegisterNoticeSent>(RegisterSentDialog, {
        data: {
          creditorName: this.creditors.name(this.batch().creditorId) || 'o credor',
          count: 1,
          latestErasedAt: v.blockedAt,
          title: 'Registrar pedido de novo arquivo',
          sentLabel: 'Enviado em',
          texts: { what: 'o pedido', missing: 'Informe quando o pedido foi enviado.', floor: 'ao bloqueio do arquivo' },
          note: 'O registro é a evidência de que o credor recebeu o pedido. É feito uma vez só e não pode ser corrigido.',
          confirmLabel: 'Registrar pedido',
        },
      })
      .afterClosed()
      .pipe(
        filter((body): body is RegisterNoticeSent => !!body),
        switchMap((body) => this.api.registerRequestSent(v.batchId, { sentAt: body.notifiedAt, channel: body.channel })),
      )
      .subscribe({
        next: (saved) => {
          this.block.set(saved);
          this.notifications.success('Pedido de novo arquivo registrado.');
        },
        error: (err: unknown) => {
          this.notifications.error(userMessage(toApiError(err)));
          this.block.reload();
        },
      });
  }
}
