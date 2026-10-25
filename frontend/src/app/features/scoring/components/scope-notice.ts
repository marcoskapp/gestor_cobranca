import { Component, input } from '@angular/core';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';

/**
 * Telas de governança do score usam dados de todos os credores da assessoria: quem tem escopo
 * restrito vê esta explicação em vez de um erro de acesso (a API responderia 403).
 */
@Component({
  selector: 'app-scoring-scope-notice',
  imports: [EmptyState],
  template: `
    <app-empty-state
      icon="shield-lock"
      [title]="title()"
      message="Modelos, execuções e monitoramento usam dados de todos os credores da assessoria. Peça a um administrador o acesso a todos os credores, se a sua função exigir."
    />
  `,
})
export class ScopeNotice {
  readonly title = input('Disponível somente para quem acessa todos os credores.');
}
