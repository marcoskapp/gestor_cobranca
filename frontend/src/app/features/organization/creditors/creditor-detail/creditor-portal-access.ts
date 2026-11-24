import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormGroupDirective, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, finalize } from 'rxjs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { Icon } from '../../../../shared/components/icon/icon';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { AppValidators } from '../../../../shared/validation/validators';
import { CreditorPortalApi, CreditorPortalUserView, CreditorPortalView } from '../../data/creditor-portal.api';

/** O que cada perfil da pessoa do credor permite (decisão 1396). */
const ROLE_HINTS: Record<string, string> = {
  GESTOR: 'Acompanha as carteiras, responde contestações e informa pagamento direto.',
  FINANCEIRO: 'Aceita ou contesta a prestação de contas e informa pagamento direto.',
  JURIDICO: 'Responde às contestações.',
  COMPLIANCE: 'Acompanha e vê a atividade das pessoas do credor.',
  LEITURA: 'Só acompanha, sem responder nada.',
};

/**
 * Portal do credor na ficha do credor (só com `CREDITOR_PORTAL_MANAGE` — a ficha nem mostra a seção sem ela). Liberar o
 * portal e convidar, mudar o perfil, refazer o acesso e revogar as pessoas do credor. O convite vai só por e-mail para a
 * pessoa: o link nunca aparece aqui (decisão 1395).
 */
