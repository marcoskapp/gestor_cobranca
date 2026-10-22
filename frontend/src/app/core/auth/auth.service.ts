import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import {
  Observable,
  catchError,
  finalize,
  firstValueFrom,
  map,
  of,
  shareReplay,
  tap,
  throwError,
} from 'rxjs';
import { AuthApi } from './auth.api';
import {
  AuthStep,
  AuthenticatedUser,
  LoginRequest,
  LoginResponse,
  LoginStatus,
  MfaEnrollment,
  SessionEndReason,
} from './auth.models';
import { CrossTabCoordinator } from './cross-tab';
import { safeReturnUrl } from './return-url';
import { TenantMemory } from './tenant-memory';
import { renewalDelayMs, tokenLifetimeMs } from './token-lifetime';
import { UserActivity } from './user-activity';

/** Etapa intermediária do login. O step token vive apenas aqui, em memória. */
export interface PendingStep {
  status: AuthStep;
  stepToken: string;
}

/**
 * Resultado de uma tentativa de renovação:
 * - `renewed`: novo access token em memória;
 * - `ended`: o servidor recusou (sessão expirada, revogada ou inexistente) — é preciso novo login;
 * - `unavailable`: falha de rede ou do servidor — a sessão pode continuar válida.
 */
export type RefreshOutcome = 'renewed' | 'ended' | 'unavailable';

const RETRY_UNAVAILABLE_MS = 15_000;
const EXPIRY_MARGIN_MS = 5_000;

export const AUTH_ROUTES = {
  login: '/login',
  mfa: '/login/mfa',
  mfaEnrollment: '/login/mfa/cadastro',
  passwordChange: '/login/senha-expirada',
} as const;

const STEP_ROUTES: Record<AuthStep, string> = {
  MFA_REQUIRED: AUTH_ROUTES.mfa,
  MFA_ENROLLMENT_REQUIRED: AUTH_ROUTES.mfaEnrollment,
  PASSWORD_CHANGE_REQUIRED: AUTH_ROUTES.passwordChange,
};

