import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { Observable, finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { EVIDENCE_ACCEPT, dueText, evidenceProblem } from '../../compliance/data/compliance.labels';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { PaymentLauncher } from '../../payment/data/payment-launcher';
import { DisputesApi } from '../data/collection.api';
import {
  ADJUSTMENT_ACTION_LABELS,
  CREDITOR_POSITION_LABELS,
  DEADLINE_SOURCE_LABELS,
  DISPUTE_ASSIGNMENT_MODE_LABELS,
  DISPUTE_ORIGIN_LABELS,
  DISPUTE_OUTCOME_LABELS,
  DISPUTE_TYPE_LABELS,
  MAIL_PURPOSE_LABELS,
  MAIL_STATUS_LABELS,
  NOTICE_CHANNEL_LABELS,
} from '../data/collection.labels';
import { CreditorResponseView, DisputeDetail, DisputeView, EvidenceView } from '../data/collection.models';
import { daysBetween, zonedNow } from '../data/collection.rules';
import {
  AssignDisputeData,
  AssignDisputeDialog,
  CreditorRequestData,
  CreditorRequestDialog,
  CreditorResponseData,
  CreditorResponseDialog,
  DebtorNoticeData,
  DebtorNoticeDialog,
  ResolveDisputeData,
  ResolveDisputeDialog,
} from './dispute-dialogs';
import { DisputeMailReplies, DisputeMailRepliesSource } from './dispute-mail-replies';
import { adjustmentShortcut, canWorkDispute, disputeActions, dueTone, isPending, nearestDue, protocolOf } from './dispute.rules';

/** Resposta registrada do credor (pela API, por e-mail confirmado ou registrada à mão), no fio do pedido. */
@Component({
  selector: 'app-creditor-response',
  imports: [AppDatePipe],
  template: `
    <header class="dp-thread__head">
      <strong>Resposta do credor</strong>
      <span class="badge rounded-pill bg-info-subtle text-info-emphasis">{{ positionLabel() }}</span>
      <span class="app-muted dp-thread__when">{{ response().receivedAt | appDate }}</span>
    </header>
    <dl class="app-definition-list dp-meta">
      <dt>Origem</dt>
      <dd>{{ response().source === 'PUBLIC_API' ? 'API do credor' : (response().source === 'EMAIL' ? 'E-mail, confirmado por ' : 'Registrada por ') + (response().source === 'PUBLIC_API' ? '' : (response().recordedByName ?? 'usuário')) }}</dd>
      @if (response().evidenceIds.length || response().documentsInVerification || response().documentsRejected) {
        <dt>Documentos</dt>
        <dd>
          @if (response().evidenceIds.length) {
            <div>{{ response().evidenceIds.length }} nas evidências</div>
          }
          @if (response().documentsInVerification) {
            <div>{{ response().documentsInVerification }} em verificação de segurança</div>
          }
          @if (response().documentsRejected) {
            <div class="dp-late">{{ response().documentsRejected }} recusado(s) na verificação de segurança — peça o reenvio por outro meio</div>
          }
        </dd>
      }
    </dl>
    <p class="dp-pre mb-0">{{ response().note }}</p>
  `,
  // o cartão (fundo, recuo e linha à esquerda) vem da classe `dp-reply` aplicada pelo canal com o credor
  styles: `
    .dp-thread__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 10px;
    }
    .dp-thread__when {
      margin-left: auto;
      font-size: var(--app-text-sm);
    }
    .dp-meta {
      gap: 4px 20px;
      font-size: var(--app-text-sm);
    }
    .dp-late {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
    .dp-pre {
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
  `,
})
export class CreditorResponseCard {
  readonly response = input.required<CreditorResponseView>();
  protected readonly positionLabel = computed(() => CREDITOR_POSITION_LABELS[this.response().position]);
}

/**
 * Página da contestação (F21): resumo com os três relógios (prazo interno, prazo de resposta ao devedor e prazo pedido
 * ao credor), responsável, canal formal com o credor (pedidos, lembretes, respostas), evidências, resolução com o ajuste
 * sugerido no título e a resposta fundamentada ao devedor. As regras são do servidor; a tela só oferece o que cabe.
 */
@Component({
  selector: 'app-dispute-page',
  providers: [DisputeMailRepliesSource],
  imports: [RouterLink, PageHeader, StatusChip, Skeleton, EmptyState, Amount, AppDatePipe, Icon, DisputeMailReplies, CreditorResponseCard],
  template: `
    @if (detail.error()) {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a contestação." message="Ela pode não existir ou não estar no seu escopo de credores.">
        <button class="btn btn-outline-primary" type="button" (click)="detail.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (!view()) {
      <app-skeleton variant="card" [rows]="8" />
    } @else {
      @let v = view()!;
      @let d = v.dispute;
      <app-page-header [title]="'Contestação ' + protocol() + ' · ' + types[d.type]" [subtitle]="debtors.name(d.debtorId) + ' · ' + creditors.name(d.creditorId)">
        <div class="d-flex flex-wrap gap-2" data-tour="dispute-actions">
          @if (pending()) {
            @if (canAssign()) {
              <button class="btn btn-outline-secondary" type="button" (click)="assign(d)" [disabled]="busy()">
                <app-icon name="person-check" />
                Responsável
              </button>
            } @else if (!d.assigneeId && canWork()) {
              <button class="btn btn-outline-secondary" type="button" (click)="take(d)" [disabled]="busy()">Pegar para mim</button>
            }
            @if (workable()) {
              <button class="btn btn-outline-secondary" type="button" (click)="requestCreditor(d)" data-tour="dispute-request-creditor">
                <app-icon name="send" />
                Solicitar ao credor
              </button>
              @if (v.creditorRequests.length) {
                <button class="btn btn-outline-secondary" type="button" (click)="creditorResponse(v)">Registrar resposta do credor</button>
              }
              @if (actions().includes('review')) {
                <button class="btn btn-outline-secondary" type="button" (click)="review(d)" [disabled]="busy()">Enviar para análise</button>
              }
              @if (actions().includes('withdraw')) {
                <button class="btn btn-outline-secondary" type="button" (click)="withdraw(d)" [disabled]="busy()">Registrar desistência</button>
              }
              @if (actions().includes('resolve')) {
                <button class="btn btn-primary" type="button" (click)="resolve(d)">Resolver</button>
              }
            }
          } @else if (d.debtorNoticeStatus === 'PENDING' && workable()) {
            <button class="btn btn-primary" type="button" (click)="notifyDebtor(d)" data-tour="dispute-notify">
              <app-icon name="chat-left-text" />
              Responder ao devedor
            </button>
          }
        </div>
      </app-page-header>

      @if (pending() && !workable() && d.assigneeId) {
        <div class="alert alert-info d-flex gap-2" role="note">
          <app-icon name="info-circle" />
          <span>Contestação de {{ d.assigneeName }}. Só o responsável ou a gestão de contestações trabalha nela.</span>
        </div>
      }

      <div class="dp-layout">
        <div class="app-stack">
          <section class="app-card" aria-labelledby="dp-summary" data-tour="dispute-summary">
            <h2 class="app-card__title" id="dp-summary">Resumo</h2>
            <dl class="app-definition-list">
              <dt>Situação</dt>
              <dd>
                <app-status-chip domain="dispute" [status]="d.status" />
                @if (d.awaitingCreditorSince) {
                  <span class="app-muted ms-2">aguardando o credor desde {{ d.awaitingCreditorSince | appDate: 'date' }}</span>
                }
              </dd>
              <dt>Responsável</dt>
              <dd>{{ d.assigneeName ?? 'Sem responsável' }}{{ d.assignedAt ? ' · desde ' : '' }}{{ d.assignedAt ? (d.assignedAt | appDate: 'date') : '' }}</dd>
              <dt>Origem</dt>
              <dd>{{ origins[d.origin] }} · aberta em {{ d.openedAt | appDate }}</dd>
              <dt>Prazo interno</dt>
              <dd [class.dp-late]="pending() && tone() === 'late' && d.internalDueDate === nearest()">
                {{ d.internalDueDate | appDate: 'date' }}
                @if (pending() && d.internalDueDate === nearest()) {
                  · {{ dueLabel() }}
                }
              </dd>
              <dt>Resposta ao devedor</dt>
              <dd [class.dp-late]="pending() && d.overdue">
                até {{ d.responseDueDate | appDate: 'date' }}{{ pending() && d.overdue ? ' (vencido)' : '' }}
                <div class="app-muted">prazo {{ deadlineSources[d.deadlineSource] }}; não é pausado enquanto se aguarda o credor</div>
              </dd>
              <dt>Títulos</dt>
              <dd>
                {{ d.titleIds.length }} · {{ d.blocksCollection ? 'cobrança e contato suspensos' : 'sem suspensão' }}
                <ul class="dp-list" aria-label="Títulos contestados">
                  @for (t of d.titles; track t.titleId) {
                    <li>
                      <a class="app-mono" [routerLink]="['/titulos', t.titleId]">{{ t.externalId }}{{ t.installmentNumber ? ' / ' + t.installmentNumber : '' }}</a>
                      <span class="app-muted">venc. {{ t.dueDate | appDate: 'date' }}</span>
                      <app-amount [value]="t.principalAmount" />
                      @if (t.status) {
                        <app-status-chip domain="title" [status]="t.status" />
                      }
                    </li>
                  }
                </ul>
              </dd>
              @if (d.disputedAmount !== null) {
                <dt>Valor contestado</dt>
                <dd><app-amount [value]="d.disputedAmount" /></dd>
              }
              <dt>Descrição</dt>
              <dd class="dp-pre">{{ d.description || '—' }}</dd>
            </dl>
          </section>

          @if (d.outcome) {
            <section class="app-card" aria-labelledby="dp-resolution" data-tour="dispute-resolution">
              <h2 class="app-card__title" id="dp-resolution">Resolução</h2>
              <dl class="app-definition-list">
                <dt>Resultado</dt>
                <dd>{{ outcomes[d.outcome] }} · {{ d.resolvedAt | appDate }}</dd>
                <dt>Parecer</dt>
                <dd class="dp-pre">{{ d.resolutionNote }}</dd>
                @if (d.adjustmentAction) {
                  <dt>Ajuste no título</dt>
                  <dd>
                    {{ adjustmentLabels[d.adjustmentAction] }}
                    <app-status-chip domain="disputeAdjustment" [status]="d.adjustmentStatus" />
                    @if (d.adjustmentNote) {
                      <div class="app-muted dp-pre">{{ d.adjustmentNote }}</div>
                    }
                    @if (d.adjustmentStatus === 'PENDING') {
                      <div class="d-flex flex-wrap gap-2 mt-2" data-tour="dispute-adjustment">
                        @if (canAdjust(d)) {
                          <button class="btn btn-sm btn-outline-primary" type="button" (click)="adjust(d)">
                            <app-icon name="box-arrow-up-right" />
                            {{ adjustmentCta(d) }}
                          </button>
                        }
                        @if (workable()) {
                          <button class="btn btn-sm btn-outline-secondary" type="button" (click)="dismissAdjustment(d)">Dispensar ajuste</button>
                        }
                      </div>
                      @if (d.adjustmentAction === 'ADJUST_AMOUNT') {
                        <div class="app-muted mt-1">A correção manual do valor fica registrada no título (motivo, quem e quando; acima do limite, com aprovação de outra pessoa); a pendência é concluída quando o saldo mudar, também se o credor corrigir na carga.</div>
                      }
                    }
                  </dd>
                }
                @if (d.debtorNoticeStatus) {
                  <dt>Resposta ao devedor</dt>
                  <dd>
                    @if (d.debtorNoticeStatus === 'PENDING') {
                      <span class="dp-soon">pendente</span>
                    } @else {
                      {{ d.debtorNoticeStatus === 'SENT' ? 'enviada' : 'não se aplica' }}
                    }
                  </dd>
                }
              </dl>
            </section>
          }

          <section class="app-card" aria-labelledby="dp-creditor" data-tour="dispute-creditor-channel">
            <h2 class="app-card__title" id="dp-creditor">Canal com o credor</h2>
            <!-- um fio por pedido: o que foi pedido (pares rótulo/valor) e, recuadas logo abaixo, as respostas a ele -->
            @for (r of v.creditorRequests; track r.id) {
              <article class="dp-thread">
                <header class="dp-thread__head">
                  <strong>Pedido {{ r.sequenceNumber }}</strong>
                  <app-status-chip domain="disputeCreditorRequest" [status]="r.status" />
                  @if (r.overdue) {
                    <span class="dp-late">retorno vencido</span>
                  }
                  <span class="app-muted dp-thread__when">{{ r.createdAt | appDate }}{{ r.requestedByName ? ' · ' + r.requestedByName : '' }}</span>
                </header>
                <!-- o pedido é o e-mail que enviamos: mesmo formato dos recebidos, com a direção no título -->
                <article class="dp-mail dp-mail--out">
                  <header class="dp-thread__head">
                    <app-icon name="send" class="dp-mail__icon" />
                    <strong>{{ r.sentAt ? 'E-mail enviado ao credor' : 'E-mail ao credor (não enviado)' }}</strong>
                    @if (r.sentAt) {
                      <span class="app-muted dp-thread__when">{{ r.sentAt | appDate }}</span>
                    }
                  </header>
                  <dl class="app-definition-list dp-meta">
                    <dt>Para</dt>
                    <dd>{{ r.to.join(', ') }}</dd>
                    @if (r.cc.length) {
                      <dt>Cópia</dt>
                      <dd>{{ r.cc.join(', ') }}</dd>
                    }
                    <dt>Prazo pedido</dt>
                    <dd>{{ r.requestedDueDate | appDate: 'date' }}</dd>
                    @if (r.mails.length) {
                      <dt>Envios</dt>
                      <dd>
                        <ul class="dp-sends">
                          @for (m of r.mails; track m.id) {
                            <li>
                              {{ mailPurposes[m.purpose] }}: {{ mailStatuses[m.status] }}{{ m.sentAt ? ' em ' : '' }}{{ m.sentAt ? (m.sentAt | appDate) : '' }}{{ m.status === 'QUEUED' && m.attempts ? ' · ' + m.attempts + ' tentativa(s)' : '' }}
                            </li>
                          }
                        </ul>
                      </dd>
                    }
                    @if (r.evidenceIds.length) {
                      <dt>Evidências</dt>
                      <dd>
                        {{ r.evidenceIds.length }} por link{{ r.linkExpiresAt ? ' válido até ' : '' }}{{ r.linkExpiresAt ? (r.linkExpiresAt | appDate: 'date') : '' }} · {{ r.linkDownloads }} download(s)
                      </dd>
                    }
                  </dl>
                  <details class="dp-message">
                    <summary>Mensagem: {{ r.subject }}</summary>
                    <blockquote class="dp-quote" tabindex="0" aria-label="Texto do e-mail enviado ao credor">{{ r.body }}</blockquote>
                  </details>
                </article>
                <div class="dp-replies">
                  @for (resp of responsesTo(v, r.id); track resp.id) {
                    <app-creditor-response class="dp-reply" [response]="resp" />
                  }
                  <app-dispute-mail-replies [disputeId]="d.id" [requestIds]="[r.id]" [workable]="workable()" [canManage]="canManage()" [canWork]="canWork()" (changed)="reload()" />
                </div>
              </article>
            } @empty {
              <p class="app-muted mb-0">Nenhum pedido ao credor.{{ pending() && workable() ? ' Use "Solicitar ao credor" para pedir documentos ou posicionamento.' : '' }}</p>
            }
            <!-- respostas sem pedido (registradas direto ou pela API) e e-mails de pedidos que não estão na lista -->
            @for (resp of responsesTo(v, null); track resp.id) {
              <app-creditor-response class="dp-reply" [response]="resp" />
            }
            <app-dispute-mail-replies class="dp-replies-rest" [disputeId]="d.id" [excludeRequestIds]="requestIdList()" [workable]="workable()" [canManage]="canManage()" [canWork]="canWork()" (changed)="reload()" />

          </section>

          <section class="app-card" aria-labelledby="dp-evidence">
            <h2 class="app-card__title" id="dp-evidence">Evidências ({{ evidenceRows().length }})</h2>
            <ul class="dp-evidence">
              @for (e of evidenceRows(); track e.id) {
                <li>
                  <app-icon name="file-earmark-text" />
                  <span>{{ e.fileName }}</span>
                  @if (e.creditorResponseId || e.mailReplyId) {
                    <span class="badge rounded-pill bg-info-subtle text-info-emphasis">{{ e.mailReplyId ? 'do credor, por e-mail' : 'do credor' }}</span>
                  }
                  <span class="app-muted">{{ size(e) }} · {{ e.createdAt | appDate }}{{ e.createdByName ? ' · ' + e.createdByName : '' }}</span>
                  <span class="app-muted app-mono" [attr.title]="'SHA-256 ' + e.sha256">{{ e.sha256.slice(0, 12) }}…</span>
                  <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="download(d, e)" [attr.aria-label]="'Baixar ' + e.fileName + ' (acesso registrado)'">
                    <app-icon name="download" />
                  </button>
                </li>
              } @empty {
                <li class="app-muted">Nenhuma evidência anexada.</li>
              }
            </ul>
            @if (pending() && workable()) {
              <input #file type="file" [accept]="accept" (change)="attach(d, file.files); file.value = ''" hidden />
              <button class="btn btn-outline-primary" type="button" (click)="file.click()" [disabled]="busy()">
                <app-icon name="paperclip" />
                Anexar evidência
              </button>
            }
          </section>
        </div>

        <div class="app-stack">
          <section class="app-card" aria-labelledby="dp-notices">
            <h2 class="app-card__title" id="dp-notices">Respostas ao devedor</h2>
            @for (n of v.debtorNotices; track n.id) {
              <article class="dp-item">
                <header class="dp-item__head">
                  <strong>{{ channels[n.channel] }}</strong>
                  <app-status-chip domain="disputeNotice" [status]="n.status" />
                </header>
                <div class="app-muted">{{ n.createdAt | appDate }}{{ n.sentByName ? ' · ' + n.sentByName : '' }}</div>
                <details>
                  <summary>{{ n.subject || 'Texto da resposta' }}</summary>
                  <p class="dp-pre mb-0">{{ n.body }}</p>
                </details>
              </article>
            } @empty {
              <p class="app-muted mb-0">
                {{ d.debtorNoticeStatus === 'NOT_REQUIRED' ? 'Contestação aberta pelo credor ou jurídico: a resposta ao devedor não se aplica.' : 'Nenhuma resposta registrada. Ela é enviada após a resolução.' }}
              </p>
            }
          </section>

          <section class="app-card" aria-labelledby="dp-history">
            <h2 class="app-card__title" id="dp-history">Histórico de responsáveis</h2>
            <ol class="dp-history">
              @for (a of v.assignments; track a.id) {
                <li>
                  <span>{{ assignmentModes[a.mode] }}: {{ a.assigneeName ?? 'sem responsável' }}</span>
                  <span class="app-muted">{{ a.assignedAt | appDate }}{{ a.mode !== 'AUTOMATIC' && a.assignedByName ? ' · por ' + a.assignedByName : '' }}</span>
                  @if (a.reason) {
                    <span class="app-muted dp-pre">{{ a.reason }}</span>
                  }
                </li>
              } @empty {
                <li class="app-muted">Nunca atribuída.</li>
              }
            </ol>
          </section>
        </div>
      </div>
    }
  `,
  styles: `
    .dp-layout {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
      gap: var(--app-gap);
      @media (max-width: 991.98px) {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .dp-late {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
    .dp-soon {
      color: var(--app-warning-fg);
    }
    .dp-pre {
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .dp-list,
    .dp-evidence,
    .dp-history {
      display: grid;
      gap: 4px;
      margin: 4px 0 8px;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 8px;
      }
    }
    .dp-history li {
      flex-direction: column;
      align-items: flex-start;
      gap: 0;
    }
    .dp-item {
      padding: 8px 0;
      border-top: var(--bs-border-width) solid var(--app-border);
      &:first-of-type {
        border-top: 0;
        padding-top: 0;
      }
      details summary {
        cursor: pointer;
      }
    }
    /* canal com o credor: cada pedido é um cartão embutido (fundo terciário, sem borda dentro do card) e as
       respostas a ele vêm recuadas, com uma linha à esquerda que mostra a quem respondem */
    .dp-thread {
      display: grid;
      gap: 10px;
      margin-top: 12px;
      padding: 14px 16px;
      border-radius: var(--bs-border-radius);
      background: var(--bs-tertiary-bg);
    }
    .dp-thread__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 10px;
    }
    .dp-thread__when {
      margin-left: auto;
      font-size: var(--app-text-sm);
    }
    .dp-meta {
      gap: 4px 20px;
      font-size: var(--app-text-sm);
      dd {
        overflow-wrap: anywhere;
      }
    }
    .dp-sends {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    details summary {
      cursor: pointer;
    }
    /* e-mails do fio (enviado e recebidos): cartão branco com a linha da direção à esquerda */
    .dp-mail {
      display: grid;
      gap: 8px;
      padding: 12px 14px;
      border-left: 3px solid var(--bs-primary);
      border-radius: var(--bs-border-radius-sm);
      background: var(--bs-body-bg);
    }
    .dp-mail__icon {
      color: var(--bs-secondary-color);
    }
    /* texto do e-mail aberto: quadro de citação com altura limitada, para o fio continuar legível */
    .dp-quote {
      max-height: 320px;
      overflow: auto;
      margin: 12px 0 0;
      padding: 10px 12px;
      border-radius: var(--bs-border-radius-sm);
      background: var(--bs-tertiary-bg);
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .dp-replies {
      display: grid;
      gap: 10px;
    }
    /* e-mails de pedidos fora da lista: mesmo espaçamento dos fios, sem sobra quando não há nenhum */
    .dp-replies-rest:not(:empty) {
      margin-top: 12px;
    }
    .dp-reply {
      display: grid;
      gap: 8px;
      margin-top: 12px;
      padding: 12px 14px;
      border-left: 3px solid var(--app-border-strong);
      border-radius: var(--bs-border-radius-sm);
      background: var(--bs-body-bg);
    }
    .dp-replies .dp-reply {
      margin-top: 0;
    }
    .dp-item__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 8px;
    }
  `,
})
export class DisputePage {
  readonly id = input.required<string>();

  private readonly api = inject(DisputesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly payments = inject(PaymentLauncher);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly types = DISPUTE_TYPE_LABELS;
  protected readonly origins = DISPUTE_ORIGIN_LABELS;
  protected readonly outcomes = DISPUTE_OUTCOME_LABELS;
  protected readonly deadlineSources = DEADLINE_SOURCE_LABELS;
  protected readonly adjustmentLabels = ADJUSTMENT_ACTION_LABELS;
  protected readonly mailPurposes = MAIL_PURPOSE_LABELS;
  protected readonly mailStatuses = MAIL_STATUS_LABELS;

  /** Respostas registradas para um pedido (`null`: as sem pedido, mostradas depois dos fios). */
  protected responsesTo(v: DisputeDetail, requestId: string | null): CreditorResponseView[] {
    return v.creditorResponses.filter((r) => (requestId === null ? !r.requestId || !v.creditorRequests.some((q) => q.id === r.requestId) : r.requestId === requestId));
  }

  protected readonly channels = NOTICE_CHANNEL_LABELS;
  protected readonly assignmentModes = DISPUTE_ASSIGNMENT_MODE_LABELS;
  protected readonly accept = EVIDENCE_ACCEPT;
  private readonly today = zonedNow().date;

  protected readonly detail = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.detail(params) });
  /** Pedidos com fio próprio: os e-mails deles não se repetem no bloco final. */
  protected readonly requestIdList = computed(() => this.detail.value()?.creditorRequests.map((r) => r.id) ?? []);
  protected readonly view = valueOr(this.detail, undefined);
  private readonly evidences = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.evidences(params) });
  protected readonly evidenceRows = valueOr(this.evidences, []);

  protected readonly busy = signal(false);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.DISPUTE_MANAGE));
  protected readonly canWork = computed(() => this.auth.hasAnyPermission([Permission.DISPUTE_MANAGE, Permission.DISPUTE_WORK]));
  protected readonly canAssign = this.canManage;
  private readonly dispute = computed(() => this.view()?.dispute);
  protected readonly pending = computed(() => {
    const d = this.dispute();
    return !!d && isPending(d);
  });
  protected readonly workable = computed(() => {
    const d = this.dispute();
    return !!d && canWorkDispute(d, this.auth.user()?.id ?? null, this.canManage(), this.auth.hasPermission(Permission.DISPUTE_WORK));
  });
  protected readonly actions = computed(() => disputeActions(this.dispute()?.status ?? ''));
  protected readonly protocol = computed(() => protocolOf(this.id()));
  protected readonly nearest = computed(() => {
    const d = this.dispute();
    return d ? nearestDue(d) : '';
  });
  protected readonly tone = computed(() => {
    const d = this.dispute();
    return d ? dueTone(d, this.today) : 'ok';
  });
  protected readonly dueLabel = computed(() => {
    const d = this.dispute();
    return d ? dueText(daysBetween(this.today, nearestDue(d)), d.overdue) : '';
  });

  protected readonly debtors = nameMap(inject(DebtorNames), () => [this.dispute()?.debtorId]);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.dispute()?.creditorId]);

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.view() ? `Contestação ${this.protocol()}` : null));
  }

  protected size(e: EvidenceView): string {
    return e.size >= 1024 * 1024 ? `${(e.size / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(e.size / 1024))} KB`;
  }

  protected download(d: DisputeView, e: EvidenceView): void {
    this.api.downloadEvidence(d.id, e).subscribe();
  }

  protected attach(d: DisputeView, list: FileList | null): void {
    const file = list?.[0];
    if (!file) {
      return;
    }
    const problem = evidenceProblem(file);
    if (problem) {
      this.notifications.error(problem);
      return;
    }
    this.busy.set(true);
    this.api
      .attachEvidence(d.id, file)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe(() => {
        this.notifications.success('Evidência anexada.');
        this.reload();
      });
  }

  protected take(d: DisputeView): void {
    this.run(this.api.take(d.id), 'Contestação atribuída a você.');
  }

  protected assign(d: DisputeView): void {
    this.dialog
      .open<AssignDisputeDialog, AssignDisputeData, DisputeView>(AssignDisputeDialog, { data: { dispute: d } })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.notifications.success(updated.assigneeName ? `Contestação atribuída a ${updated.assigneeName}.` : 'Contestação devolvida à fila.');
          this.reload();
        }
      });
  }

  protected requestCreditor(d: DisputeView): void {
    this.dialog
      .open<CreditorRequestDialog, CreditorRequestData, unknown>(CreditorRequestDialog, { data: { disputeId: d.id, creditorName: this.creditors.name(d.creditorId) }, size: 'lg' })
      .afterClosed()
      .subscribe((r) => r && this.reload());
  }

  protected creditorResponse(v: DisputeDetail): void {
    const open = v.creditorRequests.filter((r) => r.status === 'SENT' || r.status === 'QUEUED' || r.status === 'FAILED');
    this.dialog
      .open<CreditorResponseDialog, CreditorResponseData, unknown>(CreditorResponseDialog, { data: { disputeId: v.dispute.id, requests: open } })
      .afterClosed()
      .subscribe((r) => r && this.reload());
  }

  protected resolve(d: DisputeView): void {
    this.dialog
      .open<ResolveDisputeDialog, ResolveDisputeData, DisputeView>(ResolveDisputeDialog, { data: { dispute: d, debtorName: this.debtors.name(d.debtorId) } })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.notifications.success(updated.debtorNoticeStatus === 'PENDING' ? 'Contestação resolvida. Falta responder ao devedor.' : 'Contestação resolvida.');
          this.reload();
        }
      });
  }

  protected notifyDebtor(d: DisputeView): void {
    this.dialog
      .open<DebtorNoticeDialog, DebtorNoticeData, unknown>(DebtorNoticeDialog, { data: { disputeId: d.id, debtorName: this.debtors.name(d.debtorId) }, size: 'lg' })
      .afterClosed()
      .subscribe((n) => n && this.reload());
  }

  protected review(d: DisputeView): void {
    this.run(this.api.review(d.id), 'Contestação enviada para análise.');
  }

  protected withdraw(d: DisputeView): void {
    this.confirm
      .confirm({
        title: 'Registrar desistência',
        message: 'O devedor (ou quem abriu) desistiu da contestação. A cobrança dos títulos é retomada e os pedidos ao credor em aberto são cancelados.',
        confirmLabel: 'Registrar desistência',
        reason: 'required',
        reasonLabel: 'Parecer',
        details: [{ label: 'Devedor', value: this.debtors.name(d.debtorId) }],
      })
      .subscribe((note) => this.run(this.api.withdraw(d.id, note), 'Desistência registrada.'));
  }

  protected canAdjust(d: DisputeView): boolean {
    if (d.adjustmentAction === 'SETTLE_BY_PAYMENT') {
      return this.auth.hasPermission(Permission.PAYMENT_REGISTER_EXTERNAL);
    }
    if (d.adjustmentAction === 'CANCEL_TITLES') {
      return this.auth.hasPermission(Permission.DEBT_TITLE_MANAGE);
    }
    return this.auth.hasPermission(Permission.TITLE_AMOUNT_ADJUST);
  }

  protected adjustmentCta(d: DisputeView): string {
    return d.adjustmentAction === 'SETTLE_BY_PAYMENT' ? 'Informar pagamento' : d.adjustmentAction === 'CANCEL_TITLES' ? 'Cancelar no título' : 'Corrigir o valor no título';
  }

  /** Atalho para o fluxo existente; a pendência é concluída pelo servidor quando o efeito aparece no título. */
  protected adjust(d: DisputeView): void {
    const shortcut = d.adjustmentAction ? adjustmentShortcut(d.adjustmentAction, d.titleIds, this.protocol(), d.id) : null;
    if (!shortcut) {
      return;
    }
    if (shortcut.kind === 'payment') {
      this.payments
        .external({ debtorId: d.debtorId, debtorName: this.debtors.name(d.debtorId), initialKind: 'TITLES', titleIds: shortcut.titleIds })
        .subscribe((p) => p && this.reload());
      return;
    }
    void this.router.navigate(['/titulos', shortcut.titleId], { queryParams: shortcut.queryParams });
  }

  protected dismissAdjustment(d: DisputeView): void {
    this.confirm
      .confirm({
        title: 'Dispensar ajuste no título',
        message: 'A pendência de ajuste é encerrada sem alterar o título. O motivo fica registrado na contestação e na auditoria.',
        confirmLabel: 'Dispensar',
        reason: 'required',
        details: [{ label: 'Ajuste sugerido', value: d.adjustmentAction ? ADJUSTMENT_ACTION_LABELS[d.adjustmentAction] : '—' }],
      })
      .subscribe((note) => this.run(this.api.dismissAdjustment(d.id, note), 'Ajuste dispensado.'));
  }

  private run(call: Observable<DisputeView>, message: string): void {
    this.busy.set(true);
    call.pipe(finalize(() => this.busy.set(false))).subscribe(() => {
      this.notifications.success(message);
      this.reload();
    });
  }

  protected reload(): void {
    this.detail.reload();
    this.evidences.reload();
  }
}
