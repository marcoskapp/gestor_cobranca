import { Component, ElementRef, Injector, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { DecimalPipe, NgTemplateOutlet } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs';
import { ApiError, toApiError, userMessage } from '../../core/http/problem';
import { Amount } from '../../shared/components/amount/amount';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { DIALOG_DATA, DialogRef } from '../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../shared/dialog/dialog.directives';
import { DialogService } from '../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../shared/forms/field';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { groupedHash } from '../../shared/util/hash';
import { AppValidators } from '../../shared/validation/validators';
import { CreditorDisputesApi } from '../data/creditor-disputes.api';
import { CreditorSession } from '../data/creditor-session';
import {
  CREDITOR_DISPUTE_POSITION_LABELS,
  CREDITOR_DISPUTE_TYPE_LABELS,
  CreditorDisputeDetail,
  CreditorDisputeDocument,
  CreditorDisputePosition,
  CreditorDisputeRequest,
  CreditorDisputeResponse,
  CreditorPermission,
} from '../data/creditor.models';
import { disputeSituationText } from './creditor-disputes';

/** Limites da resposta (os mesmos do servidor). */
export const DISPUTE_RESPONSE_LIMITS = { files: 5, fileBytes: 10 * 1024 * 1024, note: 4000 } as const;
const ACCEPT = '.pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg';
const ALLOWED_NAME = /\.(pdf|png|jpe?g)$/i;
const ALLOWED_MIME = ['application/pdf', 'image/png', 'image/jpeg'];
/** Conflitos em que o pedido não pode mais ser respondido: o diálogo fecha e a ficha recarrega (os demais mantêm o texto). */
const RELOAD_CONFLICTS = new Set(['dispute_response.not_awaiting', 'dispute.not_open', 'concurrent_modification']);

function responseMessage(error: ApiError): string {
  if (error.code === 'dispute.evidence_type_invalid') {
    return 'Um dos documentos não é PDF, PNG ou JPEG. Envie só arquivos nesses formatos.';
  }
  if (error.code === 'dispute.evidence_limit') {
    return 'A contestação já tem o máximo de documentos. Envie a resposta sem anexos e combine o envio com a assessoria.';
  }
  if (error.status === 429) {
    return 'Muitos envios com documentos em pouco tempo. Aguarde um minuto e tente de novo.';
  }
  if (error.status === 404) {
    return 'Esta contestação não está mais disponível. Volte à lista de contestações.';
  }
  if (error.status === 409) {
    return 'A contestação mudou enquanto você respondia (por exemplo, outra pessoa do seu time já respondeu ou a assessoria encerrou). Atualizamos a página abaixo.';
  }
  if (error.status === 422) {
    return error.detail ?? 'Não foi possível registrar a resposta. Confira os dados e tente de novo.';
  }
  return userMessage(error);
}

interface RespondDialogData {
  detail: CreditorDisputeDetail;
  request: CreditorDisputeRequest;
}

type RespondResult = CreditorDisputeDetail | { reload: true; message: string };

/**
 * Resposta ao pedido da assessoria: posição, resumo e até 5 documentos. A resposta não encerra a contestação — a
 * assessoria analisa e decide — e os documentos passam por verificação de segurança antes de chegar a ela.
 */
@Component({
  selector: 'app-creditor-dispute-respond-dialog',
  imports: [DecimalPipe, ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Responder · protocolo {{ data.detail.dispute.protocol }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          Sua resposta vai para a assessoria, que analisa e decide a contestação. Responder não encerra a contestação nem muda
          os prazos dados ao devedor. Depois de enviada, a resposta não pode ser alterada nem receber novos documentos:
          confira a posição e anexe tudo agora.
        </p>
        <app-field class="mb-3" label="Sua posição" error="Escolha a sua posição.">
          <select appInput ngbAutofocus formControlName="position" required>
            <option value="" disabled>Selecione</option>
            @for (p of positions; track p.value) {
              <option [value]="p.value">{{ p.label }}</option>
            }
          </select>
        </app-field>
        <app-field class="mb-3" label="Resumo" [hintEnd]="(form.controls.note.value.length | number) + ' / ' + (limits.note | number)"
                   hint="O que o seu time encontrou (pagamentos, baixas, contratos). Sem dados além do necessário."
                   error="Escreva um resumo da resposta.">
          <textarea appInput formControlName="note" rows="5" [maxlength]="limits.note" required></textarea>
        </app-field>
        <input #file type="file" multiple [accept]="accept" (change)="pick(file.files); file.value = ''" hidden
               aria-label="Escolher documentos" />
        <button type="button" class="btn btn-outline-primary" (click)="file.click()" [disabled]="files().length >= limits.files">
          <app-icon name="paperclip" /> Anexar documentos
        </button>
        <div class="form-text">Até {{ limits.files }} arquivos PDF, PNG ou JPEG, de até 10 MB cada.</div>
        @if (files().length) {
          <ul class="list-unstyled mt-2 mb-0">
            @for (f of files(); track f; let i = $index) {
              <li class="d-flex align-items-center gap-2">
                <app-icon name="file-earmark-text" /><span class="text-break">{{ f.name }}</span>
                <span class="app-muted">{{ f.size / 1048576 | number: '1.1-1' }} MB</span>
                <button type="button" class="btn btn-link btn-sm" (click)="remove(i)" [attr.aria-label]="'Remover ' + f.name">Remover</button>
              </li>
            }
          </ul>
        }
        <div aria-live="polite">
          @if (fileError()) {
            <div class="alert alert-warning mt-3 mb-0">{{ fileError() }}</div>
          }
        </div>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="busy()">Enviar resposta</button>
      </div>
    </form>
  `,
})
export class CreditorDisputeRespondDialog {
  protected readonly data = inject<RespondDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<RespondResult>>(DialogRef);
  private readonly api = inject(CreditorDisputesApi);
  protected readonly limits = DISPUTE_RESPONSE_LIMITS;
  protected readonly accept = ACCEPT;
  protected readonly positions = Object.entries(CREDITOR_DISPUTE_POSITION_LABELS)
    .map(([value, label]) => ({ value: value as CreditorDisputePosition, label }));
  protected readonly files = signal<File[]>([]);
  protected readonly fileError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    position: ['' as CreditorDisputePosition | '', [Validators.required]],
    note: ['', [AppValidators.notBlank, Validators.maxLength(DISPUTE_RESPONSE_LIMITS.note)]],
  });

  protected pick(list: FileList | null): void {
    const current = this.files();
    const picked = Array.from(list ?? []).filter((f) => !current.some((c) => c.name === f.name && c.size === f.size));
    const invalid = picked.filter((f) => f.size === 0 || (!ALLOWED_NAME.test(f.name) && !ALLOWED_MIME.includes(f.type)));
    const valid = picked.filter((f) => !invalid.includes(f));
    const tooLarge = valid.filter((f) => f.size > DISPUTE_RESPONSE_LIMITS.fileBytes);
    const accepted = valid.filter((f) => f.size <= DISPUTE_RESPONSE_LIMITS.fileBytes);
    const room = DISPUTE_RESPONSE_LIMITS.files - this.files().length;
    this.files.update((current) => [...current, ...accepted.slice(0, room)]);
    const messages: string[] = [];
    if (invalid.length) {
      messages.push(`${invalid.map((f) => f.name).join(', ')}: só PDF, PNG ou JPEG com conteúdo, não anexado.`);
    }
    if (tooLarge.length) {
      messages.push(`${tooLarge.map((f) => f.name).join(', ')}: maior que 10 MB, não anexado.`);
    }
    if (accepted.length > room) {
      messages.push(`No máximo ${DISPUTE_RESPONSE_LIMITS.files} documentos por resposta.`);
    }
    this.fileError.set(messages.length ? messages.join(' ') : null);
  }

  protected remove(index: number): void {
    this.files.update((current) => current.filter((_, i) => i !== index));
    this.fileError.set(null);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.busy()) {
      return;
    }
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.api
      .respond(this.data.detail.dispute.id, this.data.request.id, v.position as CreditorDisputePosition, v.note.trim(), this.files())
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (detail) => this.ref.close(detail),
        error: (err: unknown) => {
          const e = toApiError(err);
          if (e.status === 404 || (e.status === 409 && RELOAD_CONFLICTS.has(e.code ?? ''))) {
            this.ref.close({ reload: true, message: responseMessage(e) });
            return;
          }
          // ex.: limite de documentos da contestação — o resumo e os anexos ficam para a pessoa ajustar e reenviar
          this.error.set(responseMessage(e));
        },
      });
  }
}

/**
 * Ficha da contestação no portal do credor: o que foi contestado (protocolo, natureza, títulos e valor), os pedidos da
 * assessoria (texto como foi enviado e documentos, com conferência do código de integridade) e as respostas.
 */
@Component({
  selector: 'app-creditor-dispute-detail',
  imports: [NgTemplateOutlet, RouterLink, Amount, AppDatePipe, EmptyState, Icon, Skeleton, StatusChip],
  template: `
    <a class="btn btn-link px-0" [routerLink]="['/credor', slug(), 'contestacoes']">
      <app-icon name="arrow-left" /> Contestações
    </a>
    @if (detail.isLoading() && !current()) {
      <app-skeleton variant="card" />
    } @else if (detail.error() && !current()) {
      <section class="app-card">
        @if (notFound()) {
          <app-empty-state tone="error" icon="chat-square-text" title="Contestação não encontrada."
                           message="Confira o link recebido ou abra a contestação pela lista.">
            <a class="btn btn-outline-secondary" [routerLink]="['/credor', slug(), 'contestacoes']">Voltar às contestações</a>
          </app-empty-state>
        } @else {
          <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível abrir a contestação."
                           message="Verifique sua conexão e tente de novo. Se continuar, avise a assessoria.">
            <button class="btn btn-outline-secondary" type="button" (click)="detail.reload()">Tentar novamente</button>
          </app-empty-state>
        }
      </section>
    }
    @if (current(); as d) {
      <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
        <h1 #pageTitle class="h4 mb-0" tabindex="-1">Contestação · protocolo {{ d.dispute.protocol }}</h1>
        <app-status-chip domain="creditorDispute" [status]="d.dispute.situation" />
      </div>
      <p class="app-muted">{{ typeLabel(d.dispute.type) }} · aberta em {{ d.dispute.openedAt | appDate: 'date' }}</p>
      <div aria-live="polite">
        @if (notice(); as n) {
          <div class="alert d-flex align-items-start gap-2" [class.alert-success]="n.ok" [class.alert-warning]="!n.ok">
            <app-icon [name]="n.ok ? 'check-circle' : 'exclamation-triangle'" /><span>{{ n.text }}</span>
          </div>
        }
      </div>

      @if (awaiting(); as r) {
        <div class="alert d-flex flex-wrap align-items-center gap-2" [class.alert-warning]="!r.overdue" [class.alert-danger]="r.overdue"
             role="note" data-tour="creditor-dispute-respond">
          <span class="flex-grow-1">
            A assessoria pediu a sua posição até {{ r.requestedDueDate | appDate: 'date' }}{{ r.overdue ? ' (prazo vencido)' : '' }}.
            Leia o pedido abaixo e responda.
          </span>
          @if (canRespond()) {
            <button class="btn btn-primary" type="button" (click)="respond(d, r)">Responder</button>
          } @else {
            <span class="app-muted">Quem responde pelo credor são os perfis Gestão de recuperação e Jurídico.</span>
          }
        </div>
      } @else {
        <div class="alert alert-secondary d-flex align-items-start gap-2" role="note">
          <app-icon name="info-circle" /><span>{{ situation(d) }}.</span>
        </div>
      }

      <section class="app-card" aria-labelledby="cdd-resumo">
        <h2 class="app-card__title" id="cdd-resumo">O que foi contestado</h2>
        <dl class="app-definition-list">
          <dt>Natureza</dt>
          <dd>{{ typeLabel(d.dispute.type) }}</dd>
          <dt>Valor contestado</dt>
          <dd><app-amount [value]="d.dispute.disputedAmount" /></dd>
          @if (d.dispute.closedAt) {
            <dt>Encerrada em</dt>
            <dd>{{ d.dispute.closedAt | appDate: 'date' }}</dd>
          }
        </dl>
        <h3 class="h6 mt-3">Títulos</h3>
        <div class="table-responsive">
          <table class="table table-sm mb-0">
            <caption class="visually-hidden">Títulos contestados</caption>
            <thead>
              <tr><th scope="col">Título</th><th scope="col">Parcela</th><th scope="col">Vencimento</th><th scope="col" class="text-end">Saldo</th></tr>
            </thead>
            <tbody>
              @for (t of d.titles; track $index) {
                <tr>
                  <td>{{ t.externalId }}</td>
                  <td>{{ t.installmentNumber ?? '—' }}</td>
                  <td>{{ t.dueDate ? (t.dueDate | appDate: 'date') : '—' }}</td>
                  <td class="text-end">@if (t.openBalance !== null) { <app-amount [value]="t.openBalance" /> } @else { — }</td>
                </tr>
              } @empty {
                <tr><td colspan="4" class="app-muted">Os títulos não estão mais disponíveis para consulta.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <section class="app-card" aria-labelledby="cdd-pedidos">
        <h2 class="app-card__title" id="cdd-pedidos">Pedidos da assessoria</h2>
        @for (r of d.requests; track r.id) {
          <article class="cdd-item" [attr.aria-labelledby]="'cdd-req-' + r.id">
            <h3 class="h6 mb-1" [id]="'cdd-req-' + r.id">Pedido {{ r.sequenceNumber }} · {{ r.subject }}</h3>
            <p class="app-muted cdd-small mb-2">
              {{ r.sentAt ? 'Enviado em ' : 'Registrado pela assessoria' }}{{ r.sentAt ? (r.sentAt | appDate) : '' }} · responder até {{ r.requestedDueDate | appDate: 'date' }}
              @if (r.awaiting && r.overdue) {
                <span class="cdd-late"><app-icon name="exclamation-triangle" /> prazo vencido</span>
              }
              {{ r.awaiting ? '' : '· respondido' }}
            </p>
            <p class="cdd-pre">{{ r.body }}</p>
            @for (f of r.documents; track f.id) {
              <ng-container [ngTemplateOutlet]="fileRow" [ngTemplateOutletContext]="{ $implicit: f, dispute: d }" />
            }
          </article>
        }
      </section>

      <section class="app-card" aria-labelledby="cdd-respostas">
        <h2 class="app-card__title" id="cdd-respostas">Respostas</h2>
        @for (r of d.responses; track r.id) {
          <article class="cdd-item">
            <h3 class="h6 mb-1">{{ positionLabel(r) }}</h3>
            <p class="app-muted cdd-small mb-2">
              @if (requestOf(d, r); as q) {
                <a [routerLink]="[]" [fragment]="'cdd-req-' + q.id">Resposta ao pedido {{ q.sequenceNumber }}</a> ·
              }
              {{ originText(r) }} · {{ r.receivedAt | appDate }}
            </p>
            @if (r.note) {
              <p class="cdd-pre">{{ r.note }}</p>
            }
            @for (f of r.documents; track f.id) {
              <ng-container [ngTemplateOutlet]="fileRow" [ngTemplateOutletContext]="{ $implicit: f, dispute: d }" />
            }
            @if (r.documentsInVerification) {
              <p class="app-muted cdd-small mb-0">
                <app-icon name="hourglass-split" /> {{ r.documentsInVerification }} documento(s) em verificação de segurança antes de chegar à assessoria.
              </p>
            }
            @if (r.documentsRejected) {
              <p class="cdd-late cdd-small mb-0">
                <app-icon name="exclamation-triangle" /> {{ r.documentsRejected }} documento(s) recusado(s) na verificação de segurança. Combine outro envio com a assessoria.
              </p>
            }
          </article>
        } @empty {
          <p class="app-muted mb-0">Nenhuma resposta ainda.</p>
        }
      </section>

      <ng-template #fileRow let-f let-dispute="dispute">
        <div class="cdd-file">
          <div class="cdd-file__name">
            <strong>{{ f.fileName }}</strong>
            <div class="app-muted cdd-small">Código de integridade: <span class="cdd-hash">{{ hash(f.sha256) }}</span></div>
            @if (checked()[f.id] !== undefined) {
              <app-status-chip domain="creditorFileCheck" [status]="checked()[f.id] ? 'OK' : 'MISMATCH'" />
            }
          </div>
          <button class="btn btn-outline-secondary" type="button" (click)="download(dispute, f)" [disabled]="downloading() === f.id"
                  [attr.aria-label]="'Baixar ' + f.fileName">
            <app-icon name="download" /> Baixar
          </button>
        </div>
        <div aria-live="polite">
          @if (downloadMessage(); as m) {
            @if (m.id === f.id) {
              <div class="alert mb-2" [class.alert-success]="m.ok" [class.alert-danger]="!m.ok">{{ m.text }}</div>
            }
          }
        </div>
      </ng-template>
    }
  `,
  styles: `
    .cdd-item {
      padding: 12px 0;
      border-bottom: var(--bs-border-width) solid var(--app-border);
    }
    .cdd-item:last-of-type {
      border-bottom: 0;
    }
    .cdd-small {
      font-size: var(--app-text-xs);
    }
    .cdd-pre {
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .cdd-late {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
    .cdd-file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 6px 0;
    }
    .cdd-file__name {
      min-width: 0;
    }
    .cdd-hash {
      font-family: var(--bs-font-monospace);
      word-break: break-all;
    }
  `,
})
export class CreditorDisputeDetailPage {
  private readonly api = inject(CreditorDisputesApi);
  private readonly dialog = inject(DialogService);
  private readonly session = inject(CreditorSession);
  private readonly route = inject(ActivatedRoute);
  protected readonly slug = toSignal(this.route.parent!.paramMap.pipe(map((p) => p.get('slug') ?? '')), { initialValue: '' });
  private readonly id = toSignal(this.route.paramMap.pipe(map((p) => p.get('id') ?? '')), { initialValue: '' });
  protected readonly detail = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  private readonly updated = signal<CreditorDisputeDetail | null>(null);
  protected readonly current = computed(() => {
    const u = this.updated();
    return u && u.dispute.id === this.id() ? u : (this.detail.value() ?? null);
  });
  /** Pedido mais antigo que ainda aguarda a resposta (é o que a resposta atende). */
  protected readonly awaiting = computed(() => {
    const d = this.current();
    return d && d.dispute.situation === 'AWAITING_YOU' ? (d.requests.find((r) => r.awaiting) ?? null) : null;
  });
  protected readonly notFound = computed(() => toApiError(this.detail.error()).status === 404);
  protected readonly canRespond = computed(() => this.session.me()?.permissions.includes(CreditorPermission.DISPUTE_RESPOND) ?? false);
  protected readonly notice = signal<{ ok: boolean; text: string } | null>(null);
  protected readonly checked = signal<Record<string, boolean>>({});
  protected readonly downloading = signal<string | null>(null);
  protected readonly downloadMessage = signal<{ id: string; ok: boolean; text: string } | null>(null);
  private readonly injector = inject(Injector);
  private readonly pageTitle = viewChild<ElementRef<HTMLElement>>('pageTitle');
  protected readonly hash = groupedHash;
  protected readonly typeLabel = (type: string) => CREDITOR_DISPUTE_TYPE_LABELS[type] ?? 'Contestação';
  protected readonly situation = (d: CreditorDisputeDetail) => disputeSituationText(d.dispute);
  protected readonly requestOf = (d: CreditorDisputeDetail, r: CreditorDisputeResponse) =>
    d.requests.find((q) => q.id === r.requestId) ?? null;
  protected readonly positionLabel = (r: CreditorDisputeResponse) => CREDITOR_DISPUTE_POSITION_LABELS[r.position];
  protected readonly originText = (r: CreditorDisputeResponse) =>
    r.origin === 'CREDITOR_PORTAL' ? 'Pelo seu time, no portal'
      : r.origin === 'INTEGRATION' ? 'Pela integração do seu sistema' : 'Registrada pela assessoria, recebida por outro canal';

  protected respond(d: CreditorDisputeDetail, request: CreditorDisputeRequest): void {
    this.dialog
      .open<CreditorDisputeRespondDialog, RespondDialogData, RespondResult>(CreditorDisputeRespondDialog, { data: { detail: d, request } })
      .afterClosed()
      .subscribe((result) => {
        if (!result) {
          return;
        }
        if ('reload' in result) {
          this.updated.set(null);
          this.detail.reload();
          this.notice.set({ ok: false, text: result.message });
        } else {
          this.updated.set(result);
          const last = result.responses.at(-1);
          const withDocuments = !!last && (last.documentsInVerification > 0 || last.documents.length > 0);
          this.notice.set({ ok: true, text: withDocuments
            ? 'Resposta enviada à assessoria. Os documentos passam por verificação de segurança antes de chegar a ela.'
            : 'Resposta enviada à assessoria.' });
        }
        // o botão de responder some com a mudança de situação: o foco vai para o título da ficha
        afterNextRender(() => this.pageTitle()?.nativeElement.focus(), { injector: this.injector });
      });
  }

  protected download(d: CreditorDisputeDetail, f: CreditorDisputeDocument): void {
    this.downloading.set(f.id);
    this.downloadMessage.set(null);
    this.api
      .download(d.dispute.id, f)
      .pipe(finalize(() => this.downloading.set(null)))
      .subscribe({
        next: ({ verified }) => {
          this.checked.update((c) => ({ ...c, [f.id]: verified }));
          this.downloadMessage.set(verified
            ? { id: f.id, ok: true, text: `${f.fileName} baixado. O código de integridade confere.` }
            : { id: f.id, ok: false, text: 'O arquivo recebido não confere com o código de integridade e não foi salvo. Tente de novo; se continuar, avise a assessoria.' });
        },
        error: (err: unknown) => this.downloadMessage.set({ id: f.id, ok: false, text: toApiError(err).status === 404
          ? 'Este documento não está mais disponível. Atualize a página.'
          : 'Não foi possível baixar o documento. Tente de novo; se continuar, avise a assessoria.' }),
      });
  }
}
