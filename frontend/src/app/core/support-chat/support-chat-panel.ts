import { Component, DestroyRef, ElementRef, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NavigationStart, Router, RouterLink } from '@angular/router';
import { NgbActiveOffcanvas } from '@ng-bootstrap/ng-bootstrap';
import { filter } from 'rxjs';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { StatusDomain } from '../../shared/status/status';
import { HelpService } from '../layout/help/help.service';
import { ManualService } from '../onboarding/manual/manual.service';
import { OnboardingService } from '../onboarding/onboarding.service';
import {
  DOWN_REASONS,
  SUPPORT_QUESTION_MAX,
  SupportAction,
  SupportCard,
  SupportCardKind,
  SupportDownReason,
  SupportMessage,
  SupportTicket,
} from './support-chat.models';
import { SupportChatStore } from './support-chat.store';

export const SUPPORT_CHAT_TITLE_ID = 'support-chat-title';

const CARD_DOMAIN: Partial<Record<SupportCardKind, StatusDomain>> = {
  DEBTOR: 'debtor',
  CASE: 'case',
  AGREEMENT: 'agreement',
  PAYMENT: 'payment',
  IMPORT: 'importBatch',
  SETTLEMENT_STATEMENT: 'settlementStatement',
  COMMISSION_STATEMENT: 'commissionStatement',
};

const SEVERITY_LABEL: Record<string, string> = { OVERDUE: 'Vencida', DUE_TODAY: 'Vence hoje', NORMAL: 'No prazo' };

const SUGGESTIONS: readonly string[] = ['Como uso esta tela?', 'Quais são as minhas pendências?', 'Quero fazer o tour desta tela'];

/**
 * Chat de suporte com IA num painel lateral (`NgbOffcanvas` à direita, como "Sobre esta tela"; largura
 * total no celular). A conversa fica na `SupportChatStore` e continua ao reabrir o painel.
 *
 * Acessibilidade (WCAG 2.2 AA): histórico em `role="log"`; cada resposta nova é anunciada numa região
 * `aria-live="polite"` dentro do próprio painel (fora dele um leitor de tela ignoraria, porque o painel
 * é modal); o foco começa na caixa de pergunta e volta para ela depois de cada resposta; botões de
 * avaliação com `aria-pressed`; o chamado só é enviado depois que a pessoa lê o texto exato e confirma.
 */
