import {
  HttpErrorResponse,
  HttpEvent,
  HttpHandlerFn,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, switchMap, throwError } from 'rxjs';
import { isApiRequest, isPortalRequest } from '../config/api';
import { SKIP_AUTH } from '../http/http-context';
import { AuthService } from './auth.service';

/**
 * Anexa `Authorization: Bearer` somente às chamadas da própria API (`/api/`, exceto o portal do
 * devedor, que tem token próprio — `portalInterceptor`). Em 401, tenta UMA
 * renovação (compartilhada entre requisições concorrentes) e repete a requisição; se a sessão
 * terminou, limpa o estado e leva ao login preservando a rota de retorno.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // o portal do devedor tem sessão e token próprios: o token interno nunca vai para /api/portal/
  if (!isApiRequest(req.url) || isPortalRequest(req.url) || req.context.get(SKIP_AUTH)) {
    return next(req);
  }
  const auth = inject(AuthService);
  if (auth.isTokenExpiring()) {
    // token sabidamente expirado (sessão ociosa): renova antes, sem provocar 401 no servidor
    return auth.refresh().pipe(
      switchMap((outcome) => {
        if (outcome === 'ended') {
          auth.endSession('expired');
          return throwError(() => sessionEnded(req));
        }
        return send(req, next, auth, outcome === 'renewed');
      }),
    );
  }
  return send(req, next, auth, false);
};

function send(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  auth: AuthService,
  alreadyRefreshed: boolean,
): Observable<HttpEvent<unknown>> {
  const tokenUsed = auth.accessToken();
  return next(withToken(req, tokenUsed)).pipe(
    catchError((error: unknown) => {
      if (!isUnauthorized(error)) {
        return throwError(() => error);
      }
      if (alreadyRefreshed) {
        auth.endSession('expired');
        return throwError(() => error);
      }
      const current = auth.accessToken();
      if (current && current !== tokenUsed) {
        // outra requisição já renovou o token enquanto esta estava em andamento
        return retry(req, next, auth, current);
      }
      return auth.refresh().pipe(
        switchMap((outcome) => {
          const renewed = auth.accessToken();
          if (outcome === 'renewed' && renewed) {
            return retry(req, next, auth, renewed);
          }
          if (outcome === 'ended') {
            auth.endSession('expired');
          }
          return throwError(() => error);
        }),
      );
    }),
  );
}

function retry(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  auth: AuthService,
  token: string,
): Observable<HttpEvent<unknown>> {
  return next(withToken(req, token)).pipe(
    catchError((error: unknown) => {
      if (isUnauthorized(error)) {
        auth.endSession('expired');
      }
      return throwError(() => error);
    }),
  );
}

function sessionEnded(req: HttpRequest<unknown>): HttpErrorResponse {
  return new HttpErrorResponse({ status: 401, statusText: 'Unauthorized', url: req.urlWithParams });
}

function withToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}

function isUnauthorized(error: unknown): error is HttpErrorResponse {
  return error instanceof HttpErrorResponse && error.status === 401;
}
