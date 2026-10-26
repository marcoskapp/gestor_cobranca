import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MANUAL_MODULES, findModuleInfo } from '../../core/onboarding/manual/manual-index';
import { GlossaryTerm, ModuleManual } from '../../core/onboarding/manual/manual.models';
import { ManualService } from '../../core/onboarding/manual/manual.service';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Skeleton } from '../../shared/components/skeleton/skeleton';

/**
 * Manual do sistema (`/ajuda`): como os módulos se encaixam (o caminho da dívida), o manual de
 * cada módulo e o glossário completo. Conteúdo carregado sob demanda.
 */
@Component({
  selector: 'app-help-center',
  imports: [RouterLink, PageHeader, Icon, Skeleton, EmptyState],
  template: `
    <app-page-header title="Manual do sistema" subtitle="Para que serve cada parte do Gestor de Cobrança, como elas se encaixam e os termos usados." />

    @if (error()) {
      <div class="app-card">
        <app-empty-state icon="exclamation-triangle" tone="error" title="Não foi possível carregar o manual" message="Verifique a conexão e tente de novo.">
          <button type="button" class="btn btn-outline-primary" (click)="load()">Tentar novamente</button>
        </app-empty-state>
      </div>
    } @else if (overview(); as o) {
      <section class="app-card" aria-labelledby="ajuda-todo">
        <h2 class="app-card__title" id="ajuda-todo">Como o sistema se encaixa</h2>
        @for (p of o.overview; track $index) {
          <p class="help__text">{{ p }}</p>
        }
        <ol class="help__flow">
          @for (c of o.connections; track c.module) {
            <li>
              <a [routerLink]="['/ajuda', c.module]">{{ moduleTitle(c.module) }}</a>
              <span class="app-muted"> — {{ c.text }}</span>
            </li>
          }
        </ol>
      </section>

      <section class="app-card" aria-labelledby="ajuda-modulos">
        <h2 class="app-card__title" id="ajuda-modulos">Manual por módulo</h2>
        <ul class="help__modules">
          @for (m of modules; track m.id) {
            <li>
              <a class="help__module" [routerLink]="['/ajuda', m.id]">
                <span class="help__module-icon" aria-hidden="true"><app-icon [name]="m.icon" /></span>
                <span>
                  <span class="help__module-title">{{ m.title }}</span>
                  <span class="help__module-summary">{{ m.summary }}</span>
                </span>
              </a>
            </li>
          }
        </ul>
      </section>

      <section class="app-card" aria-labelledby="ajuda-glossario">
        <h2 class="app-card__title" id="ajuda-glossario">Glossário</h2>
        <dl class="help__glossary">
          @for (t of glossary(); track t.id) {
            <div class="help__term" [id]="'termo-' + t.id">
              <dt>{{ t.term }}</dt>
              <dd>{{ t.definition }}</dd>
            </div>
          }
        </dl>
      </section>
    } @else {
      <div class="app-card"><app-skeleton variant="lines" [rows]="8" /></div>
    }
  `,
  styles: `
    .help__text {
      max-width: 72ch;
      line-height: var(--app-line-normal);
    }
    .help__flow {
      margin: 0;
      padding-left: 22px;
      line-height: var(--app-line-normal);
    }
    .help__flow li + li {
      margin-top: var(--app-space-1);
    }
    .help__modules {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: var(--app-space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .help__module {
      display: flex;
      gap: var(--app-space-3);
      height: 100%;
      padding: var(--app-space-3);
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius-lg);
      color: inherit;
      text-decoration: none;
    }
    .help__module:hover {
      border-color: var(--bs-primary);
    }
    .help__module-icon {
      flex: none;
      color: var(--bs-primary);
      font-size: 20px;
    }
    .help__module-title {
      display: block;
      font-weight: 500;
      color: var(--bs-link-color);
    }
    .help__module-summary {
      display: block;
      color: var(--bs-secondary-color);
      font-size: var(--app-text-sm);
      line-height: var(--app-line-normal);
    }
    .help__glossary {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
      gap: var(--app-space-3) var(--app-space-5);
      margin: 0;
    }
    .help__term dt {
      font-weight: 500;
    }
    .help__term dd {
      margin: 0;
      color: var(--bs-secondary-color);
      line-height: var(--app-line-normal);
    }
  `,
})
export class HelpCenter {
  private readonly manual = inject(ManualService);
  protected readonly modules = MANUAL_MODULES;
  protected readonly overview = signal<ModuleManual | null>(null);
  protected readonly glossary = signal<readonly GlossaryTerm[]>([]);
  protected readonly error = signal(false);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.error.set(false);
    try {
      const [overview, glossary] = await Promise.all([this.manual.module('geral'), this.manual.glossary()]);
      this.glossary.set([...glossary].sort((a, b) => a.term.localeCompare(b.term, 'pt-BR')));
      this.overview.set(overview);
    } catch {
      this.error.set(true);
    }
  }

  protected moduleTitle(id: string): string {
    return findModuleInfo(id)?.title ?? id;
  }
}