@Component({
  selector: 'app-support-chat-panel',
  imports: [FormsModule, RouterLink, Icon, EmptyState, StatusChip],
  template: `
    <div class="offcanvas-header chat__header">
      <div class="chat__heading">
        <p class="chat__eyebrow"><app-icon name="robot" /> Assistente de IA</p>
        <h2 class="offcanvas-title h5" [id]="titleId">Chat de suporte</h2>
        <p class="chat__sub">Respostas geradas por IA podem conter erros. Ele consulta só o que você pode ver.</p>
      </div>
      <div class="chat__header-actions">
        @if (!store.empty()) {
          <button type="button" class="btn btn-sm btn-outline-secondary" (click)="newConversation()">
            <app-icon name="plus-lg" /> Nova conversa
          </button>
        }
        <button type="button" class="btn-close" aria-label="Fechar o chat" (click)="offcanvas.dismiss()"></button>
      </div>
    </div>

    <div class="offcanvas-body chat__body" #scroller>
      @switch (store.availability()) {
        @case ('unavailable') {
          <app-empty-state
            icon="chat-dots"
            title="O chat de suporte não está disponível"
            message="Neste ambiente o chat está desligado. Use o manual do sistema ou procure o administrador da assessoria."
          >
            <a class="btn btn-primary btn-sm" routerLink="/ajuda" (click)="offcanvas.close()">
              <app-icon name="journal-text" /> Abrir o manual
            </a>
          </app-empty-state>
        }
        @case ('error') {
          <app-empty-state icon="exclamation-triangle" tone="error" title="Não foi possível falar com o suporte" message="Verifique a conexão e tente de novo.">
            <button type="button" class="btn btn-outline-primary btn-sm" (click)="store.loadStatus()">Tentar novamente</button>
          </app-empty-state>
        }
        @default {
          @if (store.empty()) {
            <div class="chat__intro">
              <p>Olá! Pergunte como fazer algo no sistema ou peça a situação de um acordo, pagamento ou importação.</p>
              @if (screenTitle(); as t) {
                <p class="chat__context"><app-icon name="geo-alt" /> Você está em: <strong>{{ t }}</strong></p>
              }
              <div class="chat__suggestions" role="group" aria-label="Sugestões de pergunta">
                @for (s of suggestions; track s) {
                  <button type="button" class="btn btn-sm btn-outline-primary" (click)="ask(s)" [disabled]="store.sending()">{{ s }}</button>
                }
              </div>
            </div>
          }
          <ol class="chat__log" role="log" aria-label="Conversa com o suporte">
            @for (m of store.messages(); track m.id) {
              <li class="chat__msg" [class.chat__msg--user]="m.role === 'USER'" [class.chat__msg--bot]="m.role === 'ASSISTANT'">
                <span class="visually-hidden">{{ m.role === 'USER' ? 'Você:' : 'Suporte:' }}</span>
                @if (m.purged) {
                  <p class="chat__text chat__text--muted">Conteúdo removido pela política de retenção.</p>
                } @else {
                  <p class="chat__text">{{ m.text }}</p>
                }
                @if (m.cards.length) {
                  <ul class="list-group chat__cards" [attr.aria-label]="'Registros encontrados (' + m.cards.length + ')'">
                    @for (c of m.cards; track $index) {
                      <li class="list-group-item chat__card">
                        <div class="chat__card-head">
                          @if (c.ref) {
                            <span class="badge text-bg-secondary chat__ref" [attr.aria-label]="'Referência ' + c.ref">{{ c.ref }}</span>
                          }
                          <span class="chat__card-title">{{ c.title }}</span>
                          @if (domain(c); as d) {
                            <app-status-chip [domain]="d" [status]="c.status" />
                          } @else if (c.status) {
                            <span class="badge rounded-pill text-bg-light border">{{ severity(c.status) }}</span>
                          }
                        </div>
                        @if (c.subtitle) {
                          <div class="chat__card-sub">{{ c.subtitle }}</div>
                        }
                        @if (c.fields.length) {
                          <dl class="chat__fields">
                            @for (f of c.fields; track f.label) {
                              <div><dt>{{ f.label }}</dt><dd>{{ f.value }}</dd></div>
                            }
                          </dl>
                        }
                        @if (c.route) {
                          <a class="chat__card-link" [routerLink]="c.route" [queryParams]="c.queryParams" (click)="offcanvas.close()">
                            Abrir <span class="visually-hidden">{{ c.title }}</span><app-icon name="arrow-right" />
                          </a>
                        }
                      </li>
                    }
                  </ul>
                }
                @if (m.actions.length) {
                  <div class="chat__actions">
                    @for (a of m.actions; track a.kind + a.screen) {
                      @switch (a.kind) {
                        @case ('OPEN_SCREEN') {
                          @if (openRoute(a); as route) {
                            <a class="btn btn-sm btn-outline-primary" [routerLink]="route" (click)="offcanvas.close()">
                              <app-icon name="box-arrow-up-right" /> Abrir: {{ a.label }}
                            </a>
                          }
                        }
                        @case ('START_TOUR') {
                          <button type="button" class="btn btn-sm btn-outline-primary" (click)="startTour()">
                            <app-icon name="signpost" /> Fazer o tour desta tela
                          </button>
                        }
                        @case ('ABOUT_SCREEN') {
                          <button type="button" class="btn btn-sm btn-outline-primary" (click)="aboutScreen()">
                            <app-icon name="info-circle" /> Sobre esta tela
                          </button>
                        }
                      }
                    }
                  </div>
                }
                @if (m.ticket; as t) {
                  <section class="chat__ticket" [attr.aria-labelledby]="'tk-' + t.id">
                    <h3 class="chat__ticket-title" [id]="'tk-' + t.id"><app-icon name="github" /> Chamado para a equipe do sistema</h3>
                    @switch (t.status) {
                      @case ('OPENED') {
                        <p class="alert alert-success mb-0" role="status">
                          Chamado nº {{ t.issueNumber }} aberto. O retorno será dado por e-mail.
                          @if (t.issueUrl) {
                            <a [href]="t.issueUrl" target="_blank" rel="noopener noreferrer">Ver no GitHub</a>
                          }
                        </p>
                      }
                      @case ('DUPLICATE') {
                        <p class="alert alert-info mb-0" role="status">
                          Este relato já tinha sido enviado (chamado nº {{ t.issueNumber }}). O retorno será dado por e-mail.
                        </p>
                      }
                      @case ('CANCELLED') {
                        <p class="chat__text--muted mb-0">Chamado não enviado.</p>
                      }
                      @case ('EXPIRED') {
                        <p class="chat__text--muted mb-0">O rascunho expirou. Peça ao chat para preparar de novo, se ainda precisar.</p>
                      }
                      @default {
                        <p class="chat__ticket-hint">Confira o texto exato que vai para o GitHub. Dados de devedores foram mascarados. Nada é enviado sem a sua confirmação.</p>
                        <p class="chat__ticket-subject">{{ t.title }}</p>
                        <pre class="chat__ticket-body" tabindex="0" [attr.aria-label]="'Texto do chamado: ' + t.title">{{ t.body }}</pre>
                        @if (store.ticketError(t.id); as err) {
                          <p class="invalid-feedback d-block" role="alert">{{ err }}</p>
                        }
                        <div class="chat__ticket-actions">
                          <button type="button" class="btn btn-sm btn-link" (click)="cancelTicket(t)" [disabled]="store.ticketBusy(t.id)">Não enviar</button>
                          <button type="button" class="btn btn-sm btn-primary" (click)="confirmTicket(t)" [disabled]="store.ticketBusy(t.id)">
                            @if (store.ticketBusy(t.id)) {
                              <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
                            }
                            Enviar chamado
                          </button>
                        </div>
                      }
                    }
                  </section>
                }
                @if (m.role === 'ASSISTANT' && !m.purged) {
                  <div class="chat__feedback" role="group" aria-label="Avaliar esta resposta">
                    <button type="button" class="btn btn-sm chat__rate" [attr.aria-pressed]="m.feedback === 'UP'" aria-label="Resposta útil" (click)="rate(m, 'UP')">
                      <app-icon [name]="m.feedback === 'UP' ? 'hand-thumbs-up-fill' : 'hand-thumbs-up'" />
                    </button>
                    <button type="button" class="btn btn-sm chat__rate" [attr.aria-pressed]="m.feedback === 'DOWN'" aria-label="Resposta não ajudou" (click)="rate(m, 'DOWN')">
                      <app-icon [name]="m.feedback === 'DOWN' ? 'hand-thumbs-down-fill' : 'hand-thumbs-down'" />
                    </button>
                    @if (reasonFor() === m.id) {
                      <span class="chat__reasons" role="group" aria-label="O que houve com a resposta?">
                        @for (r of reasons; track r.value) {
                          <button type="button" class="btn btn-sm btn-outline-secondary" (click)="rate(m, 'DOWN', r.value)">{{ r.label }}</button>
                        }
                      </span>
                    }
                  </div>
                }
              </li>
            }
          </ol>
          @if (store.sending()) {
            <p class="chat__typing"><span class="spinner-border spinner-border-sm" aria-hidden="true"></span> O suporte está respondendo…</p>
          }
        }
      }
    </div>

    @if (store.availability() !== 'unavailable') {
      <form class="chat__composer" (ngSubmit)="submit()">
        @if (store.error(); as err) {
          <p class="alert alert-warning py-2 px-3 mb-2" role="alert">{{ err }}</p>
        }
        <label class="visually-hidden" for="support-chat-input">Sua pergunta para o suporte</label>
        <div class="chat__input-row">
          <textarea
            #input
            id="support-chat-input"
            class="form-control"
            name="question"
            rows="2"
            ngbAutofocus
            [maxlength]="max"
            [(ngModel)]="draft"
            (keydown.enter)="enter($event)"
            aria-describedby="support-chat-hint"
            placeholder="Escreva sua dúvida"
          ></textarea>
          <button type="submit" class="btn btn-primary" [disabled]="!canSend()" aria-label="Enviar pergunta">
            <app-icon name="send" />
          </button>
        </div>
        <p class="chat__hint" id="support-chat-hint">Enter envia, Shift + Enter quebra a linha · {{ draft().length }}/{{ max }}</p>
      </form>
    }
    <div class="visually-hidden" aria-live="polite" aria-atomic="true">{{ announcement() }}</div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
    }
    .chat__header {
      align-items: flex-start;
      gap: var(--app-space-2);
      border-bottom: var(--bs-border-width) solid var(--bs-border-color);
    }
    .chat__heading {
      min-width: 0;
    }
    .chat__header-actions {
      display: flex;
      flex-shrink: 0;
      white-space: nowrap;
      gap: var(--app-space-2);
      align-items: center;
    }
    .chat__eyebrow {
      margin: 0 0 2px;
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .chat__sub {
      margin: 2px 0 0;
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .chat__body {
      flex: 1 1 auto;
      overflow-y: auto;
    }
    .chat__intro {
      color: var(--bs-secondary-color);
    }
    .chat__context {
      font-size: var(--app-text-sm);
    }
    .chat__suggestions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--app-space-2);
      margin-bottom: var(--app-space-3);
    }
    .chat__log {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--app-space-3);
    }
    .chat__msg {
      max-width: 92%;
      padding: var(--app-space-2) var(--app-space-3);
      border-radius: var(--bs-border-radius-lg);
    }
    .chat__msg--user {
      align-self: flex-end;
      background: var(--bs-primary-bg-subtle);
      color: var(--bs-primary-text-emphasis);
    }
    .chat__msg--bot {
      align-self: flex-start;
      background: var(--bs-tertiary-bg);
      border: var(--bs-border-width) solid var(--bs-border-color-translucent);
    }
    .chat__text {
      margin: 0;
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .chat__text--muted {
      color: var(--bs-secondary-color);
      font-style: italic;
    }
    .chat__cards {
      margin-top: var(--app-space-2);
    }
    .chat__card-head {
      display: flex;
      flex-wrap: wrap;
      gap: var(--app-space-2);
      align-items: center;
    }
    .chat__card-title {
      font-weight: 600;
    }
    .chat__card-sub,
    .chat__fields {
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
    .chat__fields {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(9rem, 1fr));
      gap: 2px var(--app-space-3);
      margin: var(--app-space-1) 0 0;
    }
    .chat__fields dt {
      font-weight: 500;
    }
    .chat__fields dd {
      margin: 0;
      color: var(--bs-body-color);
      font-variant-numeric: tabular-nums;
    }
    .chat__card-link {
      display: inline-flex;
      gap: var(--app-space-1);
      align-items: center;
      margin-top: var(--app-space-1);
      font-size: var(--app-text-sm);
    }
    .chat__actions,
    .chat__feedback,
    .chat__reasons,
    .chat__ticket-actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--app-space-2);
      align-items: center;
    }
    .chat__actions {
      margin-top: var(--app-space-2);
    }
    .chat__feedback {
      margin-top: var(--app-space-1);
    }
    .chat__rate {
      --bs-btn-padding-x: 0.4rem;
      --bs-btn-color: var(--bs-secondary-color);
      min-width: 28px;
      min-height: 28px;
    }
    .chat__rate[aria-pressed='true'] {
      --bs-btn-color: var(--bs-primary-text-emphasis);
    }
    .chat__ticket {
      margin-top: var(--app-space-2);
      padding: var(--app-space-2) var(--app-space-3);
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
    }
    .chat__ticket-title {
      font-size: var(--app-text-sm);
      font-weight: 600;
      margin: 0 0 var(--app-space-1);
    }
    .chat__ticket-hint {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
      margin-bottom: var(--app-space-2);
    }
    .chat__ticket-subject {
      font-weight: 600;
      margin-bottom: var(--app-space-1);
    }
    .chat__ticket-body {
      max-height: 14rem;
      overflow: auto;
      white-space: pre-wrap;
      font-size: var(--app-text-xs);
      background: var(--bs-tertiary-bg);
      padding: var(--app-space-2);
      border-radius: var(--bs-border-radius-sm);
    }
    .chat__ticket-actions {
      justify-content: flex-end;
    }
    .chat__typing {
      margin: var(--app-space-3) 0 0;
      color: var(--bs-secondary-color);
      font-size: var(--app-text-sm);
    }
    .chat__composer {
      padding: var(--app-space-3) var(--bs-offcanvas-padding-x);
      border-top: var(--bs-border-width) solid var(--bs-border-color);
    }
    .chat__input-row {
      display: flex;
      gap: var(--app-space-2);
      align-items: flex-end;
    }
    .chat__input-row textarea {
      resize: vertical;
      min-height: 2.75rem;
    }
    .chat__hint {
      margin: var(--app-space-1) 0 0;
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
  `,
})
export class SupportChatPanel {
  protected readonly offcanvas = inject(NgbActiveOffcanvas);
  protected readonly store = inject(SupportChatStore);
  private readonly onboarding = inject(OnboardingService);
  private readonly manual = inject(ManualService);
  private readonly help = inject(HelpService);
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');
  private readonly input = viewChild<ElementRef<HTMLTextAreaElement>>('input');

