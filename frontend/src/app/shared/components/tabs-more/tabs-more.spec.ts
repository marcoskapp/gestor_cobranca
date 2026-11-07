import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { TabsMore } from './tabs-more';

@Component({
  imports: [NgbNavModule, TabsMore],
  template: `
    <ul ngbNav #tabs="ngbNav" class="nav-underline app-tabs" [activeId]="tab()" (activeIdChange)="tab.set($event)">
      @for (t of labels; track t) {
        <li [ngbNavItem]="t">
          <button ngbNavLink type="button">{{ t }}</button>
          <ng-template ngbNavContent>conteúdo {{ t }}</ng-template>
        </li>
      }
      <li [appTabsMore]="tabs"></li>
    </ul>
  `,
})
class Host {
  readonly labels = ['Títulos', 'Contatos', 'Contratos de origem', 'Razão', 'Restrições'];
  readonly tab = signal('Títulos');
}

describe('TabsMore (abas que não cabem em "Mais N ▾")', () => {
  let stripWidth = 1000;
  const TAB_WIDTH = 120;

  beforeEach(() => {
    // o jsdom não calcula layout: largura da faixa e de cada aba simuladas; quadro de animação imediato
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        disconnect(): void {}
      },
    );
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => {
      cb(0);
      return 0;
    });
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return this.matches('ul.app-tabs') ? stripWidth : 0;
    });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return { width: this.matches('li.nav-item') ? TAB_WIDTH : 0 } as DOMRect;
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  async function render(width: number): Promise<ComponentFixture<Host>> {
    stripWidth = width;
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const more = (f: ComponentFixture<Host>) => (f.nativeElement as HTMLElement).querySelector<HTMLElement>('li.app-tabs-more')!;
  const visibleTabs = (f: ComponentFixture<Host>) =>
    [...(f.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('ul > li.nav-item:not(.app-tabs-more)')]
      .filter((li) => li.style.display !== 'none')
      .map((li) => li.textContent!.trim());

  it('com espaço para todas, não mostra o botão "Mais"', async () => {
    const f = await render(1000);
    expect(visibleTabs(f)).toHaveLength(5);
    expect(more(f).classList).toContain('d-none');
  });

  it('sem espaço, recolhe as últimas em "Mais N ▾", com seta, contagem e dica', async () => {
    // 5 × 120 = 600 > 500; cabem as que somam até 500 - 112 = 388 (3 abas)
    const f = await render(500);
    expect(visibleTabs(f)).toEqual(['Títulos', 'Contatos', 'Contratos de origem']);
    const toggle = more(f).querySelector<HTMLElement>('.app-tabs-more__toggle')!;
    expect(more(f).classList).not.toContain('d-none');
    expect(toggle.querySelector('.app-tabs-more__label')!.textContent!.trim()).toBe('Mais');
    expect(toggle.querySelector('.app-tabs-more__count')!.textContent!.trim()).toBe('2');
    expect(toggle.querySelector('.app-tabs-more__chevron')).not.toBeNull();
    expect(toggle.getAttribute('aria-label')).toBe('Mais abas (2)');
  });

  it('escolher no menu abre a aba pelo próprio link; o botão passa a mostrar o nome dela e "+N"', async () => {
    const f = await render(500);
    const toggle = more(f).querySelector<HTMLButtonElement>('.app-tabs-more__toggle')!;
    toggle.click();
    f.detectChanges();
    const item = [...document.querySelectorAll<HTMLButtonElement>('.app-menu [ngbDropdownItem], .app-menu .dropdown-item')].find(
      (b) => b.textContent!.trim() === 'Restrições',
    )!;
    item.click();
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    expect(f.componentInstance.tab()).toBe('Restrições');
    expect(toggle.classList).toContain('active');
    expect(toggle.querySelector('.app-tabs-more__label')!.textContent!.trim()).toBe('Restrições');
    // para o nome da aba aberta caber inteiro, mais uma aba vai para o menu (500 - 120 - 56 = 324: cabem 2)
    expect(visibleTabs(f)).toEqual(['Títulos', 'Contatos']);
    expect(toggle.querySelector('.app-tabs-more__count')!.textContent!.trim()).toBe('+2');
    expect(toggle.getAttribute('aria-label')).toBe('Mais abas (aberta: Restrições)');
  });

  it('aba trocada sem clique (pela URL ou pelo [activeId]) também atualiza o botão', async () => {
    const f = await render(500);
    f.componentInstance.tab.set('Restrições');
    f.componentRef.changeDetectorRef.markForCheck();
    f.detectChanges(); // [activeId] muda; o `aria-selected` do link acompanha
    await new Promise((r) => setTimeout(r, 0)); // o MutationObserver entrega depois da microtarefa
    f.detectChanges();
    const toggle = more(f).querySelector<HTMLElement>('.app-tabs-more__toggle')!;
    expect(toggle.querySelector('.app-tabs-more__label')!.textContent!.trim()).toBe('Restrições');
    expect(toggle.classList).toContain('active');
  });

  it('setas no botão "Mais" ficam com o menu: não levam o foco para a primeira aba', async () => {
    const f = await render(500);
    const toggle = more(f).querySelector<HTMLButtonElement>('.app-tabs-more__toggle')!;
    toggle.focus();
    toggle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    f.detectChanges();
    expect(document.activeElement?.classList.contains('nav-link') && document.activeElement !== toggle).toBe(false);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(document.activeElement?.classList.contains('dropdown-item')).toBe(true);
    expect(more(f).getAttribute('role')).toBe('presentation');
  });

  it('← → com abas recolhidas percorrem só as visíveis e o botão "Mais"', async () => {
    const f = await render(500);
    const links = [...(f.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('ul > li:not(.app-tabs-more) > .nav-link')];
    const toggle = more(f).querySelector<HTMLElement>('.app-tabs-more__toggle')!;
    const press = (el: HTMLElement, key: string) => {
      el.focus();
      el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    };
    press(links[2], 'ArrowRight'); // última visível → botão "Mais"
    expect(document.activeElement).toBe(toggle);
    press(toggle, 'ArrowLeft'); // botão → última visível
    expect(document.activeElement).toBe(links[2]);
    press(links[0], 'ArrowLeft'); // primeira → botão (dá a volta, sem passar pelas recolhidas)
    expect(document.activeElement).toBe(toggle);
  });
});
