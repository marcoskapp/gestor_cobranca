import { ErrorHandler, Injectable, inject, isDevMode } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NotificationService } from '../notification/notification.service';

/**
 * Tratamento de erros não capturados. Nunca registra o objeto de erro completo no console em
 * produção (pode conter dados pessoais vindos da API); erros HTTP já foram notificados pelo
 * errorInterceptor.
 */
@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  private readonly notifications = inject(NotificationService);

  handleError(error: unknown): void {
    if (error instanceof HttpErrorResponse) {
      return;
    }
    this.notifications.error('Ocorreu um erro inesperado na aplicação. Recarregue a página e tente novamente.');
    if (isDevMode()) {
      // eslint-disable-next-line no-console -- diagnóstico somente em desenvolvimento
      console.error(error);
    } else {
      const name = error instanceof Error ? error.name : typeof error;
      // eslint-disable-next-line no-console -- apenas o tipo do erro, sem conteúdo
      console.error(`Erro não tratado: ${name}`);
    }
  }
}
