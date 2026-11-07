import { TestBed } from '@angular/core/testing';
import { MoreAction, MoreActions } from './more-actions';

describe('MoreActions', () => {
  function render(actions: MoreAction[]) {
    const fixture = TestBed.createComponent(MoreActions);
    fixture.componentRef.setInput('actions', actions);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('sem ações não mostra o menu', () => {
    expect(render([]).querySelector('button')).toBeNull();
  });

  it('a destrutiva vai por último, separada e em vermelho; executa a ação escolhida', () => {
    const calls: string[] = [];
    const el = render([
      { id: 'cancel', label: 'Cancelar', danger: true, run: () => calls.push('cancel') },
      { id: 'suspend', label: 'Suspender', run: () => calls.push('suspend') },
      { id: 'busy', label: 'Transferir', disabled: true, run: () => calls.push('busy') },
    ]);
    const toggle = el.querySelector<HTMLButtonElement>('.dropdown-toggle')!;
    expect(toggle.getAttribute('aria-label')).toBe('Mais ações');
    expect(toggle.classList).toContain('btn-outline-secondary');

    const items = [...el.querySelectorAll<HTMLButtonElement>('.dropdown-item')];
    expect(items.map((b) => b.textContent!.trim())).toEqual(['Suspender', 'Transferir', 'Cancelar']);
    expect(el.querySelector('.dropdown-divider')).not.toBeNull();
    expect(items[2].classList).toContain('text-danger-emphasis');
    expect(items[1].disabled).toBe(true);

    items[0].click();
    items[2].click();
    expect(calls).toEqual(['suspend', 'cancel']);
  });

  it('ao escolher um item, fecha o menu e devolve o foco ao botão antes de executar a ação', () => {
    let focusedOnRun: Element | null = null;
    const fixture = TestBed.createComponent(MoreActions);
    fixture.componentRef.setInput('actions', [{ id: 'suspend', label: 'Suspender', run: () => (focusedOnRun = document.activeElement) }]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    document.body.appendChild(el);
    const toggle = el.querySelector<HTMLButtonElement>('.dropdown-toggle')!;
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    el.querySelector<HTMLButtonElement>('.dropdown-item')!.click();
    fixture.detectChanges();
    expect(focusedOnRun).toBe(toggle);
    expect(document.activeElement).toBe(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    el.remove();
  });
});
