import { Component, input } from '@angular/core';

/** Cabeçalho de página: título (h1), descrição e área de ações. */
@Component({
  selector: 'app-page-header',
  template: `
    <header class="app-page-header">
      <div class="app-page-header__text">
        <h1 class="app-page-header__title">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="app-page-header__subtitle">{{ subtitle() }}</p>
        }
      </div>
      <div class="app-page-header__actions">
        <ng-content />
      </div>
    </header>
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
}
