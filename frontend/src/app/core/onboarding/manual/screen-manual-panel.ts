import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationStart, Router, RouterLink } from '@angular/router';
import { NgbActiveOffcanvas } from '@ng-bootstrap/ng-bootstrap';
import { filter } from 'rxjs';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { OnboardingService } from '../onboarding.service';
import { findModuleInfo, manualLink } from './manual-index';
import { ManualService, ScreenManualView } from './manual.service';
import { ScreenManualBody } from './screen-manual-body';

export const MANUAL_PANEL_TITLE_ID = 'manual-panel-title';

type PanelState = { kind: 'loading' } | { kind: 'error' } | { kind: 'missing' } | { kind: 'ready'; view: ScreenManualView };

/**
 * "Sobre esta tela": manual rápido da tela atual num painel lateral (`NgbOffcanvas` à direita; foco
 * preso, Esc fecha e devolve o foco ao botão de ajuda). Fecha ao navegar.
 */
@Component({
  selector: 'app-screen-manual-panel',
  imports: [RouterLink, Icon, Skeleton, EmptyState, ScreenManualBody],
  template: `
    <div class="offcanvas-header">
      <div>
        <p class="manual-panel__eyebrow">Sobre esta tela</p>
        <h2 class="offcanvas-title h5" [id]="titleId">{{ title() }}</h2>
      </div>
      <button type="button" class="btn-close" aria-label="Fechar" (click)="offcanvas.dismiss()"></button>
    </div>
    <div class="offcanvas-body">
      @switch (state().kind) {
        @case ('loading') {
          <app-skeleton variant="lines" [rows]="6" />
        }
        @case ('error') {
          <app-empty-state icon="exclamation-triangle" tone="error" title="Não foi possível carregar a ajuda" message="Verifique a conexão e tente de novo.">
            <button type="button" class="btn btn-outline-primary btn-sm" (click)="load()">Tentar novamente</button>
          </app-empty-state>
        }
        @case ('missing') {
          <app-empty-state icon="info-circle" title="Esta tela ainda não tem manual" message="Consulte o manual do sistema para ver todos os módulos." />
        }
        @case ('ready') {
          @if (view(); as v) {
            <app-screen-manual-body [view]="v" [level]="3" (navigate)="offcanvas.close()" />
          }
        }
      }
    </div>
    <div class="manual-panel__footer">
      @if (onboarding.hasScreenHelp()) {
        <button type="button" class="btn btn-outline-primary btn-sm" (click)="startTour()">
          <app-icon name="signpost" /> Fazer o tour desta tela
        </button>
      }
      @if (moreLink; as more) {
        <a class="btn btn-primary btn-sm" [routerLink]="more.path" [fragment]="more.fragment" (click)="offcanvas.close()">
          <app-icon name="journal-text" /> Saiba mais no manual: {{ more.module }}
        </a>
      } @else {
        <a class="btn btn-primary btn-sm" routerLink="/ajuda" (click)="offcanvas.close()">
          <app-icon name="journal-text" /> Manual do sistema
        </a>
      }
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
    }
    .manual-panel__eyebrow {
      margin: 0 0 2px;
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .offcanvas-body {
      flex: 1 1 auto;
    }
    .manual-panel__footer {
      display: flex;
      flex-wrap: wrap;
      gap: var(--app-space-2);
      justify-content: flex-end;
      padding: var(--app-space-3) var(--bs-offcanvas-padding-x);
      border-top: var(--bs-border-width) solid var(--bs-border-color);
    }
  `,
})
export class ScreenManualPanel {
  protected readonly offcanvas = inject(NgbActiveOffcanvas);
  protected readonly onboarding = inject(OnboardingService);
  private readonly manual = inject(ManualService);
  protected readonly titleId = MANUAL_PANEL_TITLE_ID;

  private readonly screenKey = this.onboarding.screen()?.key ?? null;
  protected readonly state = signal<PanelState>({ kind: 'loading' });
  protected readonly title = signal('Ajuda');

  /** "Saiba mais": manual do módulo, na seção desta tela. */
  protected readonly moreLink = moreLinkFor(this.screenKey);

  constructor() {
    inject(Router)
      .events.pipe(
        filter((e) => e instanceof NavigationStart),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe(() => this.offcanvas.dismiss('navigation'));
    void this.load();
  }

  protected view(): ScreenManualView | null {
    const state = this.state();
    return state.kind === 'ready' ? state.view : null;
  }

  protected async load(): Promise<void> {
    if (!this.screenKey) {
      this.state.set({ kind: 'missing' });
      return;
    }
    this.state.set({ kind: 'loading' });
    try {
      const view = await this.manual.screen(this.screenKey);
      if (view) {
        this.title.set(view.manual.title);
        this.state.set({ kind: 'ready', view });
      } else {
        this.state.set({ kind: 'missing' });
      }
    } catch {
      this.state.set({ kind: 'error' });
    }
  }

  protected startTour(): void {
    this.offcanvas.close();
    void this.onboarding.startScreenTour();
  }
}

function moreLinkFor(screenKey: string | null): { path: string; fragment: string; module: string } | null {
  const link = screenKey ? manualLink(screenKey) : null;
  const module = link ? findModuleInfo(link.path.split('/').pop() ?? '') : undefined;
  return link && module ? { ...link, module: module.title } : null;
}
