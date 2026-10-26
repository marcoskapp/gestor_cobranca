import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { UiStateStore } from '../../preferences/ui-state.store';
import { TourDefinition } from '../onboarding.models';
import { TourEngine } from './tour-engine.service';

@Component({
  template: `
    <button id="origem">origem</button>
    <div data-tour="a">A</div>
    <div data-tour="b" hidden>B (oculto)</div>
    <div data-tour="c">C</div>
  `,
})
class Host {}

const TOUR: TourDefinition = {
  id: 'test.tour',
  version: 2,
  title: 'Tour de teste',
  summary: 'Resumo',
  steps: [
    { title: 'Centralizado', body: 'Sem âncora' },
    { anchor: 'a', title: 'Passo A', body: 'Corpo A' },
    { anchor: 'b', title: 'Passo B', body: 'Oculto, deve ser pulado' },
    { anchor: 'inexistente', title: 'Fantasma', body: 'Não existe' },
    { anchor: 'c', title: 'Passo C', body: 'Corpo C', placement: 'above' },
  ],
};

function title(): string | null {
  return document.querySelector('.tour__title')?.textContent?.trim() ?? null;
}

function progress(): string {
  return document.querySelector('.tour__progress')?.textContent?.trim() ?? '';
}

function press(key: string): void {
  document.body.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
}

describe('TourEngine', () => {
  let engine: TourEngine;
  let recordTour: ReturnType<typeof vi.fn>;

  beforeAll(() => {
    // jsdom não calcula layout: visibilidade pelo atributo hidden
    Object.defineProperty(HTMLElement.prototype, 'checkVisibility', {
      configurable: true,
      value(this: HTMLElement) {
        return !this.closest('[hidden]');
      },
    });
    Element.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(async () => {
    vi.useFakeTimers();
    recordTour = vi.fn();
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideRouter([{ path: 'outra', component: Host }]), { provide: UiStateStore, useValue: { recordTour } }],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    engine = TestBed.inject(TourEngine);
  });

  afterEach(() => {
    engine.stop();
    vi.useRealTimers();
  });

  async function start(tour: TourDefinition = TOUR): Promise<boolean> {
    const started = engine.start(tour);
    await vi.advanceTimersByTimeAsync(1500);
    return started;
  }

  it('omite passos sem âncora visível e conta só os disponíveis', async () => {
    expect(await start()).toBe(true);
    expect(title()).toBe('Centralizado');
    expect(progress()).toContain('passo 1 de 3');

    engine.next();
    expect(title()).toBe('Passo A');
    engine.next();
    expect(title()).toBe('Passo C');
    expect(engine.view()?.isLast).toBe(true);
    expect(document.querySelector('.tour__actions button:last-child')?.textContent?.trim()).toBe('Concluir');
  });

  it('volta ao passo anterior e não passa do primeiro', async () => {
    await start();
    engine.next();
    engine.previous();
    expect(title()).toBe('Centralizado');
    engine.previous();
    expect(engine.view()?.index).toBe(0);
  });

  it('concluir registra o tour como concluído na versão atual e devolve o foco', async () => {
    const origin = document.getElementById('origem') as HTMLButtonElement;
    origin.focus();
    await start();
    engine.next();
    engine.next();
    engine.next();
    expect(recordTour).toHaveBeenCalledWith('test.tour', 2, 'COMPLETED');
    expect(engine.active()).toBe(false);
    expect(document.querySelector('.tour')).toBeNull();
    expect(document.activeElement).toBe(origin);
  });

  it('Esc dispensa o tour (registrado como dispensado)', async () => {
    await start();
    press('Escape');
    expect(recordTour).toHaveBeenCalledWith('test.tour', 2, 'DISMISSED');
    expect(engine.active()).toBe(false);
  });

  it('setas do teclado navegam quando o foco está no balão', async () => {
    await start();
    await vi.advanceTimersByTimeAsync(10);
    const panel = document.querySelector('.tour') as HTMLElement;
    expect(document.activeElement).toBe(panel);
    panel.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    expect(title()).toBe('Passo A');
    panel.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    expect(title()).toBe('Centralizado');
  });

  it('o balão é um diálogo não modal acessível', async () => {
    await start();
    const panel = document.querySelector('.tour') as HTMLElement;
    expect(panel.getAttribute('role')).toBe('dialog');
    expect(panel.getAttribute('aria-modal')).toBe('false');
    const labelledBy = panel.getAttribute('aria-labelledby') as string;
    expect(document.getElementById(labelledBy)?.textContent).toContain('Centralizado');
  });

  it('não inicia se nenhum passo estiver disponível', async () => {
    const ghost: TourDefinition = { ...TOUR, steps: [{ anchor: 'inexistente', title: 'x', body: 'y' }] };
    expect(await start(ghost)).toBe(false);
    expect(engine.active()).toBe(false);
  });

  it('tour transitório (tela sem tour próprio) não grava o desfecho no servidor', async () => {
    await start({ ...TOUR, id: 'screen.test', transient: true });
    press('Escape');
    expect(engine.active()).toBe(false);
    expect(recordTour).not.toHaveBeenCalled();
  });

  it('navegar para outra tela encerra sem registrar desfecho', async () => {
    await start();
    await TestBed.inject(Router).navigateByUrl('/outra');
    expect(engine.active()).toBe(false);
    expect(recordTour).not.toHaveBeenCalled();
  });
});
