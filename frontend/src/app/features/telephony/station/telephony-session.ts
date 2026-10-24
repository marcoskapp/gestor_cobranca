import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, of, tap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError } from '../../../core/http/problem';
import { CallsApi, PresenceApi } from '../data/telephony.api';
import { AgentView, CallView, PauseReason } from '../data/telephony.models';
import { CALL_POLL_MS, HEARTBEAT_MS, PRESENCE_POLL_MS, awaitsTabulation, isCallActive } from '../data/telephony.rules';

/**
 * Sessão de telefonia do operador no navegador: presença (`/presence/me`), sinal periódico a cada
 * 30 s enquanto conectado (sem sinal por 90 s o servidor o tira da telefonia), chamada em
 * andamento (consulta a cada 2 s) e a chamada que aguarda tabulação. Vale para a sessão do
 * usuário inteira: sair da estação não desconecta; fechar o navegador ou encerrar a sessão sim
 * (pelo prazo do sinal).
 */
@Injectable({ providedIn: 'root' })
export class TelephonySession {
  private readonly presenceApi = inject(PresenceApi);
  private readonly callsApi = inject(CallsApi);
  private readonly auth = inject(AuthService);

  private readonly agentState = signal<AgentView | null>(null);
  /** `null` = ainda não consultado; `false` = usuário sem ramal habilitado. */
  private readonly configuredState = signal<boolean | null>(null);
  private readonly callState = signal<CallView | null>(null);
  private readonly pendingState = signal<CallView | null>(null);
  private readonly busyState = signal(false);

  readonly agent = this.agentState.asReadonly();
  readonly configured = this.configuredState.asReadonly();
  readonly call = this.callState.asReadonly();
  /** Chamada encerrada aguardando a tabulação obrigatória do operador. */
  readonly pendingTabulation = this.pendingState.asReadonly();
  readonly busy = this.busyState.asReadonly();
  readonly enabled = computed(() => this.auth.hasPermission(Permission.TELEPHONY_USE));
  readonly loggedIn = computed(() => !!this.agentState() && this.agentState()!.status !== 'OFFLINE');

  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private presenceTimer: ReturnType<typeof setInterval> | null = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    // Fim da sessão do usuário: para os sinais (o servidor encerra a presença pelo prazo).
    effect(() => {
      if (!this.auth.user()) {
        this.stopTimers();
        this.agentState.set(null);
        this.callState.set(null);
        this.pendingState.set(null);
        this.configuredState.set(null);
      }
    });
    // Sinal periódico só enquanto conectado.
    effect(() => {
      if (this.loggedIn()) {
        this.startHeartbeat();
      } else {
        this.stopHeartbeat();
      }
    });
  }

  /** Carrega a presença e a chamada pendente de tabulação (abertura da estação). */
  refresh(): void {
    if (!this.enabled()) {
      return;
    }
    this.presenceApi.me().subscribe({
      next: (agent) => {
        this.configuredState.set(true);
        this.applyAgent(agent);
      },
      error: (err: unknown) => {
        if (toApiError(err).code === 'telephony.agent_not_configured') {
          this.configuredState.set(false);
        }
      },
    });
    this.callsApi.awaitingTabulation().subscribe({
      next: (calls) => {
        const pending = calls.find((c) => awaitsTabulation(c)) ?? null;
        if (pending) {
          this.pendingState.set(pending);
        }
      },
      error: () => undefined,
    });
  }

  login(campaignId: string | null = null): Observable<AgentView> {
    return this.presence(this.presenceApi.login(campaignId));
  }

  available(): Observable<AgentView> {
    return this.presence(this.presenceApi.available());
  }

  pause(reason: PauseReason): Observable<AgentView> {
    return this.presence(this.presenceApi.pause(reason));
  }

  joinCampaign(campaignId: string | null): Observable<AgentView> {
    return this.presence(this.presenceApi.joinCampaign(campaignId));
  }

  logout(): Observable<AgentView> {
    return this.presence(this.presenceApi.logout());
  }

  /**
   * Clique para ligar. Negação do porteiro volta como chamada `FAILED/CONTACT_DENIED` (sem discar):
   * a tela mostra os motivos. Permitida, a chamada passa a ser acompanhada até o fim.
   */
  dial(caseId: string, contactId: string | null, key: string): Observable<CallView> {
    this.busyState.set(true);
    return this.callsApi.clickToCall(caseId, contactId, key).pipe(
      tap((call) => {
        if (isCallActive(call)) {
          this.track(call);
          this.reloadAgent();
        }
      }),
      finalize(() => this.busyState.set(false)),
    );
  }

  hangup(): Observable<CallView | null> {
    const call = this.callState();
    if (!call) {
      return of(null);
    }
    return this.callsApi.hangup(call.id).pipe(tap((updated) => this.applyCall(updated)));
  }

  /** Tabulação concluída (pelo diálogo): libera o operador. */
  tabulated(call: CallView): void {
    if (this.pendingState()?.id === call.id) {
      this.pendingState.set(null);
    }
    if (this.callState()?.id === call.id) {
      this.callState.set(null);
    }
    this.reloadAgent();
  }

  /** Para os sinais (testes e encerramento explícito). */
  stopTimers(): void {
    this.stopHeartbeat();
    this.stopPolling();
  }

  private presence(call: Observable<AgentView>): Observable<AgentView> {
    this.busyState.set(true);
    return call.pipe(
      tap((agent) => this.applyAgent(agent)),
      finalize(() => this.busyState.set(false)),
    );
  }

  private applyAgent(agent: AgentView): void {
    this.agentState.set(agent);
    if (agent.currentCallId && !this.callState()) {
      this.callsApi
        .get(agent.currentCallId)
        .pipe(catchError(() => of(null)))
        .subscribe((call) => call && this.track(call));
    }
  }

  private reloadAgent(): void {
    this.presenceApi
      .me()
      .pipe(catchError(() => of(null)))
      .subscribe((agent) => agent && this.agentState.set(agent));
  }

  private track(call: CallView): void {
    this.applyCall(call);
    this.stopPolling();
    if (!isCallActive(call)) {
      return;
    }
    this.pollTimer = setInterval(() => {
      const current = this.callState();
      if (!current) {
        this.stopPolling();
        return;
      }
      this.callsApi
        .get(current.id)
        .pipe(catchError(() => of(null)))
        .subscribe((updated) => updated && this.applyCall(updated));
    }, CALL_POLL_MS);
  }

  private applyCall(call: CallView): void {
    this.callState.set(call);
    if (!isCallActive(call)) {
      this.stopPolling();
      if (awaitsTabulation(call)) {
        this.pendingState.set(call);
      } else {
        this.callState.set(null);
      }
      this.reloadAgent();
    }
  }

  private startHeartbeat(): void {
    if (this.heartbeatTimer) {
      return;
    }
    this.heartbeatTimer = setInterval(() => {
      this.presenceApi.heartbeat().subscribe({
        error: () => this.reloadAgent(),
      });
    }, HEARTBEAT_MS);
    // Numa campanha de discagem, a chamada atendida chega pelo discador: a presença é consultada
    // com frequência para mostrar a chamada distribuída ao operador.
    this.presenceTimer = setInterval(() => {
      const agent = this.agentState();
      if (agent?.campaignId && !this.callState() && !this.pendingState()) {
        this.presenceApi
          .me()
          .pipe(catchError(() => of(null)))
          .subscribe((a) => a && this.applyAgent(a));
      }
    }, PRESENCE_POLL_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    if (this.presenceTimer) {
      clearInterval(this.presenceTimer);
      this.presenceTimer = null;
    }
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }
}
