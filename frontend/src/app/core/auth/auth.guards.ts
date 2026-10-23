import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthStep } from './auth.models';
import { AUTH_ROUTES, AuthService } from './auth.service';

/** Exige sessão autenticada; caso contrário vai ao login guardando a rota de retorno. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (auth.isAuthenticated()) {
    return true;
  }
  return inject(Router).createUrlTree([AUTH_ROUTES.login], { queryParams: { returnUrl: state.url } });
};

/**
 * Exige ao menos uma das permissões declaradas em `data.permissions` da rota. É apenas
 * conveniência de navegação: o servidor continua sendo a autoridade de autorização.
 */
export const permissionGuard: CanActivateFn = (route) => {
  const required = (route.data['permissions'] as readonly string[] | undefined) ?? [];
  if (inject(AuthService).hasAnyPermission(required)) {
    return true;
  }
  return inject(Router).createUrlTree(['/acesso-negado']);
};

/** Telas de login: quem já está autenticado vai para a página inicial. */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isAuthenticated() ? inject(Router).createUrlTree(['/']) : true;
};

/** Etapas intermediárias só são acessíveis com o step token correspondente em memória. */
export function stepGuard(step: AuthStep): CanActivateFn {
  return () => {
    const pending = inject(AuthService).pendingStep();
    if (pending?.status === step) {
      return true;
    }
    return inject(Router).createUrlTree([AUTH_ROUTES.login]);
  };
}
