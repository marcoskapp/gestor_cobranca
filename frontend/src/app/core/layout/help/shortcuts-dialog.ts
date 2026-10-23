import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { KeyboardShortcuts, formatKeys } from '../../keyboard/keyboard-shortcuts.service';

/** Ajuda de atalhos de teclado ativos (tecla ?). */
@Component({
  selector: 'app-shortcuts-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>Atalhos de teclado</h2>
    <mat-dialog-content>
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
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-flat-button type="button" mat-dialog-close>Fechar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .sc__group {
      margin: 8px 0 6px;
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--mat-sys-on-surface-variant);
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
      border-bottom: 1px solid var(--app-border);
    }
    dt {
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
