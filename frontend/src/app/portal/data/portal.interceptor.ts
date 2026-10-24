import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, filter, switchMap, throwError } from 'rxjs';
import { SessionGrant } from './portal.models';
import { PORTAL_API } from './portal.api';
import { PortalSession } from './portal-session';

const ME = `${PORTAL_API}/me`;
const REFRESH = `${ME}/session/refresh`;

/**
 * Anexa o token do portal somente às chamadas do devedor (`/api/portal/v1/me`). Token perto do
 * vencimento é renovado antes (a sessão no servidor continua valendo); 401 encerra a sessão local e
 * volta à entrada do portal, com aviso de sessão expirada.
 */
export const portalInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(ME)) {
    return next(req);
  }
  const session = inject(PortalSession);
  const router = inject(Router);
  const token = session.token();
  const send = (value: string | null) => next(value ? req.clone({ setHeaders: { Authorization: `Bearer ${value}` } }) : req)
    .pipe(catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        const slug = session.slug();
        session.clear('expired');
        if (slug) {
          void router.navigate(['/portal', slug]);
        }
      }
      return throwError(() => error);
    }));
  if (token && req.url !== REFRESH && session.expiringSoon()) {
    return next(req.clone({ url: REFRESH, method: 'POST', body: {}, setHeaders: { Authorization: `Bearer ${token}` } }))
      .pipe(
        filter((event): event is HttpResponse<SessionGrant> => event instanceof HttpResponse),
        switchMap((response) => {
          const grant = response.body;
          if (grant?.accessToken && grant.expiresAt && grant.sessionId) {
            session.renew(grant);
          }
          return send(session.token());
        }),
        catchError(() => send(token)),
      );
  }
  return send(token);
};
