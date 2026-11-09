import { Component, Type, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { MoreAction, MoreActions } from '../../../shared/components/more-actions/more-actions';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import {
  CHANNEL_LABELS,
  ComplaintView,
  ComplaintsApi,
  DEADLINE_SOURCE_LABELS,
  EvidenceView,
  OUTCOME_LABELS,
  STATUS_LABELS,
  SUBJECT_LABELS,
  deadlineText,
  deadlineTone,
} from './complaint.data';
import {
  AnswerComplaintDialog,
  AssignComplaintDialog,
  CloseComplaintDialog,
  ComplaintDialogData,
  DueDateComplaintDialog,
  ReclassifyComplaintDialog,
} from './complaint-dialogs';
import { COMPLAINT_EVIDENCE_ACCEPT, EVIDENCE_STATUS_LABELS, complaintActions, complaintEvidenceProblem, primaryAction } from './complaint.rules';

/**
 * Ficha da reclamação: dados e prazo, texto da reclamação e da resposta ao canal (só aqui; a abertura registra
 * acesso a dado pessoal), evidências em quarentena e o tratamento (começar, responder, encerrar) para quem trata.
 */
@Component({
  selector: 'app-complaint-detail',
  imports: [RouterLink, PageHeader, EmptyState, Skeleton, MoreActions, AppDatePipe, Icon],
  template: `
    @if (detail.isLoading() && !current()) {
      <div aria-busy="true" aria-label="Carregando reclamação">
        <app-skeleton variant="lines" [rows]="2" />
        <app-skeleton variant="card" [rows]="6" />
      </div>
    } @else if (detail.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a reclamação.">
        <button class="btn btn-outline-primary" type="button" (click)="detail.reload()">Tentar novamente</button>
        <a class="btn btn-link" routerLink="/compliance/reclamacoes">Voltar para a fila</a>
      </app-empty-state>
    }

    @if (current(); as d) {
      @let c = d.complaint;
      @let primary = primaryOf(c);
      <app-page-header [title]="channelLabels[c.channel] + (c.externalProtocol ? ' · ' + c.externalProtocol : '')" [subtitle]="subjectLabels[c.subject] + ' · recebida em ' + (c.receivedAt | appDate)">
        <span class="d-inline-flex flex-wrap gap-2" data-tour="complaint-actions">
          <app-more-actions [actions]="moreActions()" />
          @if (canManage() && primary === 'start') {
            <button class="btn btn-outline-secondary" type="button" (click)="open(answerDialog, c, 'Resposta registrada.')">
              <app-icon name="send" />
              Registrar resposta
            </button>
            <button class="btn btn-primary" type="button" (click)="start(c)" [disabled]="busy()">
              <app-icon name="play" />
              Começar tratamento
            </button>
          }
          @if (canManage() && primary === 'answer') {
            <button class="btn btn-primary" type="button" (click)="open(answerDialog, c, 'Resposta registrada.')">
              <app-icon name="send" />
              Registrar resposta
            </button>
          }
          @if (canManage() && primary === 'close') {
            <button class="btn btn-primary" type="button" (click)="open(closeDialog, c, 'Reclamação encerrada.')">
              <app-icon name="check2-circle" />
              Encerrar
            </button>
          }
        </span>
      </app-page-header>

      @if (c.deadlineState === 'OVERDUE') {
        <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">
          <app-icon name="alarm" />
          <span>Prazo de resposta vencido em {{ c.dueDate | appDate: 'date' }}. Responda ao canal e registre a resposta aqui.</span>
        </div>
      } @else if (c.deadlineState === 'DUE_SOON') {
        <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
          <app-icon name="clock" />
          <span>{{ deadline(c) }}: o prazo de resposta é {{ c.dueDate | appDate: 'date' }}.</span>
        </div>
      }

      <section class="app-card" aria-labelledby="cd-dados">
        <h2 class="app-card__title" id="cd-dados">Reclamação</h2>
        <dl class="app-definition-list">
          <dt>Situação</dt>
          <dd>
            {{ statusLabels[c.status] }}
            @if (c.outcome) {
              · {{ outcomeLabels[c.outcome] }}
            }
          </dd>
          <dt>Canal</dt>
          <dd>{{ channelLabels[c.channel] }}</dd>
          <dt>Protocolo do canal</dt>
          <dd>{{ c.externalProtocol || '—' }}</dd>
          <dt>Assunto</dt>
          <dd>{{ subjectLabels[c.subject] }}</dd>
          <dt>Credor</dt>
          <dd>{{ creditors.name(c.creditorId) }}</dd>
          <dt>Devedor</dt>
          <dd>
            @if (c.debtorId) {
              @if (canOpenDebtor()) {
                <a [routerLink]="['/cobranca/devedores', c.debtorId]">{{ debtors.name(c.debtorId) }}</a>
              } @else {
                {{ debtors.name(c.debtorId) }}
              }
              @if (c.caseId && canOpenCase()) {
                · <a [routerLink]="['/cobranca/casos', c.caseId]">abrir o caso</a>
              }
            } @else {
              <span class="app-muted">Sem devedor vinculado</span>
            }
          </dd>
          <dt>Recebida em</dt>
          <dd>{{ c.receivedAt | appDate }}</dd>
          <dt>Prazo de resposta</dt>
          <dd>
            <span [class.cd-late]="tone(c) === 'late'" [class.cd-soon]="tone(c) === 'soon'">{{ c.dueDate | appDate: 'date' }} · {{ deadline(c) }}</span>
            <div class="app-muted">{{ sourceLabels[c.deadlineSource] }}</div>
          </dd>
          <dt>Responsável</dt>
          <dd>{{ c.assigneeName || 'Sem responsável' }}</dd>
          <dt>Registrada por</dt>
          <dd>{{ d.registeredByName || '—' }}</dd>
        </dl>
        <h3 class="h6 mt-3">O que o consumidor relatou</h3>
        <p class="cd-text">{{ d.summary }}</p>
        <p class="app-muted small mb-0"><app-icon name="shield-check" /> A abertura deste texto fica registrada no log de acesso.</p>
      </section>

      <section class="app-card" aria-labelledby="cd-resposta">
        <h2 class="app-card__title" id="cd-resposta">Resposta ao canal</h2>
        @if (d.response) {
          <p class="cd-text">{{ d.response }}</p>
          <p class="app-muted mb-0">
            Enviada em {{ c.answeredAt | appDate }}{{ d.answeredByName ? ' por ' + d.answeredByName : '' }} ·
            <span [class.cd-late]="c.answeredOnTime === false">{{ c.answeredOnTime ? 'no prazo' : 'fora do prazo' }}</span>
          </p>
        } @else {
          <p class="app-muted mb-0">Ainda sem resposta registrada.</p>
        }
      </section>

      <section class="app-card" aria-labelledby="cd-evidencias">
        <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
          <h2 class="app-card__title mb-0" id="cd-evidencias">Evidências</h2>
          @if (canRegister() && c.status !== 'CLOSED') {
            <label class="btn btn-outline-secondary btn-sm mb-0" [class.disabled]="uploading()">
              <app-icon name="paperclip" />
              Anexar PDF ou imagem
              <input type="file" class="visually-hidden" [accept]="accept" (change)="attach(c, $event)" [disabled]="uploading()" />
            </label>
          }
        </div>
        <p class="app-muted small">Notificação do órgão, prints e a resposta enviada. PDF, PNG ou JPEG até 10 MB; cada arquivo passa por verificação antes de liberar.</p>
        @if (evidenceList().length) {
          <ul class="list-unstyled mb-0 cd-evidences">
            @for (e of evidenceList(); track e.id) {
              <li class="d-flex flex-wrap align-items-center gap-2">
                <span class="flex-grow-1">
                  {{ e.fileName }}
                  <span class="app-muted small">· {{ e.createdAt | appDate }}{{ e.createdByName ? ' · ' + e.createdByName : '' }}</span>
                </span>
                <span class="badge rounded-pill" [class]="evidenceBadge(e)">{{ evidenceStatus[e.status] }}</span>
                @if (e.status === 'AVAILABLE') {
                  <button class="btn btn-sm btn-outline-secondary" type="button" (click)="download(c, e)" [attr.aria-label]="'Baixar ' + e.fileName">
                    <app-icon name="download" />
                  </button>
                }
              </li>
            }
          </ul>
        } @else {
          <p class="app-muted mb-0">Nenhuma evidência anexada.</p>
        }
      </section>
    }
  `,
  styles: `
    .cd-text {
      white-space: pre-wrap;
    }
    .cd-late {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
    .cd-soon {
      color: var(--app-warning-fg);
    }
    .cd-evidences li + li {
      margin-top: 8px;
    }
  `,
})
export class ComplaintDetailPage {
  readonly id = input.required<string>();

  private readonly api = inject(ComplaintsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly subjectLabels = SUBJECT_LABELS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly outcomeLabels = OUTCOME_LABELS;
  protected readonly sourceLabels = DEADLINE_SOURCE_LABELS;
  protected readonly evidenceStatus = EVIDENCE_STATUS_LABELS;
  protected readonly accept = COMPLAINT_EVIDENCE_ACCEPT;
  protected readonly answerDialog = AnswerComplaintDialog;
  protected readonly closeDialog = CloseComplaintDialog;

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.COMPLAINT_MANAGE));
  protected readonly canRegister = computed(() =>
    this.auth.hasAnyPermission([Permission.COMPLAINT_REGISTER, Permission.COMPLAINT_MANAGE]),
  );
  protected readonly canOpenDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  protected readonly canOpenCase = computed(() => this.auth.hasPermission(Permission.CASE_VIEW));

  protected readonly detail = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.detail(params) });
  protected readonly current = valueOr(this.detail, undefined);
  private readonly evidences = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.evidences(params) });
  protected readonly evidenceList = valueOr(this.evidences, []);
  protected readonly busy = signal(false);
  protected readonly uploading = signal(false);

  protected readonly debtors = nameMap(inject(DebtorNames), () => [this.current()?.complaint.debtorId]);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.current()?.complaint.creditorId]);

  /** Ações além da primária (§12.2), só para quem trata e conforme a situação. */
  protected readonly moreActions = computed<MoreAction[]>(() => {
    const c = this.current()?.complaint;
    if (!c || !this.canManage()) {
      return [];
    }
    const allowed = complaintActions(c.status);
    const actions: MoreAction[] = [];
    if (allowed.includes('assign')) {
      actions.push({ id: 'assign', label: 'Atribuir responsável', icon: 'person-plus', run: () => this.open(AssignComplaintDialog, c, 'Responsável atualizado.') });
    }
    if (allowed.includes('dueDate')) {
      actions.push({ id: 'due', label: 'Alterar prazo', icon: 'calendar-event', hint: 'prorrogação informada pelo canal', run: () => this.open(DueDateComplaintDialog, c, 'Prazo alterado.') });
    }
    if (allowed.includes('reclassify')) {
      actions.push({ id: 'reclassify', label: 'Corrigir assunto e protocolo', icon: 'pencil', run: () => this.open(ReclassifyComplaintDialog, c, 'Reclamação corrigida.') });
    }
    return actions;
  });

  constructor() {
    effect(() => {
      const c = this.current()?.complaint;
      this.breadcrumbs.setCurrentLabel(c ? `${CHANNEL_LABELS[c.channel]}${c.externalProtocol ? ' ' + c.externalProtocol : ''}` : null);
    });
  }

  protected primaryOf(c: ComplaintView): 'start' | 'answer' | 'close' | null {
    return primaryAction(c.status);
  }

  protected deadline(c: ComplaintView): string {
    return deadlineText(c);
  }

  protected tone(c: ComplaintView): 'late' | 'soon' | 'ok' {
    return deadlineTone(c.deadlineState);
  }

  protected evidenceBadge(e: EvidenceView): string {
    return e.status === 'AVAILABLE' ? 'text-bg-success' : e.status === 'PENDING_SCAN' ? 'text-bg-secondary' : 'text-bg-danger';
  }

  protected start(c: ComplaintView): void {
    this.busy.set(true);
    this.api
      .start(c.id)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => {
          this.notifications.success('Tratamento iniciado.');
          this.detail.reload();
        },
        error: (e) => this.notifications.error(userMessage(toApiError(e))),
      });
  }

  protected open<C>(component: Type<C>, c: ComplaintView, done: string): void {
    this.dialog
      .open<C, ComplaintDialogData, ComplaintView>(component, { data: { complaint: c } })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.notifications.success(done);
          this.detail.reload();
        }
      });
  }

  protected attach(c: ComplaintView, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    const problem = complaintEvidenceProblem(file);
    if (problem) {
      this.notifications.error(problem);
      return;
    }
    this.uploading.set(true);
    this.api
      .attach(c.id, file)
      .pipe(finalize(() => this.uploading.set(false)))
      .subscribe({
        next: () => {
          this.notifications.success('Evidência recebida. Fica disponível depois da verificação.');
          this.evidences.reload();
        },
        error: (e) => this.notifications.error(userMessage(toApiError(e))),
      });
  }

  protected download(c: ComplaintView, e: EvidenceView): void {
    this.api.download(c.id, e).subscribe({ error: (err) => this.notifications.error(userMessage(toApiError(err))) });
  }
}
