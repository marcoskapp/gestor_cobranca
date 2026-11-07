import { AfterViewInit, Component, DestroyRef, ElementRef, computed, inject, input, signal } from '@angular/core';
import { NgbDropdownModule, NgbNav, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../icon/icon';

/** Aba recolhida no menu "Mais": o link original continua no DOM (oculto) e é ele que se aciona ao escolher. */
interface HiddenTab {
  readonly domId: string;
  readonly label: string;
  readonly active: boolean;
  readonly disabled: boolean;
}

/** Espaço reservado para o botão "Mais N ▾" quando nem todas as abas cabem (px). */
const MORE_RESERVE = 112;
/** Além da largura da aba aberta recolhida, o que o botão precisa para a contagem e a seta (px). */
const ACTIVE_EXTRA = 56;

const sameTabs = (a: readonly HiddenTab[], b: readonly HiddenTab[]) =>
  a.length === b.length && a.every((t, i) => t.domId === b[i].domId && t.label === b[i].label && t.active === b[i].active && t.disabled === b[i].disabled);

/**
 * Abas que não cabem na faixa (`ul ngbNav.nav-underline.app-tabs`) vão para "Mais N ▾" (ux.md §12): o componente é o
 * último item da faixa, mede a largura disponível e oculta as abas que sobram, na ordem, listando-as no menu. Com uma
 * aba recolhida aberta, o botão mostra o nome dela e "+N" (o nome encolhe com reticências; a contagem e a seta nunca
 * saem da faixa). Escolher no menu aciona o próprio link da aba — estado na URL, permissões e carregamento continuam
 * os da tela. Recalcula ao mudar a largura, os rótulos (contagens que chegam depois) ou a aba ativa.
 *
 * Uso: `<ul ngbNav #tabs="ngbNav" class="nav-underline app-tabs"> … <li [appTabsMore]="tabs"></li></ul>`.
 */
@Component({
  selector: 'li[appTabsMore]',
  imports: [NgbDropdownModule, NgbTooltip, Icon],
  host: {
    class: 'nav-item app-tabs-more',
    // dentro de `role="tablist"`, como os `li[ngbNavItem]`
    role: 'presentation',
    '[class.d-none]': '!hidden().length',
    // as teclas do menu não sobem para o `ngbNav` (que levaria o foco para a primeira aba)
    '(keydown)': 'keepMenuKeys($event)',
  },
  template: `
    <div ngbDropdown display="dynamic" placement="bottom-end" class="app-tabs-more__dropdown">
      <button
        type="button"
        class="nav-link app-tabs-more__toggle"
        [class.active]="!!active()"
        ngbDropdownToggle
        [ngbTooltip]="hint()"
        [attr.aria-label]="ariaLabel()"
      >
        <span class="app-tabs-more__label">{{ active()?.label ?? 'Mais' }}</span>
        @if (count()) {
          <span class="badge rounded-pill app-tabs-more__count" aria-hidden="true">{{ count() }}</span>
        }
        <app-icon name="chevron-down" class="app-tabs-more__chevron" />
      </button>
      <div ngbDropdownMenu class="shadow app-menu">
        @for (t of hidden(); track t.domId) {
          <button ngbDropdownItem type="button" [disabled]="t.disabled" [class.active]="t.active" [attr.aria-current]="t.active ? 'true' : null" (click)="open(t)">
            {{ t.label }}
          </button>
        }
      </div>
    </div>
  `,
  styles: `
    :host {
      flex: 0 1 auto;
      min-width: 0;
    }
    .app-tabs-more__dropdown {
      display: flex;
      min-width: 0;
    }
    .app-tabs-more__toggle {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      max-width: 100%;
      border: 0;
      background: none;
    }
    .app-tabs-more__label {
      min-width: 2.5rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .app-tabs-more__count,
    .app-tabs-more__chevron {
      flex-shrink: 0;
    }
    .app-tabs-more__count {
      background: var(--bs-secondary-bg);
      color: var(--bs-secondary-color);
      font-size: var(--app-text-xs);
      font-weight: var(--app-weight-medium);
    }
    .app-tabs-more__chevron {
      font-size: 0.75em;
    }
  `,
})
export class TabsMore implements AfterViewInit {
  /** O `ngbNav` da faixa (aba ativa). */
  readonly nav = input.required<NgbNav>({ alias: 'appTabsMore' });

  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly destroyRef = inject(DestroyRef);

  protected readonly hidden = signal<readonly HiddenTab[]>([], { equal: sameTabs });
  protected readonly active = computed(() => this.hidden().find((t) => t.active) ?? null);
  /** "3" sem aba recolhida aberta; "+2" com uma delas aberta; nada quando ela é a única recolhida. */
  protected readonly count = computed(() => {
    const n = this.hidden().length;
    return this.active() ? (n > 1 ? `+${n - 1}` : '') : String(n);
  });
  protected readonly hint = computed(() => `Mais abas: ${this.hidden().map((t) => t.label).join(', ')}`);
  protected readonly ariaLabel = computed(() => (this.active() ? `Mais abas (aberta: ${this.active()!.label})` : `Mais abas (${this.hidden().length})`));

  private frame = 0;

  ngAfterViewInit(): void {
    const strip = this.host.parentElement;
    if (!strip || typeof ResizeObserver === 'undefined') {
      return;
    }
    const resize = new ResizeObserver(() => this.schedule());
    resize.observe(strip);
    // rótulos, abas condicionais e aba ativa (`aria-selected` muda com clique, `[activeId]` ou `select()`); as mudanças
    // deste próprio item não contam e o `layout()` só altera `style`, que fica fora do filtro
    const mutations = new MutationObserver((records) => {
      if (records.some((r) => !this.host.contains(r.target))) {
        this.schedule();
      }
    });
    mutations.observe(strip, {
      childList: true,
      subtree: true,
      characterData: true,
      attributes: true,
      // `aria-disabled` também vem do link da aba e fica fora do que o `layout()` altera (só `style`)
      attributeFilter: ['aria-selected', 'aria-disabled'],
    });
    // ← → Home End com abas recolhidas: só as visíveis e o botão "Mais" (o `ngbNav` percorreria também as ocultas);
    // registrado depois do listener do `ngbNav`, corrige o foco que ele deixou
    const keys = (event: KeyboardEvent) => this.moveFocus(event, strip);
    strip.addEventListener('keydown', keys);
    this.destroyRef.onDestroy(() => {
      resize.disconnect();
      mutations.disconnect();
      strip.removeEventListener('keydown', keys);
      cancelAnimationFrame(this.frame);
    });
    this.schedule();
  }

  /** Teclas que o `ngbDropdown` trata e que o `ngbNav` da faixa também escuta (← e → seguem para `moveFocus`). */
  private static readonly MENU_KEYS = new Set(['ArrowUp', 'ArrowDown', 'Home', 'End']);

  protected keepMenuKeys(event: KeyboardEvent): void {
    if (TabsMore.MENU_KEYS.has(event.key)) {
      event.stopPropagation();
    }
  }

  private moveFocus(event: KeyboardEvent, strip: HTMLElement): void {
    if (!this.hidden().length || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      return;
    }
    const toggle = this.host.querySelector<HTMLElement>('.app-tabs-more__toggle');
    const visible = [...strip.querySelectorAll<HTMLElement>(':scope > li > .nav-link')].filter(
      (link) => link !== toggle && !link.classList.contains('disabled') && getComputedStyle(link.parentElement!).display !== 'none',
    );
    const order = toggle ? [...visible, toggle] : visible;
    const from = order.indexOf(event.target as HTMLElement);
    if (from < 0 || !order.length) {
      return;
    }
    const to =
      event.key === 'Home' ? 0 : event.key === 'End' ? order.length - 1 : (from + (event.key === 'ArrowRight' ? 1 : -1) + order.length) % order.length;
    event.preventDefault();
    order[to].focus();
  }

  /** Abre a aba recolhida pelo próprio link (mesmo fluxo de um clique na faixa). */
  protected open(tab: HiddenTab): void {
    (this.host.parentElement?.querySelector<HTMLElement>(`#${CSS.escape(tab.domId)}`))?.click();
  }

  private schedule(): void {
    cancelAnimationFrame(this.frame);
    this.frame = requestAnimationFrame(() => this.layout());
  }

  /** Mostra todas, mede e oculta, na ordem, as que não cabem com o botão "Mais". */
  private layout(): void {
    const strip = this.host.parentElement;
    if (!strip) {
      return;
    }
    const tabsInStrip = [...strip.children].filter(
      (el): el is HTMLElement => el !== this.host && el instanceof HTMLElement && !!el.querySelector(':scope > .nav-link'),
    );
    tabsInStrip.forEach((li) => li.style.removeProperty('display'));
    // abas ocultas pela própria tela (ex.: classes responsivas) ficam fora da medição
    const items = tabsInStrip.filter((li) => getComputedStyle(li).display !== 'none');
    const available = strip.clientWidth;
    const gap = parseFloat(getComputedStyle(strip).columnGap) || 0;
    const widths = items.map((li) => li.getBoundingClientRect().width);
    const total = widths.reduce((sum, w) => sum + w, 0) + gap * Math.max(0, items.length - 1);
    // aba ativa pelo próprio ngbNav (a classe `active` do link só muda depois da detecção de mudanças)
    const activeDomId = this.nav().items.find((item) => item.active)?.domId;
    const activeIndex = items.findIndex((li) => li.querySelector(':scope > .nav-link')?.id === activeDomId);
    const fitting = (reserve: number): number => {
      const limit = available - reserve;
      let used = 0;
      let visible = 0;
      for (const w of widths) {
        const next = used + (visible ? gap : 0) + w;
        if (visible > 0 && next > limit) {
          break;
        }
        used = next;
        visible++;
      }
      return visible;
    };
    let overflow: HTMLElement[] = [];
    if (total > available + 0.5) {
      let visible = fitting(MORE_RESERVE);
      // aba aberta recolhida: o botão mostra o nome dela inteiro (mais contagem e seta), mesmo que outra aba vá para o menu
      if (activeIndex >= visible) {
        visible = fitting(Math.max(MORE_RESERVE, widths[activeIndex] + ACTIVE_EXTRA));
      }
      overflow = items.slice(visible);
      overflow.forEach((li) => (li.style.display = 'none'));
    }
    const tabs = overflow.map((li) => {
      const link = li.querySelector<HTMLElement>(':scope > .nav-link')!;
      return {
        domId: link.id,
        label: (link.textContent ?? '').replace(/\s+/g, ' ').trim(),
        active: link.id === activeDomId,
        disabled: link.classList.contains('disabled'),
      };
    });
    this.hidden.set(tabs);
  }
}