@Component({
  selector: 'app-creditor-portal-access',
  imports: [ReactiveFormsModule, Icon, StatusChip, ...FORM_FIELD],
  host: { class: 'app-card d-block', role: 'region', 'aria-labelledby': 'portal-credor', 'data-tour': 'creditor-portal-access' },
  template: `
    <div class="app-page-header">
      <h2 class="app-card__title" id="portal-credor">Portal do credor</h2>
      @if (view(); as v) {
        @if (v.enabled) {
          <button class="btn btn-outline-secondary" type="button" (click)="disable()" [disabled]="busy()">Desativar portal</button>
        } @else {
          <button class="btn btn-primary" type="button" (click)="enable()" [disabled]="busy()">Liberar portal</button>
        }
      }
    </div>
    <p class="app-muted">
      As pessoas do credor acompanham a cobrança das carteiras, a prestação de contas e as contestações, com senha e
      autenticador (a prestação de contas e as contestações chegam ao portal nas próximas versões). O convite chega só por
      e-mail à pessoa convidada e vale 72 horas.
    </p>
    @if (portal.isLoading() && !view()) {
      <p class="app-muted" role="status">Carregando o portal do credor…</p>
    } @else if (portal.status() === 'error') {
      <div class="alert alert-danger d-flex align-items-center gap-2" role="alert">
        <app-icon name="exclamation-circle" /><span>Não foi possível carregar o portal do credor.</span>
        <button class="btn btn-sm btn-outline-secondary ms-auto" type="button" (click)="portal.reload()">Tentar novamente</button>
      </div>
    } @else if (view(); as v) {
      @if (!v.enabled) {
        <p class="app-muted">Portal desativado para este credor: ninguém entra e nenhum convite vale. Libere para convidar pessoas.</p>
      }
      <ul class="list-unstyled cpa-list" aria-label="Pessoas do credor no portal">
        @for (u of v.users; track u.id) {
          <li>
            <span class="cpa-who">
              <strong>{{ u.name }}</strong>
              <span class="app-muted">{{ u.email }}{{ u.status === 'REVOKED' || !v.enabled ? ' · ' + u.roleLabel : '' }}</span>
              <span class="app-muted cpa-when">{{ when(u) }}</span>
            </span>
            <app-status-chip domain="creditorPortalUser" [status]="u.status" />
            @if (u.locked) {
              <span class="badge rounded-pill bg-warning-subtle text-warning-emphasis">Bloqueado por tentativas</span>
            }
            @if (u.status !== 'REVOKED' && v.enabled) {
              <span class="cpa-actions">
                <label class="visually-hidden" [for]="'role-' + u.id">Perfil de {{ u.name }}</label>
                <select class="form-select form-select-sm" [id]="'role-' + u.id" [value]="u.role" [disabled]="busy()"
                        (change)="changeRole(u, $any($event.target))">
                  @for (r of v.roles; track r.code) {
                    <option [value]="r.code" [selected]="r.code === u.role">{{ r.label }}</option>
                  }
                </select>
                @if (u.status === 'INVITED') {
                  <button class="btn btn-sm btn-outline-secondary" type="button" (click)="resend(u)" [disabled]="busy()"
                          [attr.aria-label]="'Reenviar convite para ' + u.name">Reenviar convite</button>
                } @else {
                  <button class="btn btn-sm btn-outline-secondary" type="button" (click)="resetAccess(u)" [disabled]="busy()"
                          [attr.aria-label]="'Refazer o acesso de ' + u.name">Refazer acesso</button>
                }
                <button class="btn btn-sm btn-outline-danger" type="button" (click)="revoke(u)" [disabled]="busy()"
                        [attr.aria-label]="'Revogar o acesso de ' + u.name">Revogar</button>
              </span>
            }
          </li>
        } @empty {
          <li class="app-muted">Ninguém do credor tem acesso ao portal ainda.</li>
        }
      </ul>
      @if (v.enabled) {
        <form class="row g-3 align-items-start" [formGroup]="form" (ngSubmit)="invite(inviteForm)" #inviteForm="ngForm" novalidate
              aria-label="Convidar pessoa do credor">
          <app-field class="col-md-4" label="Nome" error="Informe o nome.">
            <input appInput formControlName="name" maxlength="150" required autocomplete="off" />
          </app-field>
          <app-field class="col-md-4" label="E-mail da pessoa" error="Informe um e-mail válido." hint="O e-mail do credor, nunca o de alguém da assessoria.">
            <input appInput type="email" formControlName="email" maxlength="200" required autocomplete="off" />
          </app-field>
          <app-field class="col-md-4" label="Perfil" [hint]="roleHint()" error="Escolha o perfil.">
            <select appInput formControlName="role" required>
              <option value="" disabled>Escolha o perfil</option>
              @for (r of v.roles; track r.code) {
                <option [value]="r.code">{{ r.label }}</option>
              }
            </select>
          </app-field>
          <div class="col-12 d-flex justify-content-end">
            <button class="btn btn-outline-primary" type="submit" [disabled]="busy()">
              <app-icon name="envelope" />
              Enviar convite
            </button>
          </div>
        </form>
      }
    }
  `,
  styles: `
    .cpa-list {
      display: grid;
      gap: 8px;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 12px;
      }
    }
    .cpa-who {
      display: flex;
      flex-direction: column;
      min-width: 0;
      flex: 1 1 240px;
    }
    .cpa-when {
      font-size: var(--app-text-xs);
    }
    .cpa-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      select {
        width: auto;
      }
    }
  `,
})
export class CreditorPortalAccess {
  readonly creditorId = input.required<string>();
  readonly creditorName = input<string>('');

