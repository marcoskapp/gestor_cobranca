import { Component, DestroyRef, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Permission } from '../../auth/permission';
import { describeStatus } from '../../../shared/status/status';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DialogTitle } from '../../../shared/dialog/dialog.directives';
import { Icon } from '../../../shared/components/icon/icon';
import { AuthService } from '../../auth/auth.service';
import { navigationFor } from '../navigation';
import { CommandPaletteService, PaletteCommand, filterCommands } from './command-palette.service';
import { QuickSearchApi, QuickSearchView, RecentRecord, RecentRecords, isQuickSearchable } from './quick-search';

const NAV_GROUP = 'Ir para';
const RECENT_GROUP = 'Recentes';
const CASE_GROUP = 'Casos';
const DEBTOR_GROUP = 'Devedores';
/** Espera depois da última tecla antes de buscar no servidor. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * Paleta de comandos acessível (padrão combobox + listbox da WAI-ARIA): setas movem a seleção,
 * Enter executa, Esc fecha. Lista destinos do menu permitidos ao usuário e ações registradas e, para quem vê
 * devedores (`DEBTOR_VIEW`), busca devedor e caso no servidor (nome com 3+ letras, CPF/CNPJ ou telefone completos,
 * código do título — revisão de produto 11/2026, item 1.3). O termo vai no corpo do POST, nunca na URL; com o campo
 * vazio aparecem os "Recentes" (só em memória da sessão).
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
          [attr.aria-label]="placeholder()"
          [placeholder]="placeholder() + '…'"
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
          @if (!searching()) {
            <li class="palette__empty" role="presentation">Nada encontrado para “{{ query() }}”.</li>
          }
        }
      </ul>
      <p class="palette__status" role="status">
        @if (searching()) {
          Buscando devedores e casos…
        } @else if (search.status() === 'error') {
          Não foi possível buscar devedores e casos agora.
        } @else if (found()?.truncated) {
          Há mais resultados: refine a busca (nome completo, CPF/CNPJ ou telefone).
        }
      </p>
      <p class="palette__footer" aria-hidden="true">
        <kbd>↑</kbd> <kbd>↓</kbd> navegar · <kbd>Enter</kbd> abrir
        @if (canSearchPeople()) {
          · devedor por nome (3+ letras), CPF/CNPJ ou telefone completos, ou código do título
        }
      </p>
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
    .palette__status {
      margin: 0;
      padding: 0 16px;
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .palette__status:not(:empty) {
      padding: 6px 16px;
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
  private readonly api = inject(QuickSearchApi);
  private readonly recents = inject(RecentRecords);
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('input');

  protected readonly query = signal('');
  protected readonly active = signal(0);

  protected readonly canSearchPeople = computed(() => {
    this.auth.user();
    return this.auth.hasPermission(Permission.DEBTOR_VIEW);
  });
  protected readonly placeholder = computed(() =>
    this.canSearchPeople() ? 'Buscar telas, ações, devedores e casos' : 'Buscar telas e ações',
  );

  /** Termo enviado ao servidor (depois da pausa na digitação); `undefined` = nada a buscar. */
  private readonly serverTerm = signal<string | undefined>(undefined);
  /** Termo digitado que espera a pausa (ou a resposta) do servidor. */
  private readonly pendingTerm = signal<string | undefined>(undefined);
  private debounce: ReturnType<typeof setTimeout> | undefined;
  protected readonly search = rxResource({
    params: () => this.serverTerm(),
    stream: ({ params }) => this.api.search(params),
  });
  /** Resultado só vale para o termo atual (a resposta de um termo antigo não aparece). */
  protected readonly found = computed<QuickSearchView | undefined>(() =>
    this.search.hasValue() && this.serverTerm() === this.pendingTerm() ? this.search.value() : undefined,
  );
  protected readonly searching = computed(
    () => this.pendingTerm() !== undefined && (this.pendingTerm() !== this.serverTerm() || this.search.isLoading()),
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.debounce));
  }

  private readonly navigation = computed<PaletteCommand[]>(() => {
    this.auth.user();
    return navigationFor((permission) => this.auth.hasPermission(permission)).flatMap((section) =>
      section.items
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

  /** Devedores e casos do servidor. Os casos vêm antes: no atendimento, o destino é o caso de quem ligou. */
  private readonly entities = computed<PaletteCommand[]>(() => {
    const view = this.found();
    if (!view) {
      return [];
    }
    const creditor = (id: string) => view.creditorNames[id] ?? 'Credor';
    const names = new Map(view.debtors.map((d) => [d.id, d.name]));
    const cases = view.cases.map((c) => {
      const record: RecentRecord = {
        kind: 'case',
        id: c.id,
        label: `${names.get(c.debtorId) ?? 'Devedor'} · ${creditor(c.creditorId)}`,
        hint: [describeStatus('case', c.status).label, view.portfolioNames[c.portfolioId]].filter(Boolean).join(' · '),
      };
      return this.recordCommand(record, CASE_GROUP);
    });
    const debtors = view.debtors.map((d) => {
      const record: RecentRecord = {
        kind: 'debtor',
        id: d.id,
        label: d.name,
        hint: d.status === 'ANONYMIZED' ? 'Dados pessoais eliminados' : [d.maskedDocument, ...d.creditorIds.map(creditor)].filter(Boolean).join(' · '),
      };
      return this.recordCommand(record, DEBTOR_GROUP);
    });
    return [...cases, ...debtors];
  });

  private readonly recentCommands = computed<PaletteCommand[]>(() =>
    this.recents.items().map((r) => this.recordCommand(r, RECENT_GROUP)),
  );

  protected readonly results = computed(() => {
    const all = [...this.navigation(), ...this.palette.commands()];
    const filtered = filterCommands(all, this.query());
    // mantém os grupos contíguos na ordem em que aparecem
    const groups = [...new Set(filtered.map((c) => c.group))];
    const local = groups.flatMap((g) => filtered.filter((c) => c.group === g));
    // campo vazio: recentes primeiro; com termo, os resultados do servidor chegam depois e entram no fim (a seleção
    // do teclado não pula enquanto a resposta chega)
    return this.query().trim() ? [...local, ...this.entities()] : [...this.recentCommands(), ...local];
  });

  protected readonly activeId = computed(() => (this.results().length ? `palette-opt-${this.active()}` : null));

  protected onInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.query.set(value);
    this.active.set(0);
    this.scheduleSearch(value);
  }

  private scheduleSearch(value: string): void {
    clearTimeout(this.debounce);
    const term = value.trim();
    if (!this.canSearchPeople() || !isQuickSearchable(term)) {
      this.pendingTerm.set(undefined);
      this.serverTerm.set(undefined);
      return;
    }
    this.pendingTerm.set(term);
    this.debounce = setTimeout(() => this.serverTerm.set(term), SEARCH_DEBOUNCE_MS);
  }

  /** Devedor ou caso como comando: abrir a ficha e lembrar em "Recentes". */
  private recordCommand(record: RecentRecord, group: string): PaletteCommand {
    const route = record.kind === 'case' ? `/cobranca/casos/${record.id}` : `/devedores/${record.id}`;
    return {
      id: `${group}:${record.kind}:${record.id}`,
      label: record.label,
      group,
      icon: record.kind === 'case' ? 'briefcase' : 'person',
      hint: record.hint,
      run: () => {
        this.recents.add(record);
        void this.router.navigateByUrl(route);
      },
    };
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
