import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { APP_NAME } from '../../config/page-title.strategy';

/** Moldura das telas públicas (login, MFA, redefinição de senha). */
@Component({
  selector: 'app-auth-layout',
  imports: [RouterOutlet],
  template: `
    <main class="app-auth" id="conteudo">
      <div class="app-auth__panel">
        <p class="app-auth__brand">{{ appName }}</p>
        <router-outlet />
      </div>
      <p class="app-auth__footer">Acesso restrito. Todas as ações são registradas para auditoria.</p>
    </main>
  `,
  styleUrl: './auth-layout.scss',
})
export class AuthLayout {
  protected readonly appName = APP_NAME;
}
