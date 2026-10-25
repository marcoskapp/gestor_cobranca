import { Component, computed, inject } from '@angular/core';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { KeyboardShortcuts, formatKeys } from '../../keyboard/keyboard-shortcuts.service';

/** Ajuda de atalhos de teclado ativos (tecla ?). */
@Component({
  selector: 'app-shortcuts-dialog',
  imports: [...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Atalhos de teclado</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      @for (group of groups(); track group.name) {
        <h3 class="sc__group">{{ group.name }}</h3>
        <dl class="sc__list">
          @for (s of group.items; track s.id) {
            <div class="sc__row">
              <dt>
                @for (k of keys(s.keys); track $index) {
                  @if (!$first) {
                    <span aria-hidden="true">+</span>
                  }
                  <kbd>{{ k }}</kbd>
                }
              </dt>
              <dd>{{ s.description }}</dd>
            </div>
          }
        </dl>
      }
    </div>
    <div class="modal-footer">
      <button type="button" class="btn btn-primary" ngbAutofocus appDialogClose>Fechar</button>
    </div>
  `,
  styles: `
    .sc__group {
      margin: 8px 0 6px;
      font-size: var(--app-text-xs);
      font-weight: 500;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .sc__list {
      margin: 0 0 12px;
    }
    .sc__row {
      display: grid;
      grid-template-columns: 160px 1fr;
      align-items: center;
      gap: 12px;
      padding: 6px 0;
      border-bottom: var(--bs-border-width) solid var(--bs-border-color);
    }
    dt {
      font-weight: inherit;
      display: flex;
      align-items: center;
      gap: 4px;
    }
    dd {
      margin: 0;
    }
  `,
})
export class ShortcutsDialog {
  private readonly shortcuts = inject(KeyboardShortcuts);
  protected readonly groups = computed(() => {
    const list = [
      ...this.shortcuts.shortcuts(),
      { id: 'esc', keys: 'Esc', description: 'Fechar diálogo, menu ou tour', group: 'Geral' },
    ];
    const names = [...new Set(list.map((s) => s.group))];
    return names.map((name) => ({ name, items: list.filter((s) => s.group === name) }));
  });

  protected keys(keys: string): string[] {
    return formatKeys(keys);
  }
}
