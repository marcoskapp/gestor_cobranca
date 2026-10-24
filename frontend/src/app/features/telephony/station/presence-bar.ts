import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { catchError, of } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { DialerCampaignsApi } from '../data/telephony.api';
import { PAUSE_REASON_LABELS, PRODUCTIVE_PAUSES, TELEPHONY_ERROR_LABELS } from '../data/telephony.labels';
import { PauseReason } from '../data/telephony.models';
import { clockText, isCallActive, presenceActions } from '../data/telephony.rules';
import { TelephonyLauncher } from './telephony-launcher';
import { TelephonySession } from './telephony-session';

/**
 * Barra de presença do operador na estação de cobrança: entrar (opcionalmente numa campanha de
 * discagem), disponível, pausa com motivo e sair; mostra a chamada em andamento (com o tempo e o
 * botão de encerrar) e abre a tabulação obrigatória ao fim da chamada.
 */
@Component({
  selector: 'app-presence-bar',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, StatusChip],
  template: `
    @if (session.enabled()) {
      <div class="pb" role="group" aria-label="Presença na telefonia" data-tour="presence">
        @if (session.configured() === false) {
          <span class="app-muted pb-small" matTooltip="Peça ao gestor para habilitar o seu usuário na telefonia"><mat-icon class="pb-icon" aria-hidden="true">phone_disabled</mat-icon>Sem ramal</span>
        } @else if (agent(); as a) {
          <app-status-chip domain="agentPresence" [status]="a.status" />
          @if (a.status === 'PAUSED' && a.pauseReason) {
            <span class="pb-small">{{ pauseLabels[a.pauseReason] }}</span>
          }
          @if (a.status !== 'OFFLINE') {
            <span class="pb-small app-numeric app-muted" matTooltip="Tempo neste estado">{{ since() }}</span>
          }
          @if (call(); as c) {
            <span class="pb-call" aria-live="polite">
              <mat-icon class="pb-icon" aria-hidden="true">call</mat-icon>
              {{ c.numberMasked }} · <app-status-chip domain="call" [status]="c.status" />
              @if (active()) {
                <button mat-stroked-button type="button" class="pb-hangup" (click)="hangup()" aria-label="Encerrar chamada">
                  <mat-icon aria-hidden="true">call_end</mat-icon>
                  Encerrar
                </button>
              }
            </span>
          }
          @if (actions().login) {
            <button mat-stroked-button type="button" [matMenuTriggerFor]="loginMenu" [disabled]="session.busy()">
              <mat-icon aria-hidden="true">headset_mic</mat-icon>
              Entrar na telefonia
            </button>
          }
          @if (actions().available) {
            <button mat-flat-button type="button" (click)="available()" [disabled]="session.busy()">Ficar disponível</button>
          }
          @if (actions().pause) {
            <button mat-stroked-button type="button" [matMenuTriggerFor]="pauseMenu" [disabled]="session.busy()">
              <mat-icon aria-hidden="true">pause</mat-icon>
              Pausa
            </button>
          }
          @if (actions().logout) {
            <button mat-icon-button type="button" (click)="logout()" [disabled]="session.busy()" aria-label="Sair da telefonia" matTooltip="Sair da telefonia">
              <mat-icon aria-hidden="true">logout</mat-icon>
            </button>
          }
          @if (actions().blocked && !call()) {
            <span class="pb-small app-muted">{{ actions().blocked }}</span>
          }
        } @else {
          <span class="app-muted pb-small">Telefonia…</span>
        }
      </div>
      <mat-menu #pauseMenu="matMenu">
        @for (r of pauseReasons; track r) {
          <button mat-menu-item type="button" (click)="pause(r)">
            <span>{{ pauseLabels[r] }}</span>
            @if (productive.includes(r)) {
              <span class="app-muted pb-small"> · produtiva</span>
            }
          </button>
        }
      </mat-menu>
      <mat-menu #loginMenu="matMenu">
        <button mat-menu-item type="button" (click)="login(null)">Somente clique para ligar</button>
        @for (c of activeCampaigns(); track c.id) {
          <button mat-menu-item type="button" (click)="login(c.id)">Campanha {{ c.name }}</button>
        }
      </mat-menu>
    }
  `,
  styles: `
    .pb {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 10px;
    }
    .pb-small {
      font-size: var(--app-text-xs);
    }
    .pb-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
      vertical-align: middle;
    }
    .pb-call {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
      padding: 2px 8px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
      font-size: var(--app-text-sm);
    }
    .pb-hangup {
      color: var(--app-danger-fg);
    }
  `,
})
export class PresenceBar {
  protected readonly session = inject(TelephonySession);
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
