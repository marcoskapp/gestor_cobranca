import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';

/**
 * Atalho de teclado. `keys` usa a sintaxe `mod+k`, `alt+shift+n`, `?`:
 * `mod` é Ctrl (Windows/Linux) ou ⌘ (macOS).
 */
export interface ShortcutDefinition {
  id: string;
  keys: string;
  description: string;
  /** Agrupamento na ajuda de atalhos (ex.: "Geral", "Estação de cobrança"). */
  group: string;
  handler: (event: KeyboardEvent) => void;
  /** Dispara mesmo com o foco em campo de texto (use só com modificador, ex.: `mod+k`). */
  allowInInputs?: boolean;
}

interface ParsedKeys {
  key: string;
  mod: boolean;
  alt: boolean;
  shift: boolean;
}

export function isMac(nav: Navigator | undefined = globalThis.navigator): boolean {
  return /mac|iphone|ipad/i.test(nav?.platform ?? nav?.userAgent ?? '');
}

export function parseKeys(keys: string): ParsedKeys {
  const parts = keys.toLowerCase().split('+');
  const key = parts[parts.length - 1] ?? '';
  return { key, mod: parts.includes('mod'), alt: parts.includes('alt'), shift: parts.includes('shift') };
}

/** Texto exibido para o atalho (`mod+k` → `Ctrl + K` ou `⌘ + K`). */
export function formatKeys(keys: string, mac = isMac()): string[] {
  return keys.split('+').map((part) => {
    switch (part.toLowerCase()) {
      case 'mod':
        return mac ? '⌘' : 'Ctrl';
      case 'alt':
        return mac ? '⌥' : 'Alt';
      case 'shift':
        return 'Shift';
      default:
        return part.length === 1 ? part.toUpperCase() : part;
    }
  });
}

export function matches(event: KeyboardEvent, parsed: ParsedKeys, mac = isMac()): boolean {
  const mod = mac ? event.metaKey : event.ctrlKey;
  const otherMod = mac ? event.ctrlKey : event.metaKey;
  if (mod !== parsed.mod || otherMod || event.altKey !== parsed.alt) {
    return false;
  }
  const key = event.key.toLowerCase();
  // símbolos como "?" já embutem o Shift; para letras o Shift precisa coincidir
  const symbol = key.length === 1 && !/[a-z0-9]/.test(key);
  if (!symbol && event.shiftKey !== parsed.shift) {
    return false;
  }
  return key === parsed.key;
}

function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  return target.isContentEditable || /^(input|textarea|select)$/i.test(target.tagName);
}

/**
 * Registro central de atalhos de teclado. Telas registram os próprios atalhos e os removem ao
 * sair (função devolvida por `register`); a ajuda (tecla ?) lista o que estiver ativo.
 * Atalhos não disparam com o foco em campos de texto, exceto os marcados `allowInInputs`.
 */
@Injectable({ providedIn: 'root' })
export class KeyboardShortcuts {
  private readonly registered = signal<readonly ShortcutDefinition[]>([]);
  readonly shortcuts = this.registered.asReadonly();

  constructor() {
    const doc = inject(DOCUMENT);
    const listener = (event: KeyboardEvent) => this.handle(event);
    doc.addEventListener('keydown', listener);
    inject(DestroyRef).onDestroy(() => doc.removeEventListener('keydown', listener));
  }

  register(shortcut: ShortcutDefinition): () => void {
    this.registered.update((list) => [...list.filter((s) => s.id !== shortcut.id), shortcut]);
    return () => this.registered.update((list) => list.filter((s) => s !== shortcut));
  }

  private handle(event: KeyboardEvent): void {
    if (event.defaultPrevented || event.repeat || event.isComposing) {
      return;
    }
    const editable = isEditable(event.target);
    for (const shortcut of this.registered()) {
      if ((!editable || shortcut.allowInInputs) && matches(event, parseKeys(shortcut.keys))) {
        event.preventDefault();
        shortcut.handler(event);
        return;
      }
    }
  }
}
