import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { CREDITOR_API, CREDITOR_PUBLIC } from './creditor.api';
import { CreditorSession } from './creditor-session';

/**
 * Anexa o token do portal do credor só às chamadas autenticadas de `/api/creditor/v1` (nunca às rotas públicas de
 * autenticação nem a outra API). Token perto do vencimento é renovado antes; 401 tenta renovar uma vez pelo cookie e,
 * sem sessão, volta à entrada do portal do credor com o aviso de sessão encerrada.
 */
export const creditorInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(CREDITOR_API + '/') || CREDITOR_PUBLIC.some((p) => req.url.startsWith(p))) {
    return next(req);
  }
  const session = inject(CreditorSession);
  const router = inject(Router);
  const withToken = (r: HttpRequest<unknown>) => {
    const token = session.token();
    return token ? r.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : r;
  };
  const leave = () => {
    const segments = router.url.split(/[/?#]/);
    const slug = session.me()?.tenantSlug ?? (segments[1] === 'credor' ? segments[2] : '');
    session.clear('expired');
    if (slug) {
      void router.navigate(['/credor', slug, 'entrar'], { replaceUrl: true });
    }
  };
  const send = () =>
    next(withToken(req)).pipe(
      catchError((error: unknown) => {
        if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
          return throwError(() => error);
        }
        return session.restore().pipe(
          switchMap((outcome) => {
            if (outcome === 'renewed') {
              return next(withToken(req));
            }
            // só encerra quando o servidor recusou a renovação; rede, 5xx e 429 mantêm a sessão local
            if (outcome === 'ended') {
              leave();
            }
            return throwError(() => error);
          }),
        );
      }),
    );
  if (session.token() && session.expiringSoon()) {
    return session.restore().pipe(
      switchMap((outcome) => {
        if (outcome === 'ended') {
          leave();
          return throwError(() => new HttpErrorResponse({ status: 401, statusText: 'Unauthorized', url: req.urlWithParams }));
        }
        return send();
      }),
    );
  }
  return send();
};