/**
 * Estado de autenticação da aplicação.
 *
 * - Access token apenas em memória (nunca em localStorage/sessionStorage).
 * - Refresh token em cookie HttpOnly `gc_refresh`, inacessível ao JavaScript; renovado por
 *   `POST /api/v1/auth/refresh` com `withCredentials`.
 * - Renovação proativa ~60 s antes da expiração, somente se houve atividade do usuário (respeita
 *   o tempo de inatividade da sessão no servidor).
 * - Uma única renovação em andamento por vez (requisições concorrentes compartilham o resultado),
 *   serializada também entre abas para não disparar a detecção de reuso do refresh token.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AuthApi);
  private readonly router = inject(Router);
  private readonly tenantMemory = inject(TenantMemory);
  private readonly crossTab = inject(CrossTabCoordinator);
  private readonly activity = inject(UserActivity);

  private token: string | null = null;
  private tokenReceivedAt = 0;
  private tokenExpiresAt = 0;
  private renewalTimer: ReturnType<typeof setTimeout> | null = null;
  private refreshInFlight: Observable<RefreshOutcome> | null = null;
  private returnUrl = '/';

  private readonly currentUser = signal<AuthenticatedUser | null>(null);
  private readonly step = signal<PendingStep | null>(null);

  readonly user = this.currentUser.asReadonly();
  readonly pendingStep = this.step.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUser() !== null);
  private readonly permissions = computed(() => new Set(this.currentUser()?.permissions ?? []));

  constructor() {
    this.crossTab.messages.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((message) => {
      if (message.type === 'logout' && this.isAuthenticated()) {
        this.clearSession();
        void this.router.navigate([AUTH_ROUTES.login], { queryParams: { motivo: 'logout' } });
      }
    });
  }

  // ------------------------------------------------------------------ consulta

  accessToken(): string | null {
    return this.token;
  }

  /**
   * O token em memória já expirou (ou expira em instantes). Ocorre quando não houve atividade e a
   * renovação proativa foi dispensada; a próxima chamada renova antes de enviar a requisição, sem
   * provocar um 401 desnecessário no servidor.
   */
  isTokenExpiring(now = Date.now()): boolean {
    return this.token !== null && now >= this.tokenExpiresAt - EXPIRY_MARGIN_MS;
  }

  hasPermission(permission: string): boolean {
    return this.permissions().has(permission);
  }

  hasAnyPermission(permissions: readonly string[]): boolean {
    return permissions.length === 0 || permissions.some((p) => this.permissions().has(p));
  }

  rememberedTenant(): string {
    return this.tenantMemory.read();
  }

  /** Destino após concluir o login (definido pela tela de login a partir de `returnUrl`). */
  setReturnUrl(url: string | null | undefined): void {
    this.returnUrl = safeReturnUrl(url);
  }

  // ------------------------------------------------------------------ fluxo de login

  login(request: LoginRequest): Observable<LoginStatus> {
    this.step.set(null);
    const tenant = request.tenant.trim().toLowerCase();
    return this.api.login({ tenant, email: request.email.trim(), password: request.password }).pipe(
      tap(() => this.tenantMemory.remember(tenant)),
      map((response) => this.accept(response)),
    );
  }

  verifyMfa(code: string): Observable<LoginStatus> {
    return this.withStep('MFA_REQUIRED', (stepToken) =>
      this.api.verifyMfa(stepToken, code).pipe(map((response) => this.accept(response))),
    );
  }

  startMfaEnrollment(): Observable<MfaEnrollment> {
    return this.withStep('MFA_ENROLLMENT_REQUIRED', (stepToken) => this.api.startMfaEnrollment(stepToken));
  }

  confirmMfaEnrollment(code: string): Observable<LoginStatus> {
    return this.withStep('MFA_ENROLLMENT_REQUIRED', (stepToken) =>
      this.api.confirmMfaEnrollment(stepToken, code).pipe(map((response) => this.accept(response))),
    );
  }

  /** Troca de senha obrigatória. Após o sucesso o servidor exige novo login. */
  changeExpiredPassword(newPassword: string): Observable<void> {
    return this.withStep('PASSWORD_CHANGE_REQUIRED', (stepToken) =>
      this.api.changeExpiredPassword(stepToken, newPassword).pipe(tap(() => this.step.set(null))),
    );
  }

  /** Abandona a etapa intermediária (botão "voltar" ou step token expirado). */
  cancelStep(): void {
    this.step.set(null);
  }

  /** Navega para a próxima tela conforme o status devolvido pelo servidor. */
  continueFlow(status: LoginStatus): Promise<boolean> {
    if (status === 'AUTHENTICATED') {
      const target = this.returnUrl;
      this.returnUrl = '/';
      return this.router.navigateByUrl(target);
    }
    return this.router.navigateByUrl(STEP_ROUTES[status]);
  }

  // ------------------------------------------------------------------ sessão

  /** Inicialização da aplicação: tenta restaurar a sessão a partir do cookie de refresh. */
  async restoreSession(): Promise<void> {
    await firstValueFrom(this.refresh());
  }

  /**
   * Renova o access token. Chamadas concorrentes compartilham a mesma requisição; entre abas, a
   * renovação é serializada (Web Locks), pois o refresh token é rotativo.
   */
  refresh(): Observable<RefreshOutcome> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.crossTab
        .withRefreshLock(() => firstValueFrom(this.api.refresh()))
        .pipe(
          map((response): RefreshOutcome => {
            if (response.status === 'AUTHENTICATED' && response.accessToken && response.user) {
              this.establish(response);
              return 'renewed';
            }
            this.clearSession();
            return 'ended';
          }),
          catchError((error: unknown) => {
            if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
              this.clearSession();
              return of<RefreshOutcome>('ended');
            }
            return of<RefreshOutcome>('unavailable');
          }),
          finalize(() => {
            this.refreshInFlight = null;
          }),
          shareReplay({ bufferSize: 1, refCount: false }),
        );
    }
    return this.refreshInFlight;
  }

  /** Encerra a sessão no servidor e localmente; avisa as demais abas. */
  logout(): Observable<void> {
    const request = this.token ? this.api.logout().pipe(catchError(() => of(undefined))) : of(undefined);
    return request.pipe(
      map(() => undefined),
      finalize(() => {
        this.clearSession();
        this.crossTab.broadcast({ type: 'logout' });
        void this.router.navigate([AUTH_ROUTES.login], { queryParams: { motivo: 'logout' } });
      }),
    );
  }

  /**
   * A sessão terminou sem ação do usuário (renovação recusada). Limpa o estado e leva ao login
   * preservando a rota atual para retorno.
   */
  endSession(reason: SessionEndReason, returnUrl: string = this.router.url): void {
    const alreadyOnLogin = this.router.url.startsWith(AUTH_ROUTES.login);
    this.clearSession();
    if (alreadyOnLogin) {
      return;
    }
    const queryParams: Record<string, string> = { motivo: reason === 'expired' ? 'expirada' : reason };
    const target = safeReturnUrl(returnUrl);
    if (target !== '/') {
      queryParams['returnUrl'] = target;
    }
    void this.router.navigate([AUTH_ROUTES.login], { queryParams });
  }

  /** Atualiza os dados do usuário em memória (ex.: após ativar MFA). */
  reloadProfile(): Observable<AuthenticatedUser> {
    return this.api.profile().pipe(tap((user) => this.currentUser.set(user)));
  }

  // ------------------------------------------------------------------ interno

  private accept(response: LoginResponse): LoginStatus {
    if (response.status === 'AUTHENTICATED') {
      if (!response.accessToken || !response.user) {
        throw new Error('Resposta de autenticação incompleta');
      }
      this.establish(response);
    } else {
      if (!response.stepToken) {
        throw new Error('Resposta de autenticação sem step token');
      }
      this.step.set({ status: response.status, stepToken: response.stepToken });
    }
    return response.status;
  }

  private establish(response: LoginResponse): void {
    const accessToken = response.accessToken as string;
    const now = Date.now();
    const lifetime = tokenLifetimeMs(accessToken, response.accessTokenExpiresAt, now);
    this.token = accessToken;
    this.tokenReceivedAt = now;
    this.tokenExpiresAt = now + lifetime;
    this.step.set(null);
    this.currentUser.set(response.user);
    this.scheduleRenewal(renewalDelayMs(lifetime));
  }

  private scheduleRenewal(delay: number): void {
    this.cancelRenewal();
    this.renewalTimer = setTimeout(() => this.renewProactively(), delay);
  }

  private renewProactively(): void {
    this.renewalTimer = null;
    if (!this.token) {
      return;
    }
    if (this.activity.lastActivityAt() <= this.tokenReceivedAt) {
      // sem interação desde a última emissão: deixa o token expirar. A próxima chamada à API
      // recebe 401 e renova sob demanda, se o servidor ainda considerar a sessão ativa.
      return;
    }
    this.refresh().subscribe((outcome) => {
      if (outcome === 'ended') {
        this.endSession('expired');
      } else if (outcome === 'unavailable' && Date.now() + RETRY_UNAVAILABLE_MS < this.tokenExpiresAt) {
        this.scheduleRenewal(RETRY_UNAVAILABLE_MS);
      }
    });
  }

  private cancelRenewal(): void {
    if (this.renewalTimer !== null) {
      clearTimeout(this.renewalTimer);
      this.renewalTimer = null;
    }
  }

  private clearSession(): void {
    this.cancelRenewal();
    this.token = null;
    this.tokenReceivedAt = 0;
    this.tokenExpiresAt = 0;
    this.step.set(null);
    this.currentUser.set(null);
  }

  private withStep<T>(status: AuthStep, call: (stepToken: string) => Observable<T>): Observable<T> {
    const current = this.step();
    if (!current || current.status !== status) {
      return throwError(() => new Error(`Etapa de autenticação ${status} não iniciada`));
    }
    return call(current.stepToken);
  }
}
