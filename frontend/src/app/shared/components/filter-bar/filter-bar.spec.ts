import { BreakpointObserver, BreakpointState } from '@angular/cdk/layout';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NgbConfig } from '@ng-bootstrap/ng-bootstrap';
import { BehaviorSubject, map } from 'rxjs';
import { FILTER_BAR_COLLAPSED, FilterBar } from './filter-bar';

@Component({
  imports: [FilterBar],
  template: `
    <app-filter-bar [activeCount]="active()">
      <form class="app-filters"><input id="filtro-nome" aria-label="Nome" /></form>
    </app-filter-bar>
  `,
})
class Host {
  readonly active = signal(0);
}

describe('FilterBar', () => {
  const narrow = new BehaviorSubject(false);

  beforeEach(() => {
    narrow.next(false);
    TestBed.configureTestingModule({
      providers: [
        {
          provide: BreakpointObserver,
          useValue: {
            observe: (query: string) => narrow.pipe(map((m): BreakpointState => ({ matches: query === FILTER_BAR_COLLAPSED && m, breakpoints: {} }))),
            isMatched: () => narrow.value,
          },
        },
      ],
    });
    TestBed.inject(NgbConfig).animation = false;
  });

  function render() {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('telas largas: filtros na própria linha, sem botão', () => {
    const { el } = render();
    expect(el.querySelector('#filtro-nome')).not.toBeNull();
    expect(el.querySelector('.app-filter-bar__toggle')).toBeNull();
  });

  it('abaixo de 768 px: recolhe num botão "Filtros (n)" que abre o painel com os mesmos campos', async () => {
    narrow.next(true);
    const { fixture, el } = render();
    fixture.componentInstance.active.set(2);
    fixture.detectChanges();
    expect(el.querySelector('#filtro-nome')).toBeNull();
    const button = el.querySelector<HTMLButtonElement>('.app-filter-bar__toggle button')!;
    expect(button.textContent).toContain('Filtros');
    expect(button.querySelector('.badge')?.textContent).toBe('2');
    expect(button.getAttribute('aria-expanded')).toBe('false');

    button.click();
    fixture.detectChanges();
    expect(document.querySelector('.offcanvas.app-filter-panel #filtro-nome')).not.toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('true');

    // ao alargar a tela, o painel fecha e os filtros voltam para a linha
    narrow.next(false);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(document.querySelector('.offcanvas.app-filter-panel')).toBeNull();
    expect(el.querySelector('#filtro-nome')).not.toBeNull();
  });

  it('sem filtros aplicados, o botão não mostra contagem', () => {
    narrow.next(true);
    const { el } = render();
    expect(el.querySelector('.app-filter-bar__toggle .badge')).toBeNull();
  });
});
