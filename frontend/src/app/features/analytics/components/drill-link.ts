import { NgTemplateOutlet } from '@angular/common';
import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DrillTarget } from '../data/drill-down';

/**
 * Número de painel que leva à lista com a base do indicador (drill-down, `data/drill-down.ts`). Sem destino (recorte
 * que a lista não representa ou perfil que não abre a lista) o conteúdo fica como texto. O link mantém a cor do texto
 * (azul é para ação) e diz ao leitor de tela para onde vai.
 */
@Component({
  selector: 'app-drill-link',
  imports: [NgTemplateOutlet, RouterLink],
  template: `
    <ng-template #content><ng-content /></ng-template>
    @if (to(); as t) {
      <a class="link-body-emphasis app-drill-link" [routerLink]="t.route" [queryParams]="t.queryParams" [attr.title]="t.label">
        <ng-container *ngTemplateOutlet="content" />
        <span class="visually-hidden">: {{ t.label }}</span>
      </a>
    } @else {
      <ng-container *ngTemplateOutlet="content" />
    }
  `,
  styles: `
    .app-drill-link {
      text-decoration-line: underline;
      text-decoration-style: dotted;
      text-decoration-thickness: 1px;
      text-decoration-color: var(--bs-secondary-color);
      text-underline-offset: 3px;
    }
    .app-drill-link:hover,
    .app-drill-link:focus-visible {
      text-decoration-style: solid;
      text-decoration-color: currentColor;
    }
  `,
})
export class DrillLink {
  readonly to = input<DrillTarget | null>(null);
}
