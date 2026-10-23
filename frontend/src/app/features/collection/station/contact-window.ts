import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { silentErrorContext } from '../../../core/http/http-context';
import { valueOr } from '../../../shared/data/resource-value';
import { ContactPolicyApi } from '../../compliance/data/compliance.api';
import { TenantSettingsApi } from '../../organization/data/organization.api';
import { CHANNEL_ICONS, CHANNEL_LABELS } from '../data/collection.labels';
import { InteractionChannel } from '../data/collection.models';
import { ContactWindow, WindowState, tenantDays, weekDayIndex, windowState, zonedNow } from '../data/collection.rules';

const CHANNELS: readonly InteractionChannel[] = ['PHONE', 'WHATSAPP', 'SMS', 'EMAIL'];

interface ChannelRow {
  channel: InteractionChannel;
  restricted: boolean;
  state: WindowState;
}

/**
 * Janela de contato permitida, sempre visível na estação: por canal, se há restrição para o
 * devedor (elegibilidade do caso) e se o horário de agora está dentro da janela (política do
 * credor quando o usuário pode lê-la; senão o padrão da assessoria). É orientação: o porteiro de
 * contato do servidor valida janela, feriado e limites a cada contato.
 */
@Component({
  selector: 'app-contact-window',
  imports: [MatIconModule, MatTooltipModule],
  template: `
    <section class="cw" aria-labelledby="cw-title" data-tour="contact-window">
      <h2 class="cw__title" id="cw-title">
        <mat-icon aria-hidden="true">schedule</mat-icon>
        Janela de contato
        <span class="cw__now app-numeric">agora {{ now().time }}</span>
      </h2>
      <ul class="cw__list">
        @for (row of rows(); track row.channel) {
          <li class="cw__row">
            <mat-icon class="cw__channel" aria-hidden="true">{{ icons[row.channel] }}</mat-icon>
            <span class="cw__name">{{ labels[row.channel] }}</span>
            @if (row.restricted) {
              <span class="app-status app-status--danger"><mat-icon class="app-status__icon" aria-hidden="true">block</mat-icon>Restrito</span>
            } @else if (row.state.open) {
              <span class="app-status app-status--success" [matTooltip]="row.state.text"
                ><mat-icon class="app-status__icon" aria-hidden="true">check_circle</mat-icon>Permitido</span
              >
            } @else {
              <span class="app-status app-status--warning" [matTooltip]="row.state.text"
                ><mat-icon class="app-status__icon" aria-hidden="true">do_not_disturb_on</mat-icon>Fora da janela</span
              >
            }
            <span class="cw__detail app-muted app-numeric" [matTooltip]="row.state.text">{{ row.restricted ? 'restrição vigente' : row.state.range }}</span>
          </li>
        }
      </ul>
      <p class="cw__note app-muted">{{ sourceText() }} Feriados e limites de tentativas são verificados pelo sistema a cada contato.</p>
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
  `,
})
export class ContactWindowPanel {
  readonly creditorId = input<string | null>(null);
  /** Canais sem restrição (`CaseView.contactableChannels`); nulo = desconhecido (sem caso). */
  readonly contactableChannels = input<readonly string[] | null>(null);

  private readonly auth = inject(AuthService);
  private readonly tenantApi = inject(TenantSettingsApi);
  private readonly policyApi = inject(ContactPolicyApi);

  protected readonly labels = CHANNEL_LABELS;
  protected readonly icons = CHANNEL_ICONS;
  private readonly tick = signal(Date.now());
  protected readonly now = computed(() => zonedNow(new Date(this.tick())));

  private readonly tenant = rxResource({ stream: () => this.tenantApi.current() });
  private readonly tenantValue = valueOr(this.tenant, undefined);

  /** A política efetiva por credor só é legível com `CONTACT_RESTRICTION_MANAGE` (ver pendências). */
  private readonly policy = rxResource({
    params: () => (this.auth.hasPermission(Permission.CONTACT_RESTRICTION_MANAGE) && this.creditorId() ? this.creditorId()! : undefined),
    stream: ({ params }) => this.policyApi.effective(params, silentErrorContext()),
  });
  private readonly policyValue = valueOr(this.policy, undefined);

  protected readonly sourceText = computed(() =>
    this.policyValue() ? 'Regras da política de contato vigente do credor.' : 'Janela padrão da assessoria.',
  );

  protected readonly rows = computed<ChannelRow[]>(() => {
    const tenant = this.tenantValue();
    const policy = this.policyValue();
    const contactable = this.contactableChannels();
    const now = this.now();
    const fallback: ContactWindow = {
      start: tenant?.contactWindowStart ?? null,
      end: tenant?.contactWindowEnd ?? null,
      days: tenant ? tenantDays(tenant.contactOnSaturday, tenant.contactOnSunday) : null,
      source: 'TENANT',
    };
    return CHANNELS.map((channel) => {
      const rule = policy?.channels.find((c) => c.channel === channel);
      const window: ContactWindow = rule
        ? { start: rule.windowStart, end: rule.windowEnd, days: rule.allowedDays?.map(weekDayIndex) ?? fallback.days, source: 'POLICY' }
        : fallback;
      return { channel, restricted: contactable !== null && !contactable.includes(channel), state: windowState(window, now) };
    });
  });

  constructor() {
    const timer = setInterval(() => this.tick.set(Date.now()), 30_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
}
