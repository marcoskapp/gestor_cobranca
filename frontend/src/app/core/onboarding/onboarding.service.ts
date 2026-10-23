import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { NAVIGATION } from '../layout/navigation';
import { UiStateStore } from '../preferences/ui-state.store';
import { CHECKLISTS } from './content/checklists';
import { LATEST_RELEASE, RELEASE_NOTES_SCREEN } from './content/release-notes';
import { findScreen } from './content/screens';
import { findTour } from './content/tours';
import { ChecklistItem, ChecklistProfile, ScreenGuide, TourDefinition } from './onboarding.models';
import { TourEngine } from './tour/tour-engine.service';

export const WELCOME_TOUR = 'app.welcome';

/** Convite não intrusivo exibido no canto da tela. */
export type OnboardingHint =
  | { kind: 'welcome'; tour: TourDefinition }
  | { kind: 'tour'; tour: TourDefinition; screen: ScreenGuide }
  | { kind: 'changes'; screen: ScreenGuide; items: readonly string[]; tour: TourDefinition | null };

export interface ChecklistEntry {
  item: ChecklistItem;
  done: boolean;
}

export interface ChecklistSummary {
  profile: ChecklistProfile;
  entries: ChecklistEntry[];
  done: number;
  total: number;
}

/**
 * Orquestra o onboarding a partir da navegação:
 *
 * - primeiro acesso → convite para o tour de boas-vindas;
 * - tela ainda não navegada com tour → convite para um tour curto;
 * - tela com versão maior que a vista → destaque das novidades daquela tela;
 * - checklist de primeiros passos conforme o perfil e a central de novidades.
 *
 * Nada abre sozinho por cima do trabalho: convites são cartões dispensáveis e cada um aparece
 * no máximo uma vez. O usuário pode desligar as dicas automáticas a qualquer momento.
 */
@Injectable({ providedIn: 'root' })
export class OnboardingService {
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly store = inject(UiStateStore);
  private readonly engine = inject(TourEngine);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  private readonly currentHint = signal<OnboardingHint | null>(null);
  /** Tours já oferecidos nesta sessão do navegador (evita repetir enquanto o servidor confirma). */
  private readonly offered = new Set<string>();

  readonly hint = this.currentHint.asReadonly();
  readonly screen = computed(() => findScreen(this.url()));
  readonly hintsEnabled = computed(() => this.store.state().hintsEnabled);

  /** Tour da tela atual (na tela inicial, o de boas-vindas). */
  readonly screenTour = computed<TourDefinition | null>(() => {
    const screen = this.screen();
    if (screen?.tourId) {
      return findTour(screen.tourId) ?? null;
    }
    return screen?.key === 'app.home' ? (findTour(WELCOME_TOUR) ?? null) : null;
  });

  readonly unreadReleaseNotes = computed(
    () => LATEST_RELEASE > (this.store.state().screens[RELEASE_NOTES_SCREEN]?.version ?? 0),
  );

  readonly checklist = computed<ChecklistSummary | null>(() => {
    const user = this.auth.user();
    if (!user) {
      return null;
    }
    const state = this.store.state();
    const navRoutes = new Set(
      NAVIGATION.flatMap((s) => s.items)
        .filter((item) => this.auth.hasAnyPermission(item.permissions))
        .map((item) => item.route),
    );
    const profile = CHECKLISTS.find((p) => this.auth.hasAnyPermission(p.permissions)) ?? CHECKLISTS[CHECKLISTS.length - 1];
    const entries = profile.items
      .filter((item) => this.auth.hasAnyPermission(item.permissions))
      .filter((item) => !item.route || item.route === '/' || item.route.startsWith('/conta') || navRoutes.has(item.route))
      .map((item): ChecklistEntry => {
        const manual = !!state.checklist[item.id];
        const c = item.completion;
        const auto =
          (c.kind === 'mfa' && user.mfaEnabled) ||
          (c.kind === 'visit' && !!state.screens[c.screenKey]) ||
          (c.kind === 'tour' && state.tours[c.tourId]?.outcome === 'COMPLETED');
        return { item, done: manual || auto };
      });
    return { profile, entries, done: entries.filter((e) => e.done).length, total: entries.length };
  });

