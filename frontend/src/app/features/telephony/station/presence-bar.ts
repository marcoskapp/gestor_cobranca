import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbDropdownModule, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { catchError, of } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { DialerCampaignsApi } from '../data/telephony.api';
import { PAUSE_REASON_LABELS, PRODUCTIVE_PAUSES, TELEPHONY_ERROR_LABELS } from '../data/telephony.labels';
import { PauseReason } from '../data/telephony.models';
import { clockText, isCallActive, presenceActions } from '../data/telephony.rules';
import { TelephonyLauncher } from './telephony-launcher';
import { SoftphoneState, WebrtcSoftphone } from './softphone';
import { TelephonySession } from './telephony-session';

/**
 * Barra de presença do operador na estação de cobrança: entrar (opcionalmente numa campanha de
 * discagem), disponível, pausa com motivo e sair; mostra a chamada em andamento (com o tempo e o
 * botão de encerrar) e abre a tabulação obrigatória ao fim da chamada.
 */
@Component({
  selector: 'app-presence-bar',
  imports: [NgbDropdownModule, NgbTooltip, Icon, StatusChip],
  template: `
    @if (session.enabled()) {
      <div class="d-flex flex-wrap align-items-center column-gap-2 row-gap-1" role="group" aria-label="Presença na telefonia" data-tour="presence">
        @if (session.configured() === false) {
          <span class="app-muted small d-inline-flex align-items-center gap-1" ngbTooltip="Peça ao gestor para habilitar o seu usuário na telefonia"><app-icon name="telephone-x" />Sem ramal</span>
        } @else if (agent(); as a) {
          <app-status-chip domain="agentPresence" [status]="a.status" />
          @if (a.status === 'PAUSED' && a.pauseReason) {
            <span class="small">{{ pauseLabels[a.pauseReason] }}</span>
          }
          @if (a.status !== 'OFFLINE') {
            <span class="small app-numeric app-muted" ngbTooltip="Tempo neste estado">{{ since() }}</span>
          }
          @if (softphone.state() !== 'off') {
            <span class="small d-inline-flex align-items-center gap-1" [class.text-danger-emphasis]="softphone.state() === 'error'" data-testid="softphone-state" aria-live="polite">
              <app-icon name="headset" />{{ softphoneLabels[softphone.state()] }}
            </span>
            @if (softphone.state() === 'in-call') {
              <button class="btn btn-sm btn-outline-secondary" type="button" (click)="softphone.toggleMute()" [attr.aria-pressed]="softphone.muted()">
                {{ softphone.muted() ? 'Reativar microfone' : 'Silenciar' }}
              </button>
            }
          }
          @if (call(); as c) {
            <span class="d-inline-flex flex-wrap align-items-center gap-2 px-2 py-1 rounded bg-body-tertiary small" aria-live="polite">
              <app-icon name="telephone" />
              {{ c.numberMasked }} · <app-status-chip domain="call" [status]="c.status" />
              @if (active()) {
                <button class="btn btn-sm btn-outline-danger d-inline-flex align-items-center gap-1" type="button" (click)="hangup()" aria-label="Encerrar chamada">
                  <app-icon name="telephone-x" />
                  Encerrar
                </button>
              }
            </span>
          }
          @if (actions().login) {
            <div ngbDropdown class="d-inline-flex">
              <button class="btn btn-sm btn-outline-primary d-inline-flex align-items-center gap-1" type="button" ngbDropdownToggle [disabled]="session.busy()">
                <app-icon name="headset" />
                Entrar na telefonia
                <app-icon name="chevron-down" />
              </button>
              <div ngbDropdownMenu class="shadow app-menu" aria-label="Entrar na telefonia">
                <button ngbDropdownItem type="button" (click)="login(null)">Somente clique para ligar</button>
                @for (c of activeCampaigns(); track c.id) {
                  <button ngbDropdownItem type="button" (click)="login(c.id)">Campanha {{ c.name }}</button>
                }
              </div>
            </div>
          }
          @if (actions().available) {
            <button class="btn btn-sm btn-primary" type="button" (click)="available()" [disabled]="session.busy()">Ficar disponível</button>
          }
          @if (actions().pause) {
            <div ngbDropdown class="d-inline-flex">
              <button class="btn btn-sm btn-outline-primary d-inline-flex align-items-center gap-1" type="button" ngbDropdownToggle [disabled]="session.busy()">
                <app-icon name="pause" />
                Pausa
                <app-icon name="chevron-down" />
              </button>
              <div ngbDropdownMenu class="shadow app-menu" aria-label="Motivo da pausa">
                @for (r of pauseReasons; track r) {
                  <button ngbDropdownItem type="button" (click)="pause(r)">
                    <span>{{ pauseLabels[r] }}</span>
                    @if (productive.includes(r)) {
                      <span class="app-muted small">· produtiva</span>
                    }
                  </button>
                }
              </div>
            </div>
          }
          @if (actions().logout) {
            <button class="btn btn-sm btn-link" type="button" (click)="logout()" [disabled]="session.busy()" aria-label="Sair da telefonia" ngbTooltip="Sair da telefonia">
              <app-icon name="box-arrow-right" />
            </button>
          }
          @if (actions().blocked && !call()) {
            <span class="small app-muted">{{ actions().blocked }}</span>
          }
        } @else {
          <span class="app-muted small">Telefonia…</span>
        }
      </div>
    }
  `,
})
export class PresenceBar {
  protected readonly session = inject(TelephonySession);
  protected readonly softphone = inject(WebrtcSoftphone);
  protected readonly softphoneLabels: Record<SoftphoneState, string> = {
    off: '',
    connecting: 'Conectando o softphone…',
    ready: 'Softphone pronto',
    'in-call': 'Em ligação no navegador',
    error: 'Softphone sem conexão',
  };
  private readonly launcher = inject(TelephonyLauncher);
  private readonly notifications = inject(NotificationService);
  private readonly campaignsApi = inject(DialerCampaignsApi);

