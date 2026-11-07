import { Component, inject } from '@angular/core';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { RouterOutlet } from '@angular/router';
import { Icon } from '../../../shared/components/icon/icon';
import { APP_NAME } from '../../config/page-title.strategy';
import { ThemeService } from '../../preferences/theme.service';

/**
 * Moldura das telas públicas (login, MFA, redefinição de senha). A partir de 992 px, duas colunas:
 * painel da marca em navy institucional (`--app-topbar-bg`) à esquerda e o cartão do Bootstrap à
 * direita; abaixo disso, só o cartão com a marca no topo (ux.md §7).
 */
@Component({
  selector: 'app-auth-layout',
  imports: [RouterOutlet, NgbTooltip, Icon],
  template: `
    <div class="app-auth">
      <aside class="app-auth__brandpanel" data-bs-theme="dark">
        <p class="app-auth__brandname">
          <svg class="app-auth__logo app-auth__logo--lg" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
            <rect width="32" height="32" rx="9" fill="currentColor" opacity="0.16" />
            <path d="M9 21.5V10.5h2.8v11z M14.6 21.5v-7h2.8v7z M20.2 21.5v-4h2.8v4z" fill="currentColor" />
            <path d="M8.5 24.5h15" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
          {{ appName }}
        </p>
        <p class="app-auth__pitch">Carteiras, negociação, pagamentos e prestação de contas da assessoria num só lugar, com cada ação registrada.</p>
        <p class="app-auth__restricted">Acesso restrito. Todas as ações são registradas para auditoria.</p>
      </aside>

      <main class="app-auth__main" id="conteudo">
        <button
          type="button"
          class="btn app-auth__theme"
          (click)="theme.toggleTheme()"
          [attr.aria-label]="theme.effectiveTheme() === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'"
          [ngbTooltip]="theme.effectiveTheme() === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'"
          container="body"
          [openDelay]="300"
        >
          <app-icon [name]="theme.effectiveTheme() === 'dark' ? 'sun' : 'moon-stars'" />
        </button>
        <div class="card shadow-sm app-auth__panel">
          <div class="card-body">
            <!-- a marca no cartão só aparece quando o painel lateral está oculto (< 992 px) -->
            <p class="app-auth__brand">
              <svg class="app-auth__logo" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
                <rect width="32" height="32" rx="9" fill="currentColor" opacity="0.14" />
                <path d="M9 21.5V10.5h2.8v11z M14.6 21.5v-7h2.8v7z M20.2 21.5v-4h2.8v4z" fill="currentColor" />
                <path d="M8.5 24.5h15" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
              </svg>
              {{ appName }}
            </p>
            <router-outlet />
          </div>
        </div>
        <p class="app-auth__footer">Acesso restrito. Todas as ações são registradas para auditoria.</p>
      </main>
    </div>
  `,
  styleUrl: './auth-layout.scss',
})
export class AuthLayout {
  protected readonly appName = APP_NAME;
  protected readonly theme = inject(ThemeService);
}
