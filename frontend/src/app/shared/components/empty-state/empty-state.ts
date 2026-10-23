import { Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/**
 * Estado vazio: ícone, título, orientação e (opcional) ação primária via conteúdo projetado.
 * Use `tone="error"` para falha de carga com ação de tentar novamente.
 *
 * ```html
 * <app-empty-state icon="folder_open" title="Nenhuma carteira ainda"
 *   message="Crie a primeira carteira para começar a importar títulos.">
 *   <button mat-flat-button (click)="create()">Nova carteira</button>
 * </app-empty-state>
 * ```
 */
@Component({
  selector: 'app-empty-state',
  imports: [MatIconModule],
  host: { '[attr.role]': "tone() === 'error' ? 'alert' : 'status'" },
  template: `
    <div class="empty" [class.empty--error]="tone() === 'error'" [class.empty--compact]="compact()">
      <span class="empty__icon" aria-hidden="true"><mat-icon>{{ icon() }}</mat-icon></span>
      <p class="empty__title">{{ title() }}</p>
      @if (message()) {
        <p class="empty__message">{{ message() }}</p>
      }
      <div class="empty__actions"><ng-content /></div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 32px 16px;
      text-align: center;
    }
    .empty--compact {
      padding: 16px;
    }
    .empty__icon {
      display: grid;
      place-items: center;
      width: 48px;
      height: 48px;
      margin-bottom: 12px;
      border-radius: 50%;
      background: var(--app-neutral-bg);
      color: var(--app-neutral-fg);
    }
    .empty--error .empty__icon {
      background: var(--app-danger-bg);
      color: var(--app-danger-fg);
    }
    .empty__title {
      margin: 0;
      font-size: var(--app-text-md);
      font-weight: 600;
      color: var(--mat-sys-on-surface);
    }
    .empty__message {
      max-width: 480px;
      margin: 4px 0 0;
      font-size: var(--app-text-sm);
      color: var(--mat-sys-on-surface-variant);
    }
    .empty__actions:not(:empty) {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 8px;
      margin-top: 16px;
    }
  `,
})
export class EmptyState {
  readonly icon = input('inbox');
  readonly title = input.required<string>();
  readonly message = input<string>();
  readonly tone = input<'neutral' | 'error'>('neutral');
  readonly compact = input(false);
}
