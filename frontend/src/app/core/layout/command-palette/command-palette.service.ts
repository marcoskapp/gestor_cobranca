import { Injectable, inject, signal } from '@angular/core';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';

export interface PaletteCommand {
  id: string;
  label: string;
  /** Grupo exibido na lista (ex.: "Ir para", "Ações"). */
  group: string;
  icon: string;
  /** Texto auxiliar à direita (seção do menu ou atalho). */
  hint?: string;
  /** Termos adicionais de busca (sinônimos). */
  keywords?: readonly string[];
  run: () => void;
}

/**
 * Busca rápida / paleta de comandos (Ctrl + K). Destinos do menu entram automaticamente;
 * telas e o shell registram ações extras com `register` (removidas pela função devolvida).
 */
@Injectable({ providedIn: 'root' })
export class CommandPaletteService {
  private readonly dialog = inject(MatDialog);
  private readonly extra = signal<readonly PaletteCommand[]>([]);
  private ref: MatDialogRef<unknown> | null = null;

  readonly commands = this.extra.asReadonly();

  register(commands: readonly PaletteCommand[]): () => void {
    const ids = new Set(commands.map((c) => c.id));
    this.extra.update((list) => [...list.filter((c) => !ids.has(c.id)), ...commands]);
    return () => this.extra.update((list) => list.filter((c) => !commands.includes(c)));
  }

  async open(): Promise<void> {
    if (this.ref) {
      return;
    }
    const { CommandPalette } = await import('./command-palette');
    this.ref = this.dialog.open(CommandPalette, {
      panelClass: 'app-palette-pane',
      backdropClass: 'app-palette-backdrop',
      position: { top: '12vh' },
      width: '640px',
      maxWidth: 'calc(100vw - 32px)',
      autoFocus: 'first-tabbable',
      ariaLabel: 'Busca rápida',
    });
    this.ref.afterClosed().subscribe(() => (this.ref = null));
  }
}

/** Normaliza para busca sem acento e sem diferenciar maiúsculas. */
export function normalizeSearch(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim();
}

/** Todos os termos digitados precisam aparecer no rótulo, grupo, dica ou sinônimos. */
export function filterCommands(commands: readonly PaletteCommand[], query: string): PaletteCommand[] {
  const terms = normalizeSearch(query).split(/\s+/).filter(Boolean);
  if (terms.length === 0) {
    return [...commands];
  }
  return commands
    .map((command) => {
      const label = normalizeSearch(command.label);
      const haystack = normalizeSearch([command.label, command.hint ?? '', ...(command.keywords ?? [])].join(' '));
      const ok = terms.every((t) => haystack.includes(t));
      // rótulo que começa com o termo vem primeiro
      const score = !ok ? -1 : label.startsWith(terms[0]) ? 2 : label.includes(terms[0]) ? 1 : 0;
      return { command, score };
    })
    .filter((r) => r.score >= 0)
    .sort((a, b) => b.score - a.score)
    .map((r) => r.command);
}
