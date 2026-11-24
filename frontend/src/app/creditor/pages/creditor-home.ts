import { Component, computed, inject } from '@angular/core';
import { CreditorSession } from '../data/creditor-session';

/**
 * Início do portal do credor. Nesta fase: a conta da pessoa (credor, perfil, último acesso). Os indicadores, a
 * prestação de contas e as contestações entram nas próximas fatias (docs/tecnico/desenho/portal-credor.md §2).
 */
@Component({
  selector: 'app-creditor-home',
  imports: [],
  template: `
    @if (session.me(); as m) {
      <h1 class="h4">Olá, {{ firstName() }}</h1>
      <section class="app-card" aria-labelledby="ch-conta">
        <h2 class="app-card__title" id="ch-conta">Sua conta</h2>
        <dl class="app-definition-list">
          <dt>Credor</dt>
          <dd>{{ m.creditorName }}</dd>
          <dt>Assessoria</dt>
          <dd>{{ m.tenantName }}</dd>
          <dt>Perfil</dt>
          <dd>{{ m.roleLabel }}</dd>
          <dt>E-mail</dt>
          <dd>{{ m.email }}</dd>
        </dl>
        <p class="app-muted mb-0">Cada ação no portal fica registrada. Se notar algo que não fez, avise a assessoria.</p>
      </section>
    }
  `,
})
export class CreditorHome {
  protected readonly session = inject(CreditorSession);
  protected readonly firstName = computed(() => this.session.me()?.name.trim().split(/\s+/)[0] ?? '');
}
