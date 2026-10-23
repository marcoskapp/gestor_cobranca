import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  ErrorHandler,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import {
  TitleStrategy,
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withRouterConfig,
} from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
import { provideLocale } from './core/config/locale.config';
import { provideMaterialDefaults } from './core/config/material.config';
import { PageTitleStrategy } from './core/config/page-title.strategy';
import { errorInterceptor } from './core/http/error.interceptor';
import { GlobalErrorHandler } from './core/http/global-error-handler';
import { ThemeService } from './core/preferences/theme.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
      withRouterConfig({ paramsInheritanceStrategy: 'always' }),
    ),
    // ordem: authInterceptor trata o 401 (renovação) antes de o errorInterceptor notificar
    provideHttpClient(withFetch(), withInterceptors([errorInterceptor, authInterceptor])),
    provideLocale(),
    provideMaterialDefaults(),
    { provide: TitleStrategy, useClass: PageTitleStrategy },
    { provide: ErrorHandler, useClass: GlobalErrorHandler },
    // restaura a sessão (cookie de refresh) antes da primeira navegação
    provideAppInitializer(() => inject(AuthService).restoreSession()),
    // tema e densidade aplicados desde a primeira tela (o onboarding é carregado com o shell)
    provideAppInitializer(() => {
      inject(ThemeService);
    }),
  ],
};
