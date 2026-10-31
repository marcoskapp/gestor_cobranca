import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { BOTTOM_INSET_VAR, BottomBar, BottomInset } from './bottom-bar';

@Component({
  imports: [BottomBar],
  template: `
    @if (show()) {
      <div appBottomBar class="bar">ações</div>
    }
  `,
})
class Host {
  readonly show = signal(true);
}

const tick = () => new Promise((r) => setTimeout(r, 0));
const inset = () => document.documentElement.style.getPropertyValue(BOTTOM_INSET_VAR);

describe('BottomBar / BottomInset', () => {
  afterEach(() => document.documentElement.style.removeProperty(BOTTOM_INSET_VAR));

  it('publica a altura ocupada no rodapé enquanto a barra está na tela e limpa ao sair', async () => {
    const height = window.innerHeight;
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      top: height - 60, bottom: height - 10, height: 50, left: 0, right: 300, width: 300, x: 0, y: height - 60, toJSON: () => ({}),
    } as DOMRect);
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await tick();
    // 60 px cobertos + 8 px de folga
    expect(inset()).toBe('68px');

    fixture.componentInstance.show.set(false);
    fixture.detectChanges();
    await tick();
    expect(inset()).toBe('');
    rect.mockRestore();
  });

  it('barra fora da janela (rolada para longe) não desloca nada', () => {
    const service = TestBed.inject(BottomInset);
    const el = document.createElement('div');
    vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({ top: window.innerHeight + 200, bottom: window.innerHeight + 260, height: 60 } as DOMRect);
    service.register(el);
    expect(service.measure()).toBe(0);
    service.unregister(el);
  });
});
