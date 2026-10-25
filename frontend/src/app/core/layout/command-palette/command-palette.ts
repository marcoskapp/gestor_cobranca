import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DialogTitle } from '../../../shared/dialog/dialog.directives';
import { Icon } from '../../../shared/components/icon/icon';
import { AuthService } from '../../auth/auth.service';
import { NAVIGATION } from '../navigation';
import { CommandPaletteService, PaletteCommand, filterCommands } from './command-palette.service';

const NAV_GROUP = 'Ir para';

/**
 * Paleta de comandos acessível (padrão combobox + listbox da WAI-ARIA): setas movem a seleção,
 * Enter executa, Esc fecha. Lista destinos do menu permitidos ao usuário e ações registradas.
 */
@Component({
  selector: 'app-command-palette',
  imports: [Icon, DialogTitle],
  template: `
    <div class="palette">
      <h2 appDialogTitle class="visually-hidden">Busca rápida</h2>
      <div class="palette__search">
        <app-icon name="search" />
        <input
          #input
          ngbAutofocus
          class="palette__input"
          type="text"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded="true"
          aria-controls="palette-list"
          [attr.aria-activedescendant]="activeId()"
          aria-label="Buscar telas e ações"
          placeholder="Buscar telas e ações…"
          autocomplete="off"
          spellcheck="false"
          [value]="query()"
          (input)="onInput($event)"
          (keydown)="onKeydown($event)"
        />
        <kbd>Esc</kbd>
      </div>
      <ul class="palette__list" id="palette-list" role="listbox" aria-label="Resultados">
        @for (command of results(); track command.id; let i = $index) {
          @if (i === 0 || results()[i - 1].group !== command.group) {
            <li class="palette__group" role="presentation">{{ command.group }}</li>
          }
          <!-- padrão combobox: o foco fica no campo e a opção ativa é indicada por aria-activedescendant;
               o teclado é tratado no campo (setas/Enter), o clique é só um atalho para mouse/toque -->
          <!-- eslint-disable-next-line @angular-eslint/template/click-events-have-key-events, @angular-eslint/template/interactive-supports-focus -->
          <li
            class="palette__option"
            role="option"
            [id]="'palette-opt-' + i"
            [attr.aria-selected]="i === active()"
            [class.palette__option--active]="i === active()"
            (click)="execute(command)"
            (mousemove)="active.set(i)"
          >
            <app-icon [name]="command.icon" />
            <span class="palette__label">{{ command.label }}</span>
            @if (command.hint) {
              <span class="palette__hint">{{ command.hint }}</span>
            }
          </li>
        } @empty {
          <li class="palette__empty" role="presentation">Nada encontrado para “{{ query() }}”.</li>
        }
      </ul>
      <p class="palette__footer" aria-hidden="true"><kbd>↑</kbd> <kbd>↓</kbd> navegar · <kbd>Enter</kbd> abrir</p>
    </div>
  `,
  styles: `
    .palette {
      display: flex;
      flex-direction: column;
      max-height: 70vh;
    }
    .palette__search {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 12px 16px;
      border-bottom: 1px solid var(--app-border);
      color: var(--bs-secondary-color);
    }
    .palette__input {
      flex: 1;
      min-width: 0;
      border: 0;
      outline: none;
      background: transparent;
      color: var(--bs-body-color);
      font: inherit;
      font-size: var(--app-text-lg);
    }
    .palette__list {
      flex: 1;
      margin: 0;
      padding: 6px 0;
      overflow-y: auto;
      list-style: none;
    }
    .palette__group {
      padding: 8px 16px 4px;
      font-size: var(--app-text-xs);
      font-weight: 500;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .palette__option {
      display: flex;
      align-items: center;
      gap: 12px;
      min-height: 40px;
      margin: 0 6px;
      padding: 0 10px;
      border-radius: var(--bs-border-radius);
      cursor: pointer;
    }
    .palette__option--active {
      background: var(--bs-primary-bg-subtle);
      color: var(--bs-primary-text-emphasis);
    }
    .palette__label {
      flex: 1;
    }
    .palette__hint {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .palette__empty {
      padding: 24px 16px;
      text-align: center;
      color: var(--bs-secondary-color);
    }
    .palette__footer {
      margin: 0;
      padding: 8px 16px;
      border-top: 1px solid var(--app-border);
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
  `,
})
export class CommandPalette {
  private readonly ref = inject(DialogRef);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly palette = inject(CommandPaletteService);
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('input');

  protected readonly query = signal('');
  protected readonly active = signal(0);

  private readonly navigation = computed<PaletteCommand[]>(() => {
    this.auth.user();
    return NAVIGATION.flatMap((section) =>
      section.items
        .filter((item) => this.auth.hasAnyPermission(item.permissions))
        .map((item) => ({
          id: `nav:${item.route}`,
          label: item.label,
          group: NAV_GROUP,
          icon: item.icon,
          hint: section.label ?? undefined,
          run: () => void this.router.navigateByUrl(item.route),
        })),
    );
  });

  protected readonly results = computed(() => {
    const all = [...this.navigation(), ...this.palette.commands()];
    const filtered = filterCommands(all, this.query());
    // mantém os grupos contíguos na ordem em que aparecem
    const groups = [...new Set(filtered.map((c) => c.group))];
    return groups.flatMap((g) => filtered.filter((c) => c.group === g));
  });

  protected readonly activeId = computed(() => (this.results().length ? `palette-opt-${this.active()}` : null));

  protected onInput(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    this.active.set(0);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.results().length;
    if (event.key === 'ArrowDown' && count) {
      event.preventDefault();
      this.active.update((i) => (i + 1) % count);
      this.scrollActive();
    } else if (event.key === 'ArrowUp' && count) {
      event.preventDefault();
      this.active.update((i) => (i - 1 + count) % count);
      this.scrollActive();
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const command = this.results()[this.active()];
      if (command) {
        this.execute(command);
      }
    }
  }

  protected execute(command: PaletteCommand): void {
    this.ref.close();
    command.run();
  }

  private scrollActive(): void {
    const host = this.input().nativeElement.closest('.palette');
    queueMicrotask(() => host?.querySelector(`#palette-opt-${this.active()}`)?.scrollIntoView?.({ block: 'nearest' }));
  }
}
