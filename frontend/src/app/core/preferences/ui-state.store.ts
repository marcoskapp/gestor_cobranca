import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { UiStateApi } from './ui-state.api';
import { DEFAULT_UI_STATE, TourOutcome, UiState, UiStatePatch } from './ui-state.models';
import { applyPatch, isEmptyPatch, mergePatches } from './ui-state.patch';

/** Situação da carga do estado do servidor. */
export type UiStateStatus = 'anonymous' | 'loading' | 'ready' | 'unavailable';

const FLUSH_DELAY_MS = 800;
const RETRY_DELAY_MS = 15_000;
const MAX_RETRIES = 3;
const APPEARANCE_KEY = 'gc.ui.appearance';

/** Preferência visual guardada no navegador (sem dado pessoal) para a tela de login e o carregamento inicial. */
export interface CachedAppearance {
  theme: UiState['theme'];
  density: UiState['density'];
}

export function readCachedAppearance(): CachedAppearance | null {
  try {
    const raw = globalThis.localStorage?.getItem(APPEARANCE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as Partial<CachedAppearance>;
    const theme = parsed.theme === 'LIGHT' || parsed.theme === 'DARK' ? parsed.theme : 'SYSTEM';
    const density = parsed.density === 'COMPACT' ? 'COMPACT' : 'COMFORTABLE';
    return { theme, density };
  } catch {
    return null;
  }
}

function writeCachedAppearance(appearance: CachedAppearance): void {
  try {
    globalThis.localStorage?.setItem(APPEARANCE_KEY, JSON.stringify(appearance));
  } catch {
    // armazenamento indisponível (modo privado, cota): a preferência continua no servidor
  }
}

/**
 * Estado de interface do usuário (tema, densidade, menu, onboarding), persistido no servidor
 * para acompanhar o usuário entre dispositivos.
 *
 * - Carregado a cada login; anônimo usa a aparência guardada no navegador.
 * - Alterações são otimistas e agrupadas (debounce) em um único PATCH; respostas do servidor
 *   são combinadas com o que ainda está pendente, sem perder cliques feitos no intervalo.
 * - Falhas não interrompem o usuário: o estado local continua valendo e o envio é repetido.
 */
@Injectable({ providedIn: 'root' })
export class UiStateStore {
  private readonly api = inject(UiStateApi);
  private readonly auth = inject(AuthService);

  private readonly current = signal<UiState>(this.initialState());
  private readonly loadStatus = signal<UiStateStatus>('anonymous');

  readonly state = this.current.asReadonly();
  readonly status = this.loadStatus.asReadonly();
  /** O estado do servidor já foi carregado: decisões de onboarding podem ser tomadas. */
  readonly ready = computed(() => this.loadStatus() === 'ready');

  private userId: string | null = null;
  private pending: UiStatePatch | null = null;
  private inFlight: Promise<void> | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private retries = 0;

  constructor() {
    effect(() => {
      const id = this.auth.user()?.id ?? null;
      untracked(() => this.onUserChanged(id));
    });
  }

  // ------------------------------------------------------------------ alterações

  update(patch: UiStatePatch): void {
    if (isEmptyPatch(patch)) {
      return;
    }
    this.current.update((state) => applyPatch(state, patch));
    this.cacheAppearance();
    if (!this.userId) {
      return;
    }
    this.pending = this.pending ? mergePatches(this.pending, patch) : patch;
    this.schedule(FLUSH_DELAY_MS);
  }

  recordTour(tourId: string, version: number, outcome: TourOutcome): void {
    this.update({ tours: [{ tourId, version, outcome }] });
  }

  markScreenSeen(screenKey: string, version: number): void {
    const seen = this.current().screens[screenKey]?.version ?? 0;
    if (seen < version) {
      this.update({ screens: [{ screenKey, version }] });
    }
  }

  setChecklistItem(itemId: string, done: boolean): void {
    this.update({ checklist: [{ itemId, done }] });
  }

  /** Envia imediatamente o que estiver pendente (ex.: antes do logout). */
  async flush(): Promise<void> {
    this.clearTimer();
    if (this.inFlight) {
      await this.inFlight;
    }
    if (!this.pending || !this.userId) {
      return;
    }
    const patch = this.pending;
    const owner = this.userId;
    this.pending = null;
    this.inFlight = new Promise<void>((resolve) => {
      this.api.update(patch).subscribe({
        next: (server) => {
          if (owner === this.userId) {
            this.retries = 0;
            // o que foi pedido depois do envio continua valendo sobre a resposta do servidor
            this.current.set(this.pending ? applyPatch(server, this.pending) : server);
            this.cacheAppearance();
          }
          resolve();
        },
        error: () => {
          if (owner === this.userId && this.retries < MAX_RETRIES) {
            this.retries++;
            this.pending = this.pending ? mergePatches(patch, this.pending) : patch;
            this.schedule(RETRY_DELAY_MS);
          }
          resolve();
        },
      });
    }).finally(() => {
      this.inFlight = null;
    });
    await this.inFlight;
    if (this.pending && !this.timer) {
      this.schedule(FLUSH_DELAY_MS);
    }
  }

  // ------------------------------------------------------------------ interno

  private onUserChanged(id: string | null): void {
    if (id === this.userId) {
      return;
    }
    this.clearTimer();
    this.pending = null;
    this.retries = 0;
    this.userId = id;
    if (!id) {
      this.current.set(this.initialState());
      this.loadStatus.set('anonymous');
      return;
    }
    this.loadStatus.set('loading');
    this.api.get().subscribe({
      next: (server) => {
        if (id !== this.userId) {
          return;
        }
        this.current.set(this.pending ? applyPatch(server, this.pending) : server);
        this.cacheAppearance();
        this.loadStatus.set('ready');
      },
      error: () => {
        if (id === this.userId) {
          this.loadStatus.set('unavailable');
        }
      },
    });
  }

  private initialState(): UiState {
    const cached = readCachedAppearance();
    return cached ? { ...DEFAULT_UI_STATE, ...cached } : { ...DEFAULT_UI_STATE };
  }

  private cacheAppearance(): void {
    const { theme, density } = this.current();
    writeCachedAppearance({ theme, density });
  }

  private schedule(delay: number): void {
    this.clearTimer();
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.flush();
    }, delay);
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
