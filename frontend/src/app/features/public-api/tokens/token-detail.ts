import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { finalize, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_PERMISSIONS, UserNames } from '../../admin/data/user-names';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { IntegrationTokensApi } from '../data/public-api.api';
import { PUBLIC_API_ERROR_LABELS, TOKEN_STATE_LABELS } from '../data/public-api.labels';
import { TokenView } from '../data/public-api.models';
import { RotateTokenDialog, RotateTokenDialogData } from './rotate-token-dialog';
import { TokenDialog, TokenDialogData } from './token-dialog';

/** Ficha do token: identificação, escopos, limite, IPs, uso, rotação e revogação (com motivo, imediata). */
@Component({
  selector: 'app-token-detail',
  imports: [RouterLink, AppDatePipe, EmptyState, Icon, PageHeader, Skeleton],
  template: `
    @if (resource.isLoading() && !token()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else if (resource.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar o token." message="Verifique a conexão e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (token(); as t) {
      <app-page-header [title]="t.name" [subtitle]="creditors.name(t.creditorId) + ' · ' + t.prefix">
        @if (canManage() && t.state === 'ACTIVE') {
          <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="edit(t)" [disabled]="busy()">
            <app-icon name="pencil" />
            Editar
          </button>
          <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="rotate(t)" [disabled]="busy()" data-tour="token-rotate">
            <app-icon name="arrow-repeat" />
            Rotacionar
          </button>
          <button class="btn btn-outline-danger d-inline-flex align-items-center gap-2" type="button" (click)="revoke(t)" [disabled]="busy()" data-tour="token-revoke">
            <app-icon name="slash-circle" />
            Revogar
          </button>
        }
      </app-page-header>
      <p><a routerLink="/integracoes/tokens">← Tokens de integração</a></p>
      @if (t.previousValidUntil) {
        <div class="alert alert-info d-flex gap-2 align-items-start" role="status">
          <app-icon name="clock" />
          <span>Rotação em andamento: o segredo anterior ainda é aceito até {{ t.previousValidUntil | appDate }}.</span>
        </div>
      }
      <div class="td-grid">
        <section class="app-card" aria-labelledby="td-id">
          <h2 class="app-card__title" id="td-id">Identificação</h2>
          <dl class="app-definition-list">
            <dt>Situação</dt>
            <dd>{{ stateLabels[t.state] }}</dd>
            <dt>Prefixo</dt>
            <dd class="app-mono">{{ t.prefix }}</dd>
            <dt>Descrição</dt>
            <dd>{{ t.description || '—' }}</dd>
            <dt>Criado por</dt>
            <dd>{{ t.createdBy ? users.name(t.createdBy) : '—' }}</dd>
            <dt>Criado em</dt>
            <dd>{{ t.createdAt | appDate }}</dd>
            <dt>Expira em</dt>
            <dd>{{ t.expiresAt ? (t.expiresAt | appDate) : 'Não expira' }}</dd>
          </dl>
        </section>
        <section class="app-card" aria-labelledby="td-use" data-tour="token-usage">
          <h2 class="app-card__title" id="td-use">Uso e proteção</h2>
          <dl class="app-definition-list">
            <dt>Último uso</dt>
            <dd>{{ t.lastUsedAt | appDate }}</dd>
            <dt>IP do último uso</dt>
            <dd class="app-mono">{{ t.lastUsedIp || '—' }}</dd>
            <dt>Limite</dt>
            <dd>{{ t.rateLimitPerMinute }} requisições por minuto</dd>
            <dt>IPs permitidos</dt>
            <dd class="app-mono">{{ t.allowedIps.length ? t.allowedIps.join(', ') : 'Qualquer origem' }}</dd>
            <dt>Rotações</dt>
            <dd>{{ t.rotationCount }}{{ t.rotatedAt ? ' · última em ' + (t.rotatedAt | appDate) : '' }}</dd>
          </dl>
          <a class="btn btn-sm btn-link px-0" [routerLink]="['/integracoes/consumo']" [queryParams]="{ token: t.id }">Ver consumo deste token</a>
        </section>
        <section class="app-card" aria-labelledby="td-scopes">
          <h2 class="app-card__title" id="td-scopes">Escopos</h2>
          <ul class="td-scopes">
            @for (s of t.scopes; track s) {
              <li class="app-mono">{{ s }}</li>
            }
          </ul>
          <p class="app-muted td-small">Escopos não mudam depois da criação: para outros escopos, crie um token novo e revogue este.</p>
        </section>
        @if (t.state === 'REVOKED') {
          <section class="app-card" aria-labelledby="td-revoked">
            <h2 class="app-card__title" id="td-revoked">Revogação</h2>
            <dl class="app-definition-list">
              <dt>Revogado em</dt>
              <dd>{{ t.revokedAt | appDate }}</dd>
              <dt>Por</dt>
              <dd>{{ t.revokedBy ? users.name(t.revokedBy) : '—' }}</dd>
              <dt>Motivo</dt>
              <dd>{{ t.revokeReason }}</dd>
            </dl>
          </section>
        }
      </div>
    }
  `,
  styles: `
    .td-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 360px), 1fr));
      gap: var(--app-gap);
      .app-card + .app-card {
        margin-top: 0;
      }
    }
    .td-scopes {
      columns: 2;
      padding-left: 1.2rem;
      margin: 0 0 8px;
    }
    .td-small {
      font-size: var(--app-text-xs);
      margin: 0;
    }
  `,
})
export class TokenDetail {
  readonly id = input.required<string>();
  private readonly api = inject(IntegrationTokensApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly stateLabels = TOKEN_STATE_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.PUBLIC_API_TOKEN_MANAGE));
  protected readonly busy = signal(false);

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly token = computed(() => (this.resource.hasValue() ? this.resource.value() : null));
  protected readonly creditors = nameMap(inject(CreditorNames), () => (this.token() ? [this.token()!.creditorId] : []));
  // "criado por"/"revogado por" exigem CASE_VIEW, POLICY_VIEW ou USER_VIEW (o perfil Integrações não tem: "Indisponível")
  protected readonly users = nameMap(this.auth.hasAnyPermission(USER_NAMES_PERMISSIONS) ? inject(UserNames) : { resolve: () => of([]) }, () => {
    const t = this.token();
    return t ? [t.createdBy, t.revokedBy].filter((v): v is string => !!v) : [];
  });

  protected edit(t: TokenView): void {
    this.dialog
      .open<TokenDialog, TokenDialogData, TokenView | null>(TokenDialog, { data: { token: t }, size: 'lg' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Token atualizado.');
          this.resource.reload();
        }
      });
  }

  protected rotate(t: TokenView): void {
    this.dialog
      .open<RotateTokenDialog, RotateTokenDialogData, TokenView | null>(RotateTokenDialog, { data: { token: t }, size: 'lg' })
      .afterClosed()
      .subscribe((rotated) => {
        if (rotated) {
          this.notifications.success('Token rotacionado. O novo segredo não será exibido de novo.');
          this.resource.reload();
        }
      });
  }

  protected revoke(t: TokenView): void {
    this.confirm
      .confirm({
        title: 'Revogar token',
        message: 'A revogação é imediata e definitiva: a próxima chamada do sistema do credor com este token será recusada.',
        confirmLabel: 'Revogar',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo da revogação',
        reasonMaxLength: 500,
        details: [
          { label: 'Token', value: `${t.name} (${t.prefix})` },
          { label: 'Credor', value: this.creditors.name(t.creditorId) },
        ],
      })
      .subscribe((reason) => {
        this.busy.set(true);
        this.api
          .revoke(t.id, reason)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: () => {
              this.notifications.success('Token revogado.');
              this.resource.reload();
            },
            error: (err: unknown) => {
              const e = toApiError(err);
              this.notifications.error((e.code && PUBLIC_API_ERROR_LABELS[e.code]) || userMessage(e));
            },
          });
      });
  }
}
