import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../auth/auth.service';
import { applyCachedAppearance, ThemeService } from './theme.service';
import { UiStateApi } from './ui-state.api';

describe('ThemeService', () => {
  let systemDark: boolean;
  let listeners: ((e: MediaQueryListEvent) => void)[];

  const original = Object.getOwnPropertyDescriptor(window, 'matchMedia');

  afterEach(() => {
    if (original) {
      Object.defineProperty(window, 'matchMedia', original);
    } else {
      delete (window as { matchMedia?: unknown }).matchMedia;
    }
  });

  beforeEach(() => {
    localStorage.clear();
    systemDark = false;
    listeners = [];
    // jsdom não implementa matchMedia
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: (query: string) =>
        ({
          matches: query.includes('dark') && systemDark,
          media: query,
          addEventListener: (_: string, l: (e: MediaQueryListEvent) => void) => listeners.push(l),
          removeEventListener: () => undefined,
          addListener: () => undefined,
          removeListener: () => undefined,
        }) as unknown as MediaQueryList,
    });
    document.documentElement.removeAttribute('data-theme');
    document.documentElement.removeAttribute('data-density');
    document.documentElement.removeAttribute('data-bs-theme');
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { user: signal(null) } },
        { provide: UiStateApi, useValue: {} },
      ],
    });
  });


  function create(): ThemeService {
    const service = TestBed.inject(ThemeService);
    TestBed.tick();
    return service;
  }

  it('segue o sistema por padrão, sem fixar data-theme', () => {
    systemDark = true;
    const service = create();
    expect(service.theme()).toBe('SYSTEM');
    expect(service.effectiveTheme()).toBe('dark');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark');
    expect(document.documentElement.getAttribute('data-density')).toBe('comfortable');
  });

  it('reage à mudança de preferência do sistema operacional', () => {
    const service = create();
    expect(service.effectiveTheme()).toBe('light');
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('light');
    listeners.forEach((l) => l({ matches: true } as MediaQueryListEvent));
    expect(service.effectiveTheme()).toBe('dark');
    TestBed.tick();
    // o modo de cor do Bootstrap acompanha o sistema sem fixar a preferência
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });

  it('aplica tema e densidade escolhidos no <html>', () => {
    const service = create();
    service.setTheme('DARK');
    service.setDensity('COMPACT');
    TestBed.tick();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark');
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
  });

  it('tema claro fixado prevalece sobre o sistema escuro', () => {
    systemDark = true;
    const service = create();
    service.setTheme('LIGHT');
    TestBed.tick();
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('light');
  });

  it('alternância rápida parte do tema efetivo', () => {
    systemDark = true;
    const service = create();
    service.toggleTheme();
    expect(service.theme()).toBe('LIGHT');
    service.toggleTheme();
    expect(service.theme()).toBe('DARK');
  });

  it('aplica a aparência guardada antes do bootstrap', () => {
    localStorage.setItem('gc.ui.appearance', JSON.stringify({ theme: 'DARK', density: 'COMPACT' }));
    applyCachedAppearance(document);
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark');
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
  });

  it('ignora valores inválidos no armazenamento local', () => {
    localStorage.setItem('gc.ui.appearance', '{"theme":"NEON"');
    applyCachedAppearance(document);
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });
});
