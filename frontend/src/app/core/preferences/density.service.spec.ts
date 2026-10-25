import { TestBed } from '@angular/core/testing';
import { DensityService } from './density.service';

describe('DensityService', () => {
  const original = Object.getOwnPropertyDescriptor(window, 'matchMedia');
  let fine: boolean;

  beforeEach(() => {
    fine = true;
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: (query: string) =>
        ({ matches: query.includes('fine') && fine, addEventListener: () => undefined, removeEventListener: () => undefined }) as unknown as MediaQueryList,
    });
    document.documentElement.removeAttribute('data-density');
  });

  afterEach(() => {
    if (original) {
      Object.defineProperty(window, 'matchMedia', original);
    } else {
      delete (window as { matchMedia?: unknown }).matchMedia;
    }
    document.documentElement.removeAttribute('data-density');
  });

  it('acompanha o atributo data-density do <html>', async () => {
    const density = TestBed.inject(DensityService);
    expect(density.compact()).toBe(false);
    document.documentElement.setAttribute('data-density', 'compact');
    await new Promise((r) => setTimeout(r, 0)); // MutationObserver
    expect(density.compact()).toBe(true);
  });

  it('ignora a densidade compacta em telas de toque (WCAG 2.5.8)', () => {
    fine = false;
    document.documentElement.setAttribute('data-density', 'compact');
    expect(TestBed.inject(DensityService).compact()).toBe(false);
  });
});
