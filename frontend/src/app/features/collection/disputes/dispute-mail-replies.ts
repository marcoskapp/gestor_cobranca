import { Component, Injectable, computed, effect, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { Icon } from '../../../shared/components/icon/icon';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AppValidators } from '../../../shared/validation/validators';
import { DisputesApi } from '../data/collection.api';
import { CREDITOR_POSITION_LABELS, MAIL_QUARANTINE_REASON_LABELS } from '../data/collection.labels';
import { CreditorPosition, CreditorResponseView, MailReplyView } from '../data/collection.models';

const NOTE_MAX = 4000;

export interface MailReplyConfirmData {
  disputeId: string;
  reply: MailReplyView;
}

/**
 * Confirmação da posição do credor a partir do e-mail recebido: o analista lê o texto (já sem HTML), escolhe a posição
 * e resume. Só então a resposta do credor é registrada (origem e-mail) e o pedido deixa de aguardar o credor.
 */
@Component({
  selector: 'app-mail-reply-confirm-dialog',
  imports: [ReactiveFormsModule, AppDatePipe, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Confirmar a posição do credor</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <p class="app-muted mb-2">E-mail de {{ data.reply.sender }} em {{ data.reply.receivedAt | appDate }} (pedido {{ data.reply.requestSequence }}).</p>
        <blockquote class="mr-quote" tabindex="0" aria-label="Texto do e-mail recebido do credor">{{ data.reply.text || '(sem texto)' }}</blockquote>
        <app-field class="mb-3" label="Posição do credor">
          <select appInput formControlName="position" required ngbAutofocus>
            <option value="" disabled>Selecione</option>
            @for (p of positions; track p.value) {
              <option [ngValue]="p.value">{{ p.label }}</option>
            }
          </select>
        </app-field>
        <app-field label="Resumo da resposta" [hintEnd]="form.controls.note.value.length + ' / ' + noteMax" error="Resuma o que o credor respondeu.">
          <textarea appInput formControlName="note" rows="4" [maxlength]="noteMax" required></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="busy()">Confirmar resposta</button>
      </div>
    </form>
  `,
  styles: `
    .mr-quote {
      max-height: 240px;
      overflow: auto;
      margin: 0 0 12px;
      padding: 8px 12px;
      border-left: 3px solid var(--app-border);
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
  `,
})
export class MailReplyConfirmDialog {
  protected readonly data = inject<MailReplyConfirmData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CreditorResponseView>>(DialogRef);
  private readonly api = inject(DisputesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly positions = Object.entries(CREDITOR_POSITION_LABELS).map(([value, label]) => ({ value: value as CreditorPosition, label }));
  protected readonly noteMax = NOTE_MAX;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    position: this.fb.control<CreditorPosition | ''>('', [AppValidators.notBlank]),
    note: ['', [AppValidators.notBlank, Validators.maxLength(NOTE_MAX)]],
  });

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.api
      .confirmMailReply(this.data.disputeId, this.data.reply.id, v.position as CreditorPosition, v.note.trim())
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (r) => {
          this.notifications.success('Resposta do credor confirmada.');
          this.ref.close(r);
        },
        error: (e) => this.error.set(userMessage(toApiError(e))),
      });
  }
}

/**
 * E-mails do credor da contestação, lidos uma vez e compartilhados pelos fios do canal com o credor (provido pela
 * página da contestação; cada `app-dispute-mail-replies` só filtra a sua parte).
 */
@Injectable()
export class DisputeMailRepliesSource {
  private readonly api = inject(DisputesApi);
  private readonly disputeId = signal<string | undefined>(undefined);
  private readonly replies = rxResource({ params: () => this.disputeId(), stream: ({ params }) => this.api.mailReplies(params) });
  readonly all = valueOr(this.replies, []);

  /** Todas as instâncias informam o mesmo id; o `set` com valor igual não dispara nova leitura. */
  bind(disputeId: string): void {
    this.disputeId.set(disputeId);
  }

  reload(): void {
    this.replies.reload();
  }
}

/**
 * Respostas do credor recebidas por e-mail no pedido: texto sem HTML, verificações (autenticação e vírus), anexos que
 * viraram evidências, quarentena com o motivo e as decisões. O analista confirma ou descarta; a gestão libera ou
 * descarta a quarentena. O original (.eml) sai com registro no log de acesso.
 */
@Component({
  selector: 'app-dispute-mail-replies',
  imports: [AppDatePipe, Icon, StatusChip],
  template: `
    @for (r of rows(); track r.id) {
      <article class="mr-item" data-tour="dispute-mail-reply">
        <header class="mr-head">
          <app-icon name="inbox" class="mr-icon" />
          <strong>E-mail recebido do credor</strong>
          <app-status-chip domain="disputeMailReply" [status]="r.status" />
          <span class="app-muted mr-when">{{ r.receivedAt | appDate }}</span>
        </header>
        @if (r.quarantineReason && r.status === 'QUARANTINED') {
          <div class="mr-alert" role="note">
            <app-icon name="shield-exclamation" />
            <span>{{ reasons[r.quarantineReason] }}</span>
          </div>
        }
        <dl class="app-definition-list mr-meta">
          <dt>Remetente</dt>
          <dd>{{ r.sender }}</dd>
          @if (!requestIds()) {
            <!-- dentro do fio do pedido a referência é redundante -->
            <dt>Pedido</dt>
            <dd>Pedido {{ r.requestSequence }}</dd>
          }
          <dt>Verificações</dt>
          <dd class="mr-checks">
            <span class="mr-check" [class.mr-check--bad]="!r.authenticated">
              <app-icon [name]="r.authenticated ? 'check-circle' : 'exclamation-circle'" />
              {{ r.authenticated ? 'Remetente autenticado (DMARC)' : 'Autenticação não confirmada' }}
            </span>
            <span class="mr-check" [class.mr-check--bad]="r.virus !== 'PASS'">
              <app-icon [name]="r.virus === 'PASS' ? 'check-circle' : 'exclamation-circle'" />
              {{ r.virus === 'PASS' ? 'vírus não encontrado' : 'vírus: sem aprovação' }}
            </span>
          </dd>
          @if (r.attachments) {
            <dt>Anexos</dt>
            <dd>
              {{ r.attachments }} anexo(s): {{ r.evidenceIds.length }} nas evidências{{ r.attachmentsInVerification ? ', ' + r.attachmentsInVerification + ' em verificação' : '' }}{{ r.attachmentsRejected ? ', ' + r.attachmentsRejected + ' recusado(s)' : '' }}
            </dd>
          }
          @if (r.releasedAt) {
            <dt>Liberação</dt>
            <dd>Por {{ r.releasedByName ?? 'gestão' }} em {{ r.releasedAt | appDate }}: {{ r.releaseNote }}</dd>
          }
          @if (r.decidedAt) {
            <dt>Decisão</dt>
            <dd>{{ r.status === 'CONFIRMED' ? 'Confirmado' : 'Descartado' }} por {{ r.decidedByName ?? 'usuário' }} em {{ r.decidedAt | appDate }}{{ r.decisionNote ? ': ' + r.decisionNote : '' }}</dd>
          }
        </dl>
        <details>
          <summary>Mensagem: {{ r.subject || '(sem assunto)' }}</summary>
          <blockquote class="mr-quote" tabindex="0" aria-label="Texto do e-mail recebido do credor">{{ r.text || '(sem texto)' }}</blockquote>
          @if (r.textTruncated) {
            <div class="app-muted">Texto cortado; o original tem o conteúdo completo.</div>
          }
        </details>
        <div class="mr-actions">
          @if (r.status === 'PENDING_CONFIRMATION' && workable()) {
            <button class="btn btn-sm btn-primary" type="button" (click)="confirmReply(r)" [disabled]="busy()" data-tour="dispute-mail-confirm">Confirmar posição</button>
            <button class="btn btn-sm btn-outline-secondary" type="button" (click)="dismiss(r)" [disabled]="busy()">Descartar</button>
          }
          @if (r.status === 'QUARANTINED' && canManage()) {
            @if (r.releasable) {
              <button class="btn btn-sm btn-outline-secondary" type="button" (click)="release(r)" [disabled]="busy()">Liberar para o analista</button>
            }
            <button class="btn btn-sm btn-outline-danger" type="button" (click)="dismiss(r)" [disabled]="busy()">Descartar</button>
          }
          @if (canWork()) {
            <button class="btn btn-sm btn-outline-secondary" type="button" (click)="original(r)" [attr.aria-label]="'Baixar o e-mail original (acesso registrado)'">
              <app-icon name="download" />
              Original
            </button>
          }
        </div>
      </article>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: 10px;
    }
    /* resposta por e-mail: mesmo cartão das respostas do credor (dp-reply), recuado sob o pedido */
    .mr-item {
      display: grid;
      gap: 8px;
      padding: 12px 14px;
      border-left: 3px solid var(--app-border-strong);
      border-radius: var(--bs-border-radius-sm);
      background: var(--bs-body-bg);
    }
    .mr-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 10px;
    }
    .mr-icon {
      color: var(--bs-secondary-color);
    }
    /* texto do e-mail aberto: quadro de citação com altura limitada (mesmo do e-mail enviado) */
    .mr-quote {
      max-height: 320px;
      overflow: auto;
      margin: 12px 0 4px;
      padding: 10px 12px;
      border-radius: var(--bs-border-radius-sm);
      background: var(--bs-tertiary-bg);
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .mr-when {
      margin-left: auto;
      font-size: var(--app-text-sm);
    }
    .mr-alert {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      border-radius: var(--bs-border-radius-sm);
      background: var(--app-danger-bg);
      color: var(--app-danger-fg);
      font-weight: var(--app-weight-semibold);
    }
    .mr-meta {
      gap: 4px 20px;
      font-size: var(--app-text-sm);
      dd {
        overflow-wrap: anywhere;
      }
    }
    .mr-checks {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 14px;
    }
    .mr-check {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      color: var(--app-success-fg);
    }
    .mr-check--bad {
      color: var(--app-danger-fg);
    }
    .mr-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      padding-top: 4px;
    }
    .mr-actions:empty {
      display: none;
    }
    details summary {
      cursor: pointer;
    }
  `,
})
export class DisputeMailReplies {
  readonly disputeId = input.required<string>();
  /** Só os e-mails destes pedidos (o fio de cada pedido no canal com o credor). */
  readonly requestIds = input<readonly string[] | null>(null);
  /** Os e-mails que não são destes pedidos (os que sobram depois dos fios). */
  readonly excludeRequestIds = input<readonly string[] | null>(null);
  /** O usuário pode trabalhar esta contestação agora (responsável, gestão ou sem responsável). */
  readonly workable = input(false);
  readonly canManage = input(false);
  readonly canWork = input(false);
  /** Uma decisão mudou a contestação (para recarregar pedidos, respostas e evidências). */
  readonly changed = output<void>();

  private readonly api = inject(DisputesApi);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly reasons = MAIL_QUARANTINE_REASON_LABELS;
  protected readonly busy = signal(false);

  private readonly source = inject(DisputeMailRepliesSource);
  private readonly all = this.source.all;
  protected readonly rows = computed(() => {
    const only = this.requestIds();
    const except = this.excludeRequestIds();
    return this.all().filter((r) => (!only || only.includes(r.requestId)) && (!except || !except.includes(r.requestId)));
  });

  constructor() {
    effect(() => this.source.bind(this.disputeId()));
  }

  reload(): void {
    this.source.reload();
  }

  protected confirmReply(r: MailReplyView): void {
    this.dialog
      .open<MailReplyConfirmDialog, MailReplyConfirmData, CreditorResponseView>(MailReplyConfirmDialog, { data: { disputeId: this.disputeId(), reply: r }, size: 'lg' })
      .afterClosed()
      .subscribe((done) => {
        if (done) {
          this.source.reload();
          this.changed.emit();
        }
      });
  }

  protected dismiss(r: MailReplyView): void {
    this.confirm
      .confirm({
        title: 'Descartar e-mail do credor',
        message:
          r.status === 'QUARANTINED'
            ? 'O e-mail sai da quarentena sem virar resposta do credor. O original continua guardado para a cadeia de custódia.'
            : 'O e-mail não vira resposta do credor (por exemplo, sem nova posição ou duplicado). O pedido continua como está.',
        confirmLabel: 'Descartar',
        destructive: true,
        reason: 'required',
      })
      .subscribe((note) => this.run(this.api.dismissMailReply(this.disputeId(), r.id, note), 'E-mail descartado.'));
  }

  protected release(r: MailReplyView): void {
    this.confirm
      .confirm({
        title: 'Liberar e-mail para o analista',
        message: 'Confira o original antes. Liberado, o e-mail aguarda a confirmação do analista e os anexos passam pela verificação de segurança.',
        confirmLabel: 'Liberar',
        reason: 'required',
        reasonLabel: 'Por que é seguro liberar',
        details: [{ label: 'Motivo da quarentena', value: r.quarantineReason ? MAIL_QUARANTINE_REASON_LABELS[r.quarantineReason] : '—' }],
      })
      .subscribe((note) => this.run(this.api.releaseMailReply(this.disputeId(), r.id, note), 'E-mail liberado para o analista.'));
  }

  protected original(r: MailReplyView): void {
    this.api.downloadMailOriginal(this.disputeId(), r).subscribe();
  }

  private run(call: Observable<unknown>, message: string): void {
    this.busy.set(true);
    call.pipe(finalize(() => this.busy.set(false))).subscribe(() => {
      this.notifications.success(message);
      this.source.reload();
      this.changed.emit();
    });
  }
}
