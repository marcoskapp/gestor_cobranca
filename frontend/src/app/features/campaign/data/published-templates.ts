import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { TemplatesApi } from '../../communication/data/communication.api';
import { TemplateRef } from './campaign.rules';

/**
 * Templates publicados que uma campanha do credor pode usar (do credor e da assessoria). A
 * consulta exige `TEMPLATE_MANAGE` ou `MESSAGE_SEND`; sem elas, devolve `null` e a tela deixa a
 * conferência de template e variáveis para a publicação no servidor.
 */
@Injectable({ providedIn: 'root' })
export class PublishedTemplates {
  private readonly api = inject(TemplatesApi);
  private readonly auth = inject(AuthService);

  canRead(): boolean {
    return this.auth.hasAnyPermission([Permission.TEMPLATE_MANAGE, Permission.MESSAGE_SEND]);
  }

  forCreditor(creditorId: string): Observable<TemplateRef[] | null> {
    if (!this.canRead()) {
      return of(null);
    }
    return this.api.search({ creditorId: null, code: null, channel: null, status: 'PUBLISHED' }, { page: 0, size: 200 }).pipe(
      map((page) =>
        page.content
          .filter((t) => t.creditorId === null || t.creditorId === creditorId)
          .map((t): TemplateRef => ({ code: t.code, channel: t.channel, creditorId: t.creditorId, name: t.name, templateVersion: t.templateVersion, variables: t.variables })),
      ),
      catchError(() => of(null)),
    );
  }
}
