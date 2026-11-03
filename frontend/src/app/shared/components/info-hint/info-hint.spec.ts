import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { InfoHint } from './info-hint';

@Component({
  imports: [InfoHint],
  template: `
    <p>Taxa de recuperação <app-info-hint indicator="analytics.recovery.rate" label="Taxa de recuperação" /></p>
    <app-info-hint indicator="analytics.nao.existe" label="Indicador sem texto" />
    <button type="button" id="outside">Fora</button>
  `,
})
class Host {}

/** Espera o carregamento sob demanda do catálogo e a renderização do balão. */
async function settle(fixture: ComponentFixture<Host>): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    await fixture.whenStable();
  }
}

function pointer(type: string, pointerType = 'mouse'): Event {
  const event = new Event(type, { bubbles: type !== 'pointerenter' && type !== 'pointerleave', cancelable: true });
  Object.defineProperty(event, 'pointerType', { value: pointerType });
  return event;
}

describe('InfoHint (informação contextual de indicador)', () => {
  let fixture: ComponentFixture<Host>;
  let button: HTMLButtonElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    button = (fixture.nativeElement as HTMLElement).querySelector('[data-indicator="analytics.recovery.rate"]') as HTMLButtonElement;
  });

  afterEach(() => {
    vi.useRealTimers();
    fixture.destroy();
  });

  const popover = () => document.querySelector<HTMLElement>('.app-info-popover');

  it('botão com rótulo acessível, ícone decorativo, fechado e sem descrição', () => {
    expect(button.tagName).toBe('BUTTON');
    expect(button.getAttribute('type')).toBe('button');
    expect(button.getAttribute('aria-label')).toBe('Sobre: Taxa de recuperação');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.hasAttribute('aria-describedby')).toBe(false);
    expect(button.querySelector('app-icon')?.getAttribute('aria-hidden')).toBe('true');
    expect(popover()).toBeNull();
  });

  it('abre ao passar o mouse, com o texto do catálogo, e fecha ao tirar o mouse', async () => {
    vi.useFakeTimers();
    button.dispatchEvent(pointer('pointerenter'));
    vi.advanceTimersByTime(200);
    vi.useRealTimers();
    await settle(fixture);

    // o texto vem do catálogo carregado sob demanda (chunk do módulo)
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(popover()?.textContent).toContain('O que é');
    });
    const pop = popover();
    expect(pop).not.toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(button.getAttribute('aria-describedby')).toBe(pop!.id);
    expect(pop!.getAttribute('role')).toBe('tooltip');
    expect(pop!.textContent).toContain('Taxa de recuperação');
    expect(pop!.textContent).toContain('O que é');
    expect(pop!.textContent).toContain('Como é calculado');
    expect(pop!.textContent).toContain('Como usar');
    const more = pop!.querySelector('a');
    expect(more?.textContent).toContain('Saiba mais no manual');
    expect(more?.getAttribute('href')).toBe('/ajuda/paineis#analytics.recovery');

    vi.useFakeTimers();
    button.dispatchEvent(pointer('pointerleave'));
    vi.advanceTimersByTime(400);
    vi.useRealTimers();
    await settle(fixture);
    expect(popover()).toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.hasAttribute('aria-describedby')).toBe(false);
  });

  it('o mouse pode ir do botão para o balão sem fechá-lo (conteúdo alcançável)', async () => {
    vi.useFakeTimers();
    button.dispatchEvent(pointer('pointerenter'));
    vi.advanceTimersByTime(200);
    vi.useRealTimers();
    await settle(fixture);

    vi.useFakeTimers();
    button.dispatchEvent(pointer('pointerleave'));
    popover()!.querySelector('.info-hint__body')!.dispatchEvent(pointer('pointerenter'));
    vi.advanceTimersByTime(400);
    vi.useRealTimers();
    await settle(fixture);
    expect(popover()).not.toBeNull();
  });

  it('abre ao receber o foco do teclado e fecha com Esc, sem reabrir', async () => {
    button.focus();
    await settle(fixture);
    expect(popover()).not.toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('true');

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle(fixture);
    expect(popover()).toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('false');
  });

  it('Enter fixa o balão e leva o foco para dentro; Esc fecha e devolve o foco ao botão', async () => {
    button.focus();
    await settle(fixture);
    button.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 }));
    await settle(fixture);
    await new Promise((resolve) => setTimeout(resolve, 10));
    const body = popover()!.querySelector<HTMLElement>('.info-hint__body')!;
    expect(document.activeElement).toBe(body);

    body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await settle(fixture);
    expect(popover()).toBeNull();
    expect(document.activeElement).toBe(button);
    expect(button.getAttribute('aria-expanded')).toBe('false');
  });

  it('Tab no fim do balão fecha e devolve o foco ao botão', async () => {
    button.focus();
    await settle(fixture);
    button.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 }));
    await settle(fixture);
    const link = popover()!.querySelector<HTMLAnchorElement>('a')!;
    link.focus();
    link.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
    await settle(fixture);
    expect(popover()).toBeNull();
    expect(document.activeElement).toBe(button);
  });

  it('no celular, o toque abre e fixa; um segundo toque fecha (nada depende de hover)', async () => {
    button.dispatchEvent(pointer('pointerenter', 'touch'));
    button.dispatchEvent(pointer('pointerdown', 'touch'));
    button.focus();
    await settle(fixture);
    expect(popover(), 'o foco do toque não abre sozinho').toBeNull();

    button.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }));
    await settle(fixture);
    expect(popover()).not.toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('true');

    button.dispatchEvent(pointer('pointerleave', 'touch'));
    await settle(fixture);
    expect(popover(), 'fixado pelo toque, não fecha ao "sair"').not.toBeNull();

    button.dispatchEvent(pointer('pointerdown', 'touch'));
    button.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }));
    await settle(fixture);
    expect(popover()).toBeNull();
  });

  it('um toque ou clique fora fecha o balão fixado', async () => {
    button.dispatchEvent(pointer('pointerdown'));
    button.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }));
    await settle(fixture);
    expect(popover()).not.toBeNull();

    (fixture.nativeElement as HTMLElement).querySelector('#outside')!.dispatchEvent(pointer('pointerdown'));
    await settle(fixture);
    expect(popover()).toBeNull();
  });

  it('indicador fora do catálogo mostra aviso em vez de quebrar', async () => {
    const other = (fixture.nativeElement as HTMLElement).querySelector('[data-indicator="analytics.nao.existe"]') as HTMLButtonElement;
    other.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(popover()!.textContent).toContain('Explicação ainda não disponível');
    });
    expect(popover()!.querySelector('a')).toBeNull();
  });
});
