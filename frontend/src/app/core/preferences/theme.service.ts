import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { DensityPreference, ThemePreference } from './ui-state.models';
import { UiStateStore, readCachedAppearance } from './ui-state.store';

export type EffectiveTheme = 'light' | 'dark';

const DARK_QUERY = '(prefers-color-scheme: dark)';

/**
 * Aplica tema e densidade ao documento. As cores vêm de `light-dark()` nos tokens (styles.scss),
 * portanto basta ajustar `data-theme` (ausente = segue o sistema) e `data-density` no `<html>`.
 * Usada também antes do bootstrap (`applyAppearance`) para evitar o "piscar" de tema.
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
      const listener = (event: MediaQueryListEvent) => this.systemDark.set(event.matches);
      media.addEventListener('change', listener);
      inject(DestroyRef).onDestroy(() => media.removeEventListener('change', listener));
    }
    effect(() => applyAppearance(this.document, this.theme(), this.density()));
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
export function applyAppearance(doc: Document, theme: ThemePreference, density: DensityPreference): void {
  const root = doc.documentElement;
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

/** Aplica a aparência guardada no navegador antes do bootstrap (sem esperar o login). */
export function applyCachedAppearance(doc: Document = document): void {
  const cached = readCachedAppearance();
  applyAppearance(doc, cached?.theme ?? 'SYSTEM', cached?.density ?? 'COMFORTABLE');
}
