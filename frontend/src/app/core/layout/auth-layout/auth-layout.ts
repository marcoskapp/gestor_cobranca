import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterOutlet } from '@angular/router';
import { APP_NAME } from '../../config/page-title.strategy';
import { ThemeService } from '../../preferences/theme.service';

/** Moldura das telas públicas (login, MFA, redefinição de senha). */
@Component({
  selector: 'app-auth-layout',
  imports: [RouterOutlet, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <main class="app-auth" id="conteudo">
      <button
        mat-icon-button
        type="button"
        class="app-auth__theme"
        (click)="theme.toggleTheme()"
        [attr.aria-label]="theme.effectiveTheme() === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'"
        [matTooltip]="theme.effectiveTheme() === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'"
      >
        <mat-icon aria-hidden="true">{{ theme.effectiveTheme() === 'dark' ? 'light_mode' : 'dark_mode' }}</mat-icon>
      </button>
      <div class="app-auth__panel">
        <p class="app-auth__brand">
          <svg class="app-auth__logo" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
            <rect width="32" height="32" rx="8" fill="currentColor" opacity="0.14" />
            <path d="M9 21.5V10.5h2.8v11z M14.6 21.5v-7h2.8v7z M20.2 21.5v-4h2.8v4z" fill="currentColor" />
            <path d="M8.5 24.5h15" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
          {{ appName }}
        </p>
        <router-outlet />
      </div>
      <p class="app-auth__footer">Acesso restrito. Todas as ações são registradas para auditoria.</p>
    </main>
  `,
  styleUrl: './auth-layout.scss',
})
export class AuthLayout {
  protected readonly appName = APP_NAME;
  protected readonly theme = inject(ThemeService);
}
