import { Directive, TemplateRef, ViewContainerRef, effect, inject, input } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';

/**
 * Renderiza o conteúdo somente se o usuário possuir ao menos uma das permissões
 * (`*appHasPermission="'USER_MANAGE'"` ou uma lista). Apenas ajuste de interface: o servidor
 * continua validando cada operação.
 */
@Directive({ selector: '[appHasPermission]' })
export class HasPermission {
  readonly permissions = input.required<string | readonly string[]>({ alias: 'appHasPermission' });
  private readonly auth = inject(AuthService);
  private readonly template = inject(TemplateRef);
  private readonly container = inject(ViewContainerRef);
  private rendered = false;

  constructor() {
    effect(() => {
      const value = this.permissions();
      const allowed = this.auth.hasAnyPermission(typeof value === 'string' ? [value] : value);
      if (allowed && !this.rendered) {
        this.container.createEmbeddedView(this.template);
        this.rendered = true;
      } else if (!allowed && this.rendered) {
        this.container.clear();
        this.rendered = false;
      }
    });
  }
}
