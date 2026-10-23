import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { silentErrorContext } from '../../../core/http/http-context';
import { valueOr } from '../../../shared/data/resource-value';
import { CasesApi } from '../data/collection.api';
import { CHANNEL_ICONS, CHANNEL_LABELS, eligibilityText } from '../data/collection.labels';
import { InteractionChannel } from '../data/collection.models';
import { AvailabilityState, availabilityState, zonedNow } from '../data/collection.rules';

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

const STATUS_ICON: Record<AvailabilityState['kind'], string> = {
  allowed: 'check_circle',
  wait: 'schedule',
  blocked: 'block',
  suspended: 'pause_circle',
};

/**
 * "Posso contatar agora?", sempre visível na estação: por canal, a decisão do porteiro de contato
 * do servidor para o devedor e o credor do caso (restrições, política vigente, feriados, horários
 * e limites), sem registrar tentativa e sem expor a política. A tela não calcula janelas: só
 * apresenta a decisão e o próximo horário permitido. O porteiro decide de novo a cada contato.
 */
@Component({
  selector: 'app-contact-window',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <section class="cw" aria-labelledby="cw-title" data-tour="contact-window" aria-live="polite">
      <h2 class="cw__title" id="cw-title">
        <mat-icon aria-hidden="true">schedule</mat-icon>
        Posso contatar agora?
        <span class="cw__now app-numeric">agora {{ now().time }}</span>
        @if (caseId()) {
          <button mat-icon-button class="cw__refresh" type="button" (click)="refresh()" aria-label="Consultar de novo" matTooltip="Consultar de novo">
            <mat-icon aria-hidden="true">refresh</mat-icon>
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
            <span class="app-status app-status--danger"><mat-icon class="app-status__icon" aria-hidden="true">pause_circle</mat-icon>Contato suspenso</span>
            {{ caseReasonsText() }}
          </p>
        }
        <ul class="cw__list">
          @for (row of rows(); track row.channel) {
            <li class="cw__row">
              <mat-icon class="cw__channel" aria-hidden="true">{{ icons[row.channel] }}</mat-icon>
              <span class="cw__name">{{ labels[row.channel] }}</span>
              <span class="app-status" [class]="statusClass[row.state.kind]" [matTooltip]="row.state.text"
                ><mat-icon class="app-status__icon" aria-hidden="true">{{ statusIcon[row.state.kind] }}</mat-icon>{{ row.state.label }}</span
              >
              <span class="cw__detail app-muted app-numeric" [matTooltip]="row.state.text">{{ row.state.detail }}</span>
            </li>
          }
        </ul>
        <p class="cw__note app-muted">Decisão do sistema (restrições, política, feriados e limites). Nada é registrado nesta consulta.</p>
      } @else {
        <p class="cw__note app-muted">Consultando…</p>
      }
    </section>
  `,
  styles: `
    .cw {
      padding: 10px 12px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
    .cw__title {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0 0 6px;
      font-size: var(--app-text-sm);
      font-weight: 600;
      .mat-icon {
        width: 18px;
        height: 18px;
        font-size: 18px;
      }
    }
    .cw__now {
      margin-left: auto;
      font-weight: 500;
      color: var(--mat-sys-on-surface-variant);
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
      width: 16px;
      height: 16px;
      font-size: 16px;
      color: var(--mat-sys-on-surface-variant);
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
    .cw__refresh {
      width: 28px;
      height: 28px;
      padding: 2px;
      --mat-icon-button-state-layer-size: 28px;
    }
  `,
})
export class ContactWindowPanel {
  readonly caseId = input<string | null>(null);

  private readonly api = inject(CasesApi);

  protected readonly labels = CHANNEL_LABELS;
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
