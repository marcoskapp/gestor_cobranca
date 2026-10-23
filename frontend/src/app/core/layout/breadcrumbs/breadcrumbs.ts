import { Component, Injectable, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { NavigationEnd, Router, RouterLink, TitleStrategy } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { NAVIGATION } from '../navigation';

export interface Breadcrumb {
  label: string;
  route?: string;
}

/** Áreas fora do menu principal (menu do usuário). */
const EXTRA_AREAS: Record<string, string> = { conta: 'Minha conta' };

/**
 * Permite a uma tela de detalhe trocar o último item da trilha pelo nome do registro
 * (ex.: "Credor" → "Banco Alfa S.A."). Limpo a cada navegação.
 */
@Injectable({ providedIn: 'root' })
export class BreadcrumbService {
  private readonly label = signal<string | null>(null);
  readonly currentLabel = this.label.asReadonly();

  constructor() {
    inject(Router)
      .events.pipe(filter((e) => e instanceof NavigationEnd))
      .subscribe(() => this.label.set(null));
  }

  setCurrentLabel(label: string | null): void {
    this.label.set(label);
  }
}

/** Monta a trilha a partir do menu (seção → item) e do título da rota atual. */
export function buildBreadcrumbs(url: string, pageTitle: string | undefined): Breadcrumb[] {
  const path = url.split(/[?#]/)[0] || '/';
  if (path === '/') {
    return [];
  }
  const crumbs: Breadcrumb[] = [{ label: 'Início', route: '/' }];
  let best: { section: string | null; label: string; route: string } | null = null;
  for (const section of NAVIGATION) {
    for (const item of section.items) {
      if (item.route !== '/' && (path === item.route || path.startsWith(item.route + '/'))) {
        if (!best || item.route.length > best.route.length) {
          best = { section: section.label, label: item.label, route: item.route };
        }
      }
    }
  }
  if (best) {
    if (best.section) {
      crumbs.push({ label: best.section });
    }
    const isCurrent = path === best.route;
    crumbs.push(isCurrent ? { label: best.label } : { label: best.label, route: best.route });
    if (!isCurrent && pageTitle && pageTitle !== best.label) {
      crumbs.push({ label: pageTitle });
    }
    return crumbs;
  }
  const area = EXTRA_AREAS[path.split('/')[1] ?? ''];
  if (area) {
    crumbs.push({ label: area });
  }
  if (pageTitle) {
    crumbs.push({ label: pageTitle });
  }
  return crumbs;
}

@Component({
  selector: 'app-breadcrumbs',
  imports: [RouterLink, MatIconModule],
  template: `
    @if (crumbs().length > 1) {
      <nav class="crumbs" aria-label="Trilha de navegação">
        <ol>
          @for (crumb of crumbs(); track $index; let last = $last) {
            <li>
              @if (crumb.route && !last) {
                <a [routerLink]="crumb.route">{{ crumb.label }}</a>
              } @else {
                <span [attr.aria-current]="last ? 'page' : null">{{ crumb.label }}</span>
              }
              @if (!last) {
                <mat-icon class="crumbs__sep" aria-hidden="true">chevron_right</mat-icon>
              }
            </li>
          }
        </ol>
      </nav>
    }
  `,
  styles: `
    .crumbs ol {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 2px;
      margin: 0 0 8px;
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
      color: var(--mat-sys-on-surface-variant);
    }
    .crumbs li {
      display: inline-flex;
      align-items: center;
      gap: 2px;
    }
    .crumbs a {
      color: inherit;
      text-decoration: none;
      border-radius: var(--app-radius-sm);
    }
    .crumbs a:hover {
      color: var(--mat-sys-primary);
      text-decoration: underline;
    }
    [aria-current='page'] {
      color: var(--mat-sys-on-surface);
      font-weight: 500;
    }
    .crumbs__sep {
      width: 16px;
      height: 16px;
      font-size: 16px;
    }
    @media (max-width: 599.98px) {
      .crumbs li:not(:nth-last-child(-n + 2)) {
        display: none;
      }
    }
  `,
})
export class Breadcrumbs {
  private readonly router = inject(Router);
  private readonly titles = inject(TitleStrategy);
  private readonly override = inject(BreadcrumbService).currentLabel;

  private readonly base = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => buildBreadcrumbs(this.router.url, this.titles.buildTitle(this.router.routerState.snapshot))),
    ),
    { initialValue: [] as Breadcrumb[] },
  );

  protected readonly crumbs = computed(() => {
    const crumbs = this.base();
    const label = this.override();
    return label && crumbs.length > 1 ? [...crumbs.slice(0, -1), { label }] : crumbs;
  });
}
