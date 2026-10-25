import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Icon } from '../../../shared/components/icon/icon';

@Component({
  selector: 'app-profile',
  imports: [Icon, RouterLink, PageHeader],
  template: `
    <app-page-header title="Meu perfil" />
    @if (user(); as u) {
      <section class="app-card" aria-labelledby="dados-perfil">
        <h2 class="app-card__title" id="dados-perfil">Dados da conta</h2>
        <dl class="app-definition-list">
          <dt>Nome</dt>
          <dd>{{ u.name }}</dd>
          <dt>E-mail</dt>
          <dd>{{ u.email }}</dd>
          <dt>Assessoria</dt>
          <dd>{{ u.tenantName }} <span class="app-mono app-muted">({{ u.tenantSlug }})</span></dd>
          <dt>Perfis</dt>
          <dd>{{ u.roles.join(', ') || '—' }}</dd>
          <dt>Escopo de credores</dt>
          <dd>{{ u.allCreditors ? 'Todos os credores' : u.creditorIds.length + ' credor(es)' }}</dd>
          <dt>Verificação em duas etapas</dt>
          <dd>
            @if (u.mfaEnabled) {
              <span class="app-status app-status--success">Ativa</span>
            } @else {
              <span class="app-status app-status--warning">Não configurada</span>
            }
          </dd>
        </dl>
      </section>
      <section class="app-card" aria-labelledby="seguranca-perfil">
        <h2 class="app-card__title" id="seguranca-perfil">Segurança</h2>
        <div class="app-page-header__actions">
          <a class="btn btn-outline-primary" routerLink="/conta/senha">
            <app-icon name="asterisk" />
            Trocar senha
          </a>
          @if (!u.mfaEnabled) {
            <a class="btn btn-outline-primary" routerLink="/conta/mfa">
              <app-icon name="phone-vibrate" />
              Ativar verificação em duas etapas
            </a>
          }
          <a class="btn btn-outline-primary" routerLink="/conta/sessoes">
            <app-icon name="laptop" />
            Minhas sessões
          </a>
        </div>
      </section>
    }
  `,
})
export class Profile {
  protected readonly user = inject(AuthService).user;
}
