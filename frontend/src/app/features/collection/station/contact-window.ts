import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { silentErrorContext } from '../../../core/http/http-context';
import { Icon } from '../../../shared/components/icon/icon';
import { IconName } from '../../../shared/components/icon/icon-names';
import { valueOr } from '../../../shared/data/resource-value';
import { CasesApi } from '../data/collection.api';
import { CHANNEL_ICONS, CHANNEL_LABELS, eligibilityText } from '../data/collection.labels';
import { InteractionChannel } from '../data/collection.models';
import { AvailabilityState, availabilityState, zonedNow } from '../data/collection.rules';

/** Rótulo curto do canal nos chips da linha compacta. */
const SHORT_LABELS: Partial<Record<InteractionChannel, string>> = { PHONE: 'Tel.', WHATSAPP: 'WA', SMS: 'SMS', EMAIL: 'E-mail' };

/** Reconsulta periódica (a decisão muda com o horário, feriados e tentativas de outros canais). */
const REFRESH_MS = 60_000;

interface ChannelRow {
  channel: InteractionChannel;
  state: AvailabilityState;
}

const STATUS_CLASS: Record<AvailabilityState['kind'], string> = {
  allowed: 'app-status--success',
  wait: 'app-status--warning',
  blocked: 'app-status--danger',
  suspended: 'app-status--danger',
};

const STATUS_ICON: Record<AvailabilityState['kind'], IconName> = {
  allowed: 'check-circle',
  wait: 'clock',
  blocked: 'slash-circle',
  suspended: 'pause-circle',
};

/**
 * "Posso contatar agora?", sempre visível na estação: por canal, a decisão do porteiro de contato
 * do servidor para o devedor e o credor do caso (restrições, política vigente, feriados, horários
 * e limites), sem registrar tentativa e sem expor a política. A tela não calcula janelas: só
 * apresenta a decisão e o próximo horário permitido. O porteiro decide de novo a cada contato.
 */
