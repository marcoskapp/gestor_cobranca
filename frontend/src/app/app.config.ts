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
import { provideBootstrapDefaults } from './core/config/bootstrap.config';
import { provideAppLocale } from './core/config/app-locale';
import { PageTitleStrategy } from './core/config/page-title.strategy';
import { isPortalPath } from './core/config/api';
import { errorInterceptor } from './core/http/error.interceptor';
import { GlobalErrorHandler } from './core/http/global-error-handler';
import { ThemeService } from './core/preferences/theme.service';
import { portalInterceptor } from './portal/data/portal.interceptor';

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
    // portalInterceptor só age em /api/portal/v1/me (token do devedor); authInterceptor ignora /api/portal/
    provideHttpClient(withFetch(), withInterceptors([errorInterceptor, authInterceptor, portalInterceptor])),
    provideAppLocale(),
    provideBootstrapDefaults(),
    // padrões do Angular Material (transitório) ficam nas rotas do shell e do portal
    { provide: TitleStrategy, useClass: PageTitleStrategy },
    { provide: ErrorHandler, useClass: GlobalErrorHandler },
    // restaura a sessão (cookie de refresh) antes da primeira navegação
    // (o portal do devedor nunca toca a sessão interna)
    provideAppInitializer(() => (isPortalPath(location.pathname) ? undefined : inject(AuthService).restoreSession())),
    // tema e densidade aplicados desde a primeira tela (o onboarding é carregado com o shell)
    provideAppInitializer(() => {
      inject(ThemeService);
    }),
  ],
};