  private readonly api = inject(CreditorPortalApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly portal = rxResource({ params: () => this.creditorId(), stream: ({ params }) => this.api.status(params) });
  protected readonly view = computed<CreditorPortalView | undefined>(() => (this.portal.hasValue() ? this.portal.value() : undefined));
  protected readonly busy = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    name: ['', [AppValidators.notBlank, Validators.maxLength(150)]],
    email: ['', [AppValidators.notBlank, Validators.email, Validators.maxLength(200)]],
    role: ['', Validators.required],
  });
  private readonly roleValue = toSignal(this.form.controls.role.valueChanges, { initialValue: '' });
  /** O que o perfil escolhido permite (menor privilégio: a escolha é obrigatória, sem perfil padrão). */
  protected readonly roleHint = computed(() => ROLE_HINTS[this.roleValue()] ?? 'Escolha o que a pessoa pode fazer no portal.');

  protected when(u: CreditorPortalUserView): string {
    if (u.status === 'REVOKED' && u.revokedAt) {
      return `Revogado em ${new Date(u.revokedAt).toLocaleDateString('pt-BR')}`;
    }
    if (u.lastLoginAt) {
      return `Último acesso em ${new Date(u.lastLoginAt).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })}`;
    }
    return `Convidado em ${new Date(u.invitedAt).toLocaleDateString('pt-BR')}; ainda não entrou`;
  }

  protected enable(): void {
    this.run(this.api.enable(this.creditorId()), 'Portal do credor liberado.');
  }

  protected disable(): void {
    this.confirm
      .confirm({
        title: 'Desativar o portal do credor',
        message: 'Todas as pessoas do credor saem na hora e os convites pendentes deixam de valer. Liberar de novo não reenvia convites.',
        confirmLabel: 'Desativar portal',
        destructive: true,
        details: [{ label: 'Credor', value: this.creditorName() }],
      })
      .subscribe(() => this.run(this.api.disable(this.creditorId()), 'Portal do credor desativado.'));
  }

  protected invite(formDirective: FormGroupDirective): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.run(this.api.invite(this.creditorId(), { name: v.name.trim(), email: v.email.trim(), role: v.role }),
      `Convite enviado para ${v.email.trim()}.`,
      // resetForm também zera o "enviado" do formulário: os campos vazios não aparecem como erro
      () => formDirective.resetForm({ name: '', email: '', role: '' }));
  }

  protected resend(u: CreditorPortalUserView): void {
    this.run(this.api.resendInvitation(this.creditorId(), u.id), `Novo convite enviado para ${u.email}.`);
  }

  protected resetAccess(u: CreditorPortalUserView): void {
    this.confirm
      .confirm({
        title: 'Refazer o acesso',
        message: 'Use quando a pessoa perdeu o autenticador ou a senha. Ela sai na hora e recebe um convite novo para criar senha e autenticador.',
        confirmLabel: 'Refazer acesso',
        details: [{ label: 'Pessoa', value: `${u.name} (${u.email})` }],
      })
      .subscribe(() => this.run(this.api.resetAccess(this.creditorId(), u.id), `Convite novo enviado para ${u.email}.`));
  }

  /** O select só escolhe; a gravação passa por confirmação (muda o que alguém de fora pode fazer). */
  protected changeRole(u: CreditorPortalUserView, select: HTMLSelectElement): void {
    const role = select.value;
    if (role === u.role) {
      return;
    }
    const label = this.view()?.roles.find((r) => r.code === role)?.label ?? role;
    select.value = u.role; // volta ao atual até confirmar
    this.confirm
      .confirm({
        title: 'Mudar o perfil',
        message: `${ROLE_HINTS[role] ?? ''} Vale a partir da próxima ação da pessoa no portal.`.trim(),
        confirmLabel: 'Mudar perfil',
        details: [
          { label: 'Pessoa', value: `${u.name} (${u.email})` },
          { label: 'De', value: u.roleLabel },
          { label: 'Para', value: label },
        ],
      })
      .subscribe(() => this.run(this.api.changeRole(this.creditorId(), u.id, role), 'Perfil alterado.'));
  }

  protected revoke(u: CreditorPortalUserView): void {
    this.confirm
      .confirm({
        title: 'Revogar o acesso',
        message: 'A pessoa sai na hora e não entra mais. Para dar acesso de novo, convide com outro e-mail.',
        confirmLabel: 'Revogar acesso',
        destructive: true,
        reason: 'required',
        reasonMaxLength: 200,
        details: [{ label: 'Pessoa', value: `${u.name} (${u.email})` }],
      })
      .subscribe((reason) => this.run(this.api.revoke(this.creditorId(), u.id, reason), 'Acesso revogado.'));
  }

  private run(request: Observable<unknown>, message: string, after?: () => void): void {
    this.busy.set(true);
    request.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: () => {
        this.notifications.success(message);
        after?.();
        this.portal.reload();
      },
      // recusado: a lista volta ao que vale no servidor (ex.: o perfil continua o anterior)
      error: () => this.portal.reload(),
    });
  }
}