@Component({
  selector: 'app-contact-window',
  imports: [NgbTooltip, Icon],
  template: `
    @if (compact()) {
      <!-- uma linha de chips; o detalhe aparece só para os canais que não estão liberados agora -->
      <section class="cw cw--compact" aria-labelledby="cw-title" data-tour="contact-window" aria-live="polite">
        <div class="cw__line">
          <h2 class="cw__title cw__title--inline" id="cw-title" [ngbTooltip]="'Posso contatar agora? ' + footnote">
            <app-icon name="clock" /><span class="visually-hidden">Posso contatar?</span>
          </h2>
          @if (!caseId()) {
            <span class="cw__note app-muted">Selecione um caso.</span>
          } @else if (availability.error()) {
            <span class="cw__note app-muted">Sem consulta agora (o sistema verifica a cada contato).</span>
          } @else if (result(); as r) {
            @if (!r.caseEligible) {
              <span class="app-status app-status--danger" [ngbTooltip]="caseReasonsText()"><app-icon class="app-status__icon" name="pause-circle" />Suspenso</span>
            }
            @for (row of rows(); track row.channel) {
              <span
                class="app-status cw__chip"
                [class]="statusClass[row.state.kind]"
                [ngbTooltip]="labels[row.channel] + ': ' + row.state.label + (row.state.detail ? ' · ' + row.state.detail : '')"
                [attr.aria-label]="labels[row.channel] + ': ' + row.state.label"
                ><app-icon class="app-status__icon" [name]="statusIcon[row.state.kind]" />{{ short[row.channel] }}</span
              >
            }
          } @else {
            <span class="cw__note app-muted">Consultando…</span>
          }
          @if (caseId()) {
            <button type="button" class="btn btn-sm cw__refresh ms-auto" (click)="refresh()" aria-label="Consultar de novo" ngbTooltip="Consultar de novo">
              <app-icon name="arrow-clockwise" />
            </button>
          }
        </div>
        @if (restricted().length) {
          <p class="cw__note cw__restricted">
            @for (row of restricted(); track row.channel; let last = $last) {
              <span [ngbTooltip]="labels[row.channel] + ': ' + row.state.text">{{ short[row.channel] }} {{ row.state.detail }}</span>{{ last ? '' : ' · ' }}
            }
          </p>
        }
      </section>
    } @else {
    <section class="cw" aria-labelledby="cw-title" data-tour="contact-window" aria-live="polite">
      <h2 class="cw__title" id="cw-title">
        <app-icon name="clock" />
        Posso contatar agora?
        <span class="cw__now app-numeric">agora {{ now().time }}</span>
        @if (caseId()) {
          <button type="button" class="btn btn-sm cw__refresh" (click)="refresh()" aria-label="Consultar de novo" ngbTooltip="Consultar de novo">
            <app-icon name="arrow-clockwise" />
          </button>
        }
      </h2>
      @if (!caseId()) {
        <p class="cw__note app-muted">Selecione um caso para consultar o contato.</p>
      } @else if (availability.error()) {
        <p class="cw__note app-muted">Não foi possível consultar agora. O sistema verifica a cada contato.</p>
      } @else if (result(); as r) {
        @if (!r.caseEligible) {
          <p class="cw__note">
            <span class="app-status app-status--danger"><app-icon class="app-status__icon" name="pause-circle" />Contato suspenso</span>
            {{ caseReasonsText() }}
          </p>
        }
        <ul class="cw__list">
          @for (row of rows(); track row.channel) {
            <li class="cw__row">
              <app-icon class="cw__channel" [name]="icons[row.channel]" />
              <span class="cw__name">{{ labels[row.channel] }}</span>
              <span class="app-status" [class]="statusClass[row.state.kind]" [ngbTooltip]="row.state.text"
                ><app-icon class="app-status__icon" [name]="statusIcon[row.state.kind]" />{{ row.state.label }}</span
              >
              <span class="cw__detail app-muted app-numeric" [ngbTooltip]="row.state.text">{{ row.state.detail }}</span>
            </li>
          }
        </ul>
        <p class="cw__note app-muted">{{ footnote }}</p>
      } @else {
        <p class="cw__note app-muted">Consultando…</p>
      }
    </section>
    }
  `,
  styles: `
    .cw {
      padding: 10px 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-tertiary-bg);
    }
    .cw__title {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0 0 6px;
      font-size: var(--app-text-sm);
      font-weight: 500;
    }
    .cw__now {
      margin-left: auto;
      font-weight: var(--bs-body-font-weight);
      color: var(--bs-secondary-color);
    }
    .cw__list {
      display: grid;
      gap: 4px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .cw__row {
      display: grid;
      grid-template-columns: 18px 72px auto 1fr;
      align-items: center;
      gap: 6px;
      font-size: var(--app-text-xs);
    }
    .cw__channel {
      font-size: 0.875rem;
      color: var(--bs-secondary-color);
    }
    .cw__detail {
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
    }
    .cw__note {
      margin: 6px 0 0;
      font-size: var(--app-text-xs);
    }
    .cw--compact {
      padding: 6px 8px;
    }
    .cw__line {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 6px;
    }
    .cw__title--inline {
      margin: 0 2px 0 0;
      font-size: var(--app-text-xs);
    }
    .cw__chip {
      font-size: var(--app-text-xs);
    }
    .cw__restricted {
      margin-top: 4px;
    }
    .cw__refresh {
      --bs-btn-padding-x: 0.3125rem;
      --bs-btn-padding-y: 0.125rem;
      --bs-btn-hover-bg: var(--bs-secondary-bg);
      --bs-btn-active-bg: var(--bs-secondary-bg);
    }
  `,
})
export class ContactWindowPanel {
  readonly caseId = input<string | null>(null);
  /** Uma linha de chips (estação a 1366 px); expande só os canais que não estão liberados agora. */
  readonly compact = input(false);

  private readonly api = inject(CasesApi);

  protected readonly labels = CHANNEL_LABELS;
  protected readonly short = SHORT_LABELS;
  protected readonly footnote = 'Decisão do sistema (restrições, política, feriados e limites). Nada é registrado nesta consulta.';
  protected readonly icons = CHANNEL_ICONS;
  protected readonly statusClass = STATUS_CLASS;
  protected readonly statusIcon = STATUS_ICON;
  private readonly tick = signal(Date.now());
  protected readonly now = computed(() => zonedNow(new Date(this.tick())));
  /** Muda a cada reconsulta periódica ou manual. */
  private readonly round = signal(0);

  protected readonly availability = rxResource({
    params: () => (this.caseId() ? { id: this.caseId()!, round: this.round() } : undefined),
    stream: ({ params }) => this.api.contactAvailability(params.id, {}, silentErrorContext()),
  });
  protected readonly result = valueOr(this.availability, undefined);

  protected readonly caseReasonsText = computed(() => (this.result()?.caseReasons ?? []).map(eligibilityText).join(' · '));

  protected readonly rows = computed<ChannelRow[]>(() => {
    const r = this.result();
    const now = new Date(this.tick());
    return (r?.channels ?? []).map((c) => ({ channel: c.channel, state: availabilityState(c, r!.caseEligible, now) }));
  });

  /** Canais que não estão liberados agora (o detalhe aparece embaixo da linha de chips). */
  protected readonly restricted = computed(() => this.rows().filter((r) => r.state.kind !== 'allowed'));

  constructor() {
    const clock = setInterval(() => this.tick.set(Date.now()), 30_000);
    const poll = setInterval(() => this.refresh(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(clock);
      clearInterval(poll);
    });
  }

  protected refresh(): void {
    this.round.update((n) => n + 1);
  }
}
