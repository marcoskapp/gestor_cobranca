import { Component, input, output } from '@angular/core';
import { Icon } from '../../../shared/components/icon/icon';

/**
 * Filtro aplicado pelo clique num gráfico (ux.md §22): chip "Credor: Banco X ×" acima dos cartões, com o anúncio
 * "Painel filtrado por …" ao leitor de tela. Remover limpa a dimensão; o "voltar" do navegador também desfaz.
 */
@Component({
  selector: 'app-focus-chip',
  imports: [Icon],
  template: `
    <p class="visually-hidden" aria-live="polite">{{ label() ? 'Painel filtrado por ' + label() + '.' : '' }}</p>
    @if (label(); as l) {
      <div class="fc">
        <span class="fc__chip">
          <app-icon name="funnel" />
          {{ l }}
          <button type="button" class="fc__remove" [attr.aria-label]="'Remover filtro de ' + dimension(l)" (click)="removed.emit()">
            <app-icon name="x-lg" />
          </button>
        </span>
      </div>
    }
  `,
  styles: `
    .fc {
      margin: 0 0 12px;
    }
    .fc__chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      max-width: 100%;
      padding: 2px 4px 2px 10px;
      border: 1px solid var(--bs-border-color);
      border-radius: 999px;
      background: var(--bs-tertiary-bg);
      font-size: var(--app-text-sm);
    }
    .fc__remove {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 24px;
      height: 24px;
      padding: 0;
      border: 0;
      border-radius: 50%;
      background: none;
      color: inherit;
      &:hover,
      &:focus-visible {
        background: var(--app-chart-track);
      }
    }
  `,
})
export class FocusChip {
  /** "Credor: Banco X" (nulo = sem filtro do gráfico). */
  readonly label = input<string | null>(null);
  readonly removed = output<void>();

  /** "Credor: Banco X" → "credor". */
  protected dimension(label: string): string {
    return label.split(':')[0].trim().toLocaleLowerCase('pt-BR');
  }
}
