import { Component, DOCUMENT, Injector, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { BreadcrumbService } from '../../core/layout/breadcrumbs/breadcrumbs';
import { findModuleInfo } from '../../core/onboarding/manual/manual-index';
import { GlossaryTerm, ManualModuleId, ModuleManual } from '../../core/onboarding/manual/manual.models';
import { ManualService, ScreenManualView } from '../../core/onboarding/manual/manual.service';
import { ScreenManualBody } from '../../core/onboarding/manual/screen-manual-body';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Skeleton } from '../../shared/components/skeleton/skeleton';

interface LoadedModule {
  manual: ModuleManual;
  screens: ScreenManualView[];
  terms: GlossaryTerm[];
}

type PageState = { kind: 'loading' } | { kind: 'error' } | { kind: 'not-found' } | { kind: 'ready'; data: LoadedModule };

/**
 * Manual aprofundado de um módulo (`/ajuda/:modulo`): visão geral, encaixe no sistema, regras,
 * o manual rápido de cada tela (âncora = chave da tela, alvo do "Saiba mais") e o glossário.
 */
@Component({
  selector: 'app-module-manual',
  imports: [RouterLink, PageHeader, Skeleton, EmptyState, ScreenManualBody],
  template: `
    <app-page-header [title]="title()" [subtitle]="summary()">
      <a class="btn btn-outline-secondary" routerLink="/ajuda">Todos os módulos</a>
    </app-page-header>

    @switch (state().kind) {
      @case ('loading') {
        <div class="app-card"><app-skeleton variant="lines" [rows]="8" /></div>
      }
      @case ('error') {
        <div class="app-card">
          <app-empty-state icon="exclamation-triangle" tone="error" title="Não foi possível carregar o manual" message="Verifique a conexão e tente de novo.">
            <button type="button" class="btn btn-outline-primary" (click)="reload()">Tentar novamente</button>
          </app-empty-state>
        </div>
      }
      @case ('not-found') {
        <div class="app-card">
          <app-empty-state icon="journal-text" title="Módulo não encontrado" message="Escolha um módulo na lista do manual do sistema.">
            <a class="btn btn-primary" routerLink="/ajuda">Manual do sistema</a>
          </app-empty-state>
        </div>
      }
      @case ('ready') {
        @if (data(); as d) {
          <div class="module-manual">
            <nav class="module-manual__toc app-card" aria-labelledby="manual-toc">
              <h2 class="app-card__title" id="manual-toc">Telas do módulo</h2>
              <ul class="module-manual__toc-list">
                @for (s of d.screens; track s.manual.id) {
                  <li><a [routerLink]="[]" [fragment]="s.manual.id">{{ s.manual.title }}</a></li>
                }
              </ul>
            </nav>

            <div class="module-manual__content">
              <section class="app-card" aria-labelledby="manual-visao">
                <h2 class="app-card__title" id="manual-visao">Visão geral</h2>
                @for (p of d.manual.overview; track $index) {
                  <p class="module-manual__text">{{ p }}</p>
                }
              </section>

              <section class="app-card" aria-labelledby="manual-encaixe">
                <h2 class="app-card__title" id="manual-encaixe">Como se encaixa no sistema</h2>
                <ul class="module-manual__list">
                  @for (c of d.manual.connections; track c.module) {
                    <li>
                      <a [routerLink]="['/ajuda', c.module]">{{ moduleTitle(c.module) }}</a>
                      <span class="app-muted"> — {{ c.text }}</span>
                    </li>
                  }
                </ul>
              </section>

              <section class="app-card" aria-labelledby="manual-regras">
                <h2 class="app-card__title" id="manual-regras">Regras importantes</h2>
                <ul class="module-manual__list">
                  @for (r of d.manual.rules; track $index) {
                    <li>{{ r }}</li>
                  }
                </ul>
              </section>

              @for (s of d.screens; track s.manual.id) {
                <section class="app-card module-manual__screen" [id]="s.manual.id" tabindex="-1" [attr.aria-labelledby]="'tela-' + s.manual.id">
                  <h3 class="module-manual__screen-title" [id]="'tela-' + s.manual.id">{{ s.manual.title }}</h3>
                  <app-screen-manual-body [view]="s" [level]="4" />
                </section>
              }

              <section class="app-card" aria-labelledby="manual-termos">
                <h2 class="app-card__title" id="manual-termos">Glossário do módulo</h2>
                <dl class="module-manual__terms">
                  @for (t of d.terms; track t.id) {
                    <dt>{{ t.term }}</dt>
                    <dd>{{ t.definition }}</dd>
                  }
                </dl>
              </section>
            </div>
          </div>
        }
      }
    }
  `,
  styles: `
    .module-manual {
      display: grid;
      gap: var(--app-gap);
      align-items: start;
    }
    @media (min-width: 1100px) {
      .module-manual {
        grid-template-columns: 240px minmax(0, 1fr);
      }
      .module-manual__toc {
        position: sticky;
        top: var(--app-space-4);
      }
    }
    .module-manual__content > .app-card + .app-card {
      margin-top: var(--app-gap);
    }
    .module-manual__toc-list,
    .module-manual__list {
      margin: 0;
      padding-left: 20px;
      line-height: var(--app-line-normal);
    }
    .module-manual__toc-list li + li,
    .module-manual__list li + li {
      margin-top: var(--app-space-1);
    }
    .module-manual__text {
      max-width: 72ch;
      line-height: var(--app-line-normal);
    }
    .module-manual__screen {
      scroll-margin-top: var(--app-space-4);
    }
    .module-manual__screen:focus {
      outline: none;
    }
    .module-manual__screen:focus-visible {
      outline: 2px solid var(--bs-primary);
      outline-offset: 2px;
    }
    .module-manual__screen-title {
      margin: 0 0 var(--app-space-2);
      font-size: var(--app-text-lg);
      font-weight: 500;
    }
    .module-manual__terms dt {
      font-weight: 500;
    }
    .module-manual__terms dd {
      margin: 0 0 var(--app-space-2);
      color: var(--bs-secondary-color);
      line-height: var(--app-line-normal);
    }
  `,
})
export class ModuleManualPage {
  private readonly route = inject(ActivatedRoute);
  private readonly manual = inject(ManualService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  private readonly moduleId = toSignal(this.route.paramMap.pipe(map((p) => p.get('modulo') ?? '')), { initialValue: '' });
  private readonly fragment = toSignal(this.route.fragment, { initialValue: null });
  private readonly info = computed(() => findModuleInfo(this.moduleId()));
  protected readonly title = computed(() => this.info()?.title ?? 'Manual do módulo');
  protected readonly summary = computed(() => this.info()?.summary ?? '');
  protected readonly state = signal<PageState>({ kind: 'loading' });
  protected readonly data = computed(() => {
    const state = this.state();
    return state.kind === 'ready' ? state.data : null;
  });

  constructor() {
    effect(() => {
      const info = this.info();
      untracked(() => {
        this.breadcrumbs.setCurrentLabel(info?.title ?? null);
        void this.load(info?.id);
      });
    });
    effect(() => {
      const fragment = this.fragment();
      if (this.data() && fragment) {
        untracked(() => this.scrollTo(fragment));
      }
    });
  }

  protected reload(): void {
    void this.load(this.info()?.id);
  }

  protected moduleTitle(id: string): string {
    return findModuleInfo(id)?.title ?? id;
  }

  private async load(id: ManualModuleId | undefined): Promise<void> {
    if (!id) {
      this.state.set({ kind: 'not-found' });
      return;
    }
    this.state.set({ kind: 'loading' });
    try {
      const manual = await this.manual.module(id);
      const [screens, terms] = await Promise.all([
        Promise.all(manual.screens.map(async (s) => this.manual.screen(s.id))),
        this.manual.terms(manual.terms),
      ]);
      if (this.info()?.id !== id) {
        return;
      }
      this.state.set({ kind: 'ready', data: { manual, screens: screens.filter((s): s is ScreenManualView => !!s), terms } });
    } catch {
      this.state.set({ kind: 'error' });
    }
  }

  /** Leva ao manual rápido da tela (alvo do "Saiba mais") e move o foco para lá. */
  private scrollTo(fragment: string): void {
    afterNextRender(
      () => {
        const target = this.document.getElementById(fragment);
        if (target) {
          target.scrollIntoView({ block: 'start' });
          target.focus({ preventScroll: true });
        }
      },
      { injector: this.injector },
    );
  }
}
