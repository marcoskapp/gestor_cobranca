import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

/** Navegação entre as telas de qualidade (avaliações, ocorrências e formulários). */
@Component({
  selector: 'app-quality-tabs',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav aria-label="Telas de qualidade" class="mb-3">
      <ul class="nav nav-tabs">
        @for (t of tabs; track t.route) {
          <li class="nav-item">
            <a class="nav-link" [routerLink]="t.route" routerLinkActive="active" #rla="routerLinkActive" [attr.aria-current]="rla.isActive ? 'page' : null">{{ t.label }}</a>
          </li>
        }
      </ul>
    </nav>
  `,
})
export class QualityTabs {
  protected readonly tabs = [
    { route: '/remuneracao/qualidade/avaliacoes', label: 'Avaliações' },
    { route: '/remuneracao/qualidade/ocorrencias', label: 'Ocorrências de conduta' },
    { route: '/remuneracao/qualidade/formularios', label: 'Formulários' },
  ];
}
