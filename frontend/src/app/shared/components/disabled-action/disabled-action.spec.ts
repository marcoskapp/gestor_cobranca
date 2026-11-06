import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DisabledAction } from './disabled-action';

@Component({
  imports: [DisabledAction],
  template: `
    <div (click)="clicks = clicks + 1">
      <app-disabled-action buttonClass="btn btn-danger" reason="Para executar é preciso ter a permissão X.">Executar</app-disabled-action>
    </div>
    <button type="button" id="outside">Fora</button>
  `,
})
class Host {
  clicks = 0;
}

function pointer(type: string, pointerType = 'mouse'): Event {
  const event = new Event(type, { bubbles: false, cancelable: true });
  Object.defineProperty(event, 'pointerType', { value: pointerType });
  return event;
}

describe('DisabledAction (ação desabilitada com explicação)', () => {
  let fixture: ComponentFixture<Host>;
  let button: HTMLButtonElement;
  const tooltip = () => document.querySelector<HTMLElement>('ngb-tooltip-window');
  /** O fechamento do balão tem transição: espera ele sair do documento. */
  const closed = () =>
    vi.waitFor(() => {
      fixture.detectChanges();
      expect(tooltip()).toBeNull();
    });

  beforeEach(() => {
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    button = (fixture.nativeElement as HTMLElement).querySelector('app-disabled-action button') as HTMLButtonElement;
  });

  afterEach(() => fixture.destroy());

  it('botão com as classes da ação, aria-disabled (focável) e a explicação como descrição', () => {
    expect(button.classList).toContain('btn');
    expect(button.classList).toContain('btn-danger');
    expect(button.disabled).toBe(false);
    expect(button.getAttribute('aria-disabled')).toBe('true');
    const described = document.getElementById(button.getAttribute('aria-describedby')!);
    expect(described?.textContent).toContain('Para executar é preciso ter a permissão X.');
    expect(tooltip()).toBeNull();
  });

  it('clique ou toque mostra a explicação e não chega a quem está fora (nenhuma ação)', () => {
    button.click();
    fixture.detectChanges();
    expect(tooltip()?.textContent).toContain('Para executar é preciso ter a permissão X.');
    expect(fixture.componentInstance.clicks).toBe(0);
  });

  it('abre ao passar o mouse e fecha ao tirar; o toque não depende de hover', async () => {
    const wrap = button.parentElement!;
    wrap.dispatchEvent(pointer('pointerenter'));
    fixture.detectChanges();
    expect(tooltip()).not.toBeNull();
    wrap.dispatchEvent(pointer('pointerleave'));
    fixture.detectChanges();
    await closed();

    wrap.dispatchEvent(pointer('pointerenter', 'touch'));
    fixture.detectChanges();
    expect(tooltip(), 'o toque abre pelo clique, não pelo pointerenter').toBeNull();
  });

  it('o ponteiro pode ir do botão para o balão sem fechá-lo (WCAG 1.4.13); ao sair dos dois, fecha', async () => {
    const wrap = button.parentElement!;
    wrap.dispatchEvent(pointer('pointerenter'));
    fixture.detectChanges();
    wrap.dispatchEvent(pointer('pointerleave'));
    const balloon = tooltip()!.querySelector<HTMLElement>('.app-disabled-action__balloon')!;
    expect(balloon.textContent).toContain('Para executar é preciso ter a permissão X.');
    balloon.dispatchEvent(pointer('pointerenter'));
    await new Promise((resolve) => setTimeout(resolve, 400));
    fixture.detectChanges();
    expect(tooltip(), 'continua aberto com o ponteiro no balão').not.toBeNull();

    balloon.dispatchEvent(pointer('pointerleave'));
    await closed();
  });

  it('abre ao receber o foco do teclado e fecha com Esc', async () => {
    button.focus();
    fixture.detectChanges();
    expect(tooltip()).not.toBeNull();
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    await closed();
  });
});