  protected readonly pauseLabels = PAUSE_REASON_LABELS;
  protected readonly pauseReasons = Object.keys(PAUSE_REASON_LABELS) as PauseReason[];
  protected readonly productive = PRODUCTIVE_PAUSES;
  protected readonly agent = this.session.agent;
  protected readonly call = this.session.call;
  protected readonly actions = computed(() => presenceActions(this.agent()));
  protected readonly active = computed(() => !!this.call() && isCallActive(this.call()!));

  private readonly campaigns = rxResource({
    params: () => (this.actions().login ? true : undefined),
    stream: () => this.campaignsApi.list().pipe(catchError(() => of([]))),
  });
  private readonly campaignValues = valueOr(this.campaigns, []);
  protected readonly activeCampaigns = computed(() => this.campaignValues().filter((c) => c.status === 'ACTIVE'));

  private readonly now = signal(Date.now());
  protected readonly since = computed(() => {
    const at = this.agent()?.statusSince;
    return at ? clockText(this.now() - new Date(at).getTime()) : '';
  });

  constructor() {
    this.session.refresh();
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
    // Chamada encerrada aguardando tabulação: diálogo obrigatório (não fecha sem tabular).
    effect(() => {
      const pending = this.session.pendingTabulation();
      if (pending) {
        untracked(() =>
          this.launcher.tabulation({ call: pending }).subscribe((done) => {
            if (done) {
              this.notifications.success(done.tabulationCode ? 'Chamada tabulada e registrada no caso.' : 'Tabulação registrada automaticamente pelo prazo.');
            }
          }),
        );
      }
    });
  }

  protected login(campaignId: string | null): void {
    this.run(this.session.login(campaignId), campaignId ? 'Você está disponível para a campanha de discagem.' : 'Você está disponível na telefonia.');
  }

  protected available(): void {
    this.run(this.session.available(), 'Você está disponível.');
  }

  protected pause(reason: PauseReason): void {
    this.run(this.session.pause(reason), `Pausa: ${PAUSE_REASON_LABELS[reason]}.`);
  }

  protected logout(): void {
    this.run(this.session.logout(), 'Você saiu da telefonia.');
  }

  protected hangup(): void {
    this.session.hangup().subscribe({ error: (err: unknown) => this.fail(err) });
  }

  private run(call: ReturnType<TelephonySession['login']>, message: string): void {
    call.subscribe({ next: () => this.notifications.success(message), error: (err: unknown) => this.fail(err) });
  }

  private fail(err: unknown): void {
    const e = toApiError(err);
    this.notifications.error((e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e));
  }
}
