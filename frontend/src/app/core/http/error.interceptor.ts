import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from '../notification/notification.service';
import { SKIP_ERROR_NOTIFICATION } from './http-context';
import { toApiError, userMessage } from './problem';

/**
 * Notificação centralizada de erros HTTP. O 401 é de responsabilidade do authInterceptor
 * (renovação e retorno ao login). O erro é sempre repassado para que a tela possa reagir
 * (ex.: marcar campos inválidos).
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notifications = inject(NotificationService);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status !== 401 &&
        !req.context.get(SKIP_ERROR_NOTIFICATION)
      ) {
        notifications.error(userMessage(toApiError(error)));
      }
      return throwError(() => error);
    }),
  );
};