  protected readonly titleId = SUPPORT_CHAT_TITLE_ID;
  protected readonly max = SUPPORT_QUESTION_MAX;
  protected readonly suggestions = SUGGESTIONS;
  protected readonly reasons = DOWN_REASONS;
  protected readonly draft = signal('');
  protected readonly announcement = signal('');
  protected readonly reasonFor = signal<string | null>(null);
  protected readonly canSend = computed(() => this.draft().trim().length > 0 && !this.store.sending());
  protected readonly screenTitle = signal<string | null>(null);

  constructor() {
    inject(Router)
      .events.pipe(
        filter((e) => e instanceof NavigationStart),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe(() => this.offcanvas.dismiss('navigation'));
    if (this.store.availability() !== 'available') {
      void this.store.loadStatus();
    }
    const key = this.onboarding.screen()?.key;
    if (key) {
      void this.manual
        .screen(key)
        .then((v) => this.screenTitle.set(v?.manual.title ?? null))
        .catch(() => this.screenTitle.set(null));
    }
    afterNextRender(() => this.scrollToEnd());
  }

  protected domain(card: SupportCard): StatusDomain | null {
    return card.status ? (CARD_DOMAIN[card.kind] ?? null) : null;
  }

  protected severity(status: string): string {
    return SEVERITY_LABEL[status] ?? status;
  }

  /** Link de tela só quando a pessoa pode abri-la (mesma regra do menu; o servidor já filtrou). */
  protected openRoute(action: SupportAction): string | null {
    return this.manual.routeFor(action.screen);
  }

  protected enter(event: Event): void {
    const e = event as KeyboardEvent;
    if (!e.shiftKey && !e.isComposing) {
      e.preventDefault();
      this.submit();
    }
  }

  protected submit(): void {
    if (this.canSend()) {
      void this.ask(this.draft());
    }
  }

  protected async ask(text: string): Promise<void> {
    this.draft.set('');
    this.announcement.set('Pergunta enviada. Aguarde a resposta.');
    const answer = await this.store.send(text, this.onboarding.screen()?.key ?? null);
    if (answer) {
      this.announcement.set(`Resposta do suporte: ${answer.text ?? ''}${answer.ticket ? ' Há um chamado para conferir.' : ''}`);
    } else {
      this.draft.set(text);
      this.announcement.set(this.store.error() ?? '');
    }
    this.scrollToEnd();
    this.input()?.nativeElement.focus();
  }

  protected newConversation(): void {
    this.store.newConversation();
    this.reasonFor.set(null);
    this.announcement.set('Nova conversa iniciada.');
    this.input()?.nativeElement.focus();
  }

  protected async rate(message: SupportMessage, rating: 'UP' | 'DOWN', reason: SupportDownReason | null = null): Promise<void> {
    await this.store.rate(message, rating, reason);
    this.reasonFor.set(rating === 'DOWN' && !reason ? message.id : null);
    this.announcement.set(reason || rating === 'UP' ? 'Obrigado pela avaliação.' : 'Avaliação registrada. Se quiser, diga o que houve.');
  }

  protected async confirmTicket(ticket: SupportTicket): Promise<void> {
    const updated = await this.store.confirmTicket(ticket);
    this.announcement.set(
      updated
        ? `Chamado nº ${updated.issueNumber ?? ''} ${updated.status === 'DUPLICATE' ? 'já existia' : 'aberto'}. O retorno será dado por e-mail.`
        : (this.store.ticketError(ticket.id) ?? ''),
    );
  }

  protected async cancelTicket(ticket: SupportTicket): Promise<void> {
    if (await this.store.cancelTicket(ticket)) {
      this.announcement.set('Chamado não enviado.');
    }
  }

  protected startTour(): void {
    this.offcanvas.close();
    void this.onboarding.startScreenTour();
  }

  protected aboutScreen(): void {
    this.offcanvas.close();
    void this.help.openScreenManual();
  }

  private scrollToEnd(): void {
    const el = this.scroller()?.nativeElement;
    if (el) {
      queueMicrotask(() => (el.scrollTop = el.scrollHeight));
    }
  }
}