  readonly checklistVisible = computed(() => {
    const summary = this.checklist();
    return !!summary && summary.total > 0 && !this.store.state().checklistDismissed && summary.done < summary.total;
  });

  constructor() {
    effect(() => {
      const url = this.url();
      const ready = this.store.ready();
      untracked(() => this.evaluate(url, ready));
    });
  }

  // ------------------------------------------------------------------ ações do usuário

  /** Aceita o convite atual (inicia o tour correspondente). */
  acceptHint(): void {
    const hint = this.currentHint();
    this.currentHint.set(null);
    const tour = hint?.kind === 'changes' ? hint.tour : hint?.tour;
    if (tour) {
      void this.engine.start(tour);
    }
  }

  /** "Agora não": o convite não volta nesta versão do tour (continua disponível na ajuda). */
  declineHint(): void {
    const hint = this.currentHint();
    this.currentHint.set(null);
    if (hint && hint.kind !== 'changes') {
      this.store.recordTour(hint.tour.id, hint.tour.version, 'DISMISSED');
    }
  }

  closeHint(): void {
    this.currentHint.set(null);
  }

  setHintsEnabled(enabled: boolean): void {
    this.store.update({ hintsEnabled: enabled });
    if (!enabled) {
      this.currentHint.set(null);
    }
  }

  startScreenTour(): void {
    const tour = this.screenTour();
    if (tour) {
      this.currentHint.set(null);
      void this.engine.start(tour);
    }
  }

  startTour(tourId: string): void {
    this.currentHint.set(null);
    void this.engine.start(tourId);
  }

  /** Volta a oferecer todos os tours (ajuda → "Rever tours"). */
  resetTours(): void {
    this.offered.clear();
    this.store.update({ resetTours: true, hintsEnabled: true });
  }

  markReleaseNotesRead(): void {
    this.store.markScreenSeen(RELEASE_NOTES_SCREEN, LATEST_RELEASE);
  }

  setChecklistItem(itemId: string, done: boolean): void {
    this.store.setChecklistItem(itemId, done);
  }

  setChecklistDismissed(dismissed: boolean): void {
    this.store.update({ checklistDismissed: dismissed });
  }

  // ------------------------------------------------------------------ decisão dos convites

  private evaluate(url: string, ready: boolean): void {
    this.currentHint.set(null);
    if (!ready || !this.auth.user()) {
      return;
    }
    const state = this.store.state();
    const screen = findScreen(url);
    const firstAccess = Object.keys(state.screens).length === 0;
    if (screen) {
      this.store.markScreenSeen(screen.key, screen.version);
    }
    if (firstAccess) {
      // quem acabou de chegar recebe o tour de boas-vindas, não o histórico de versões
      this.store.markScreenSeen(RELEASE_NOTES_SCREEN, LATEST_RELEASE);
    }
    if (!state.hintsEnabled || this.engine.active()) {
      return;
    }

    const welcome = findTour(WELCOME_TOUR);
    if (welcome && this.shouldOffer(welcome)) {
      this.offer({ kind: 'welcome', tour: welcome });
      return;
    }
    if (!screen) {
      return;
    }
    const seen = state.screens[screen.key]?.version ?? 0;
    const tour = screen.tourId ? (findTour(screen.tourId) ?? null) : null;
    if (seen === 0) {
      if (tour && this.shouldOffer(tour)) {
        this.offer({ kind: 'tour', tour, screen });
      }
      return;
    }
    if (seen < screen.version) {
      const items = (screen.changes ?? []).filter((c) => c.version > seen).flatMap((c) => c.items);
      if (items.length > 0) {
        this.currentHint.set({ kind: 'changes', screen, items, tour: tour && this.shouldOffer(tour) ? tour : null });
      }
    }
  }

  private shouldOffer(tour: TourDefinition): boolean {
    const record = this.store.state().tours[tour.id];
    return !this.offered.has(tour.id) && (!record || record.version < tour.version);
  }

  private offer(hint: Extract<OnboardingHint, { tour: TourDefinition }>): void {
    this.offered.add(hint.tour.id);
    this.currentHint.set(hint);
  }
}
