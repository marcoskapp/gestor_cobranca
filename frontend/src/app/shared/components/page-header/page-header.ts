import { Component, input } from '@angular/core';

/**
 * Cabeçalho de página: título (h1), descrição e área de ações (botão primário à direita).
 * Expõe as âncoras `page-header` e `page-actions` usadas pelos tours.
 */
@Component({
  selector: 'app-page-header',
  template: `
    <header class="app-page-header" data-tour="page-header">
      <div class="app-page-header__text">
        <h1 class="app-page-header__title">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="app-page-header__subtitle">{{ subtitle() }}</p>
        }
      </div>
      <div class="app-page-header__actions" data-tour="page-actions">
        <ng-content />
      </div>
    </header>
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
}
