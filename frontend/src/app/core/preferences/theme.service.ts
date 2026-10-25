import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { DensityPreference, ThemePreference } from './ui-state.models';
import { UiStateStore, readCachedAppearance } from './ui-state.store';

export type EffectiveTheme = 'light' | 'dark';

const DARK_QUERY = '(prefers-color-scheme: dark)';

/**
 * Aplica tema e densidade ao documento: `data-bs-theme` (modo de cor nativo do Bootstrap 5.3,
 * sempre o modo efetivo), `data-theme` (preferência fixada; ausente = segue o sistema, usado pelo
 * `color-scheme` e pelo tema Material transitório) e `data-density` no `<html>`.
 * Usada também antes do bootstrap (`applyCachedAppearance`) para evitar o "piscar" de tema.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly store = inject(UiStateStore);
  private readonly document = inject(DOCUMENT);
  private readonly systemDark = signal(false);

  readonly theme = computed<ThemePreference>(() => this.store.state().theme);
  readonly density = computed<DensityPreference>(() => this.store.state().density);
  readonly effectiveTheme = computed<EffectiveTheme>(() => {
    const theme = this.theme();
    return theme === 'SYSTEM' ? (this.systemDark() ? 'dark' : 'light') : theme === 'DARK' ? 'dark' : 'light';
  });

  constructor() {
    const media = this.document.defaultView?.matchMedia?.(DARK_QUERY);
    if (media) {
      this.systemDark.set(media.matches);
    }
    if (media && typeof media.addEventListener === 'function') {
      const listener = (event: MediaQueryListEvent) => this.systemDark.set(event.matches);
      media.addEventListener('change', listener);
      inject(DestroyRef).onDestroy(() => media.removeEventListener('change', listener));
    }
    effect(() => applyAppearance(this.document, this.theme(), this.density(), this.systemDark()));
  }

  setTheme(theme: ThemePreference): void {
    this.store.update({ theme });
  }

  setDensity(density: DensityPreference): void {
    this.store.update({ density });
  }

  /** Alterna entre claro e escuro a partir do tema efetivo (atalho rápido da barra superior). */
  toggleTheme(): void {
    this.setTheme(this.effectiveTheme() === 'dark' ? 'LIGHT' : 'DARK');
  }
}

/** Reflete tema e densidade nos atributos do `<html>` e na meta `color-scheme`. */
export function applyAppearance(
  doc: Document,
  theme: ThemePreference,
  density: DensityPreference,
  systemDark = systemPrefersDark(doc),
): void {
  const root = doc.documentElement;
  const dark = theme === 'DARK' || (theme === 'SYSTEM' && systemDark);
  root.setAttribute('data-bs-theme', dark ? 'dark' : 'light');
  if (theme === 'SYSTEM') {
    root.removeAttribute('data-theme');
  } else {
    root.setAttribute('data-theme', theme === 'DARK' ? 'dark' : 'light');
  }
  root.setAttribute('data-density', density === 'COMPACT' ? 'compact' : 'comfortable');
  doc
    .querySelector('meta[name="color-scheme"]')
    ?.setAttribute('content', theme === 'SYSTEM' ? 'light dark' : theme === 'DARK' ? 'dark' : 'light');
}

function systemPrefersDark(doc: Document): boolean {
  return doc.defaultView?.matchMedia?.(DARK_QUERY).matches ?? false;
}

/** Aplica a aparência guardada no navegador antes do bootstrap (sem esperar o login). */
export function applyCachedAppearance(doc: Document = document): void {
  const cached = readCachedAppearance();
  applyAppearance(doc, cached?.theme ?? 'SYSTEM', cached?.density ?? 'COMFORTABLE');
}
