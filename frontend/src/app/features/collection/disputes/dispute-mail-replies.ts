import { Component, computed, inject, input, output, signal } from '@angular/core';
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
        <blockquote class="mr-quote">{{ data.reply.text || '(sem texto)' }}</blockquote>
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
 * Respostas do credor recebidas por e-mail no pedido: texto sem HTML, verificações (autenticação e vírus), anexos que
 * viraram evidências, quarentena com o motivo e as decisões. O analista confirma ou descarta; a gestão libera ou
 * descarta a quarentena. O original (.eml) sai com registro no log de acesso.
 */
@Component({
  selector: 'app-dispute-mail-replies',
  imports: [AppDatePipe, Icon, StatusChip],
  template: `
    @for (r of rows(); track r.id) {
      <article class="dp-item mr-item" data-tour="dispute-mail-reply">
        <header class="mr-head">
          <strong>E-mail do credor</strong>
          <app-status-chip domain="disputeMailReply" [status]="r.status" />
          <span class="app-muted">{{ r.receivedAt | appDate }} · pedido {{ r.requestSequence }} · {{ r.sender }}</span>
        </header>
        @if (r.quarantineReason && r.status === 'QUARANTINED') {
          <div class="mr-alert" role="note"><app-icon name="shield-exclamation" /> {{ reasons[r.quarantineReason] }}</div>
        }
        <div class="app-muted">
          {{ r.authenticated ? 'Remetente autenticado (DMARC)' : 'Autenticação não confirmada' }} · vírus {{ r.virus === 'PASS' ? 'não encontrado' : 'sem aprovação' }}
          @if (r.attachments) {
            · {{ r.attachments }} anexo(s): {{ r.evidenceIds.length }} nas evidências{{ r.attachmentsInVerification ? ', ' + r.attachmentsInVerification + ' em verificação' : '' }}{{ r.attachmentsRejected ? ', ' + r.attachmentsRejected + ' recusado(s)' : '' }}
          }
        </div>
        <details>
          <summary>{{ r.subject || '(sem assunto)' }}</summary>
          <p class="dp-pre mb-1">{{ r.text || '(sem texto)' }}</p>
          @if (r.textTruncated) {
            <div class="app-muted">Texto cortado; o original tem o conteúdo completo.</div>
          }
        </details>
        @if (r.releasedAt) {
          <div class="app-muted">Liberado da quarentena por {{ r.releasedByName ?? 'gestão' }} em {{ r.releasedAt | appDate }}: {{ r.releaseNote }}</div>
        }
        @if (r.decidedAt) {
          <div class="app-muted">
            {{ r.status === 'CONFIRMED' ? 'Confirmado' : 'Descartado' }} por {{ r.decidedByName ?? 'usuário' }} em {{ r.decidedAt | appDate }}{{ r.decisionNote ? ': ' + r.decisionNote : '' }}
          </div>
        }
        <div class="d-flex flex-wrap gap-2 mt-1">
          @if (r.status === 'PENDING_CONFIRMATION' && workable()) {
            <button class="btn btn-sm btn-primary" type="button" (click)="confirmReply(r)" [disabled]="busy()" data-tour="dispute-mail-confirm">Confirmar posição</button>
            <button class="btn btn-sm btn-outline-secondary" type="button" (click)="dismiss(r)" [disabled]="busy()">Descartar</button>
          }
          @if (r.status === 'QUARANTINED' && canManage()) {
            @if (r.releasable) {
              <button class="btn btn-sm btn-outline-primary" type="button" (click)="release(r)" [disabled]="busy()">Liberar para o analista</button>
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
    .mr-item {
      display: grid;
      gap: 4px;
    }
    .mr-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 8px;
    }
    .mr-alert {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
    .dp-pre {
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    details summary {
      cursor: pointer;
    }
  `,
})
export class DisputeMailReplies {
  readonly disputeId = input.required<string>();
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

  private readonly replies = rxResource({ params: () => this.disputeId(), stream: ({ params }) => this.api.mailReplies(params) });
  protected readonly rows = valueOr(this.replies, []);
  readonly count = computed(() => this.rows().length);

  reload(): void {
    this.replies.reload();
  }

  protected confirmReply(r: MailReplyView): void {
    this.dialog
      .open<MailReplyConfirmDialog, MailReplyConfirmData, CreditorResponseView>(MailReplyConfirmDialog, { data: { disputeId: this.disputeId(), reply: r }, size: 'lg' })
      .afterClosed()
      .subscribe((done) => {
        if (done) {
          this.replies.reload();
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
      this.replies.reload();
      this.changed.emit();
    });
  }
}
