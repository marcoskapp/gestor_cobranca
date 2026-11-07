import { Component, EffectRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, TitleStrategy } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { Icon } from '../../../shared/components/icon/icon';
import { NAVIGATION } from '../navigation';
import { NavigationHistory, pathOf } from './navigation-history';

export interface Breadcrumb {
  label: string;
  route?: string;
}

/** Áreas fora do menu principal (menu do usuário). */
const EXTRA_AREAS: Record<string, string> = { conta: 'Minha conta', ajuda: 'Ajuda' };

/**
 * Permite a uma tela de detalhe trocar o último item da trilha pelo nome do registro
 * (ex.: "Credor" → "Banco Alfa S.A."). Limpo a cada navegação. O mesmo nome identifica a tela no
 * "← Voltar para <origem>" de quem sair dela.
 */
@Injectable({ providedIn: 'root' })
export class BreadcrumbService {
  private readonly history = inject(NavigationHistory);
  private readonly label = signal<string | null>(null);
  readonly currentLabel = this.label.asReadonly();

  constructor() {
    inject(Router)
      .events.pipe(filter((e) => e instanceof NavigationEnd))
      .subscribe(() => this.label.set(null));
  }

  setCurrentLabel(label: string | null): void {
    this.label.set(label);
    if (label) {
      this.history.setCurrentLabel(label);
    }
  }
}

/**
 * Nome do registro na trilha das fichas (contexto de injeção): `protected readonly crumb = recordLabel(() => this.current()?.name);`
 */
export function recordLabel(label: () => string | null | undefined): EffectRef {
  const breadcrumbs = inject(BreadcrumbService);
  return effect(() => breadcrumbs.setCurrentLabel(label() || null));
}

/**
 * Monta a trilha a partir do menu (seção → item) e do título da rota atual. A seção some quando tem o mesmo nome do
 * item ("Pagamentos › Pagamentos" vira "Pagamentos").
 */
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
    if (best.section && best.section !== best.label) {
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

/**
 * Trilha de navegação (menu → tela → registro) com o retorno à tela de origem: "← Voltar para <origem>" quando o
 * usuário chegou de outra tela do app que não é a lista da trilha, e o link da lista volta com os filtros e a
 * rolagem de quando ele saiu (é o "voltar" do navegador quando a lista é a origem).
 */
@Component({
  selector: 'app-breadcrumbs',
  imports: [RouterLink, Icon],
  template: `
    @if (crumbs().length > 1 || backTo()) {
      <nav class="app-crumbs-bar" aria-label="Trilha de navegação">
        @if (backTo(); as origin) {
          <a class="btn btn-sm btn-outline-secondary app-crumbs__back" [routerLink]="history.linkTo(origin.url)" (click)="goBack($event)">
            <app-icon name="arrow-left" />
            Voltar para {{ origin.label }}
          </a>
        }
        @if (crumbs().length > 1) {
          <ol class="breadcrumb app-crumbs">
            @for (crumb of crumbs(); track $index; let last = $last) {
              <li class="breadcrumb-item" [class.active]="last" [attr.aria-current]="last ? 'page' : null">
                @if (crumb.route && !last) {
                  <a [routerLink]="history.linkTo(crumb.route)" (click)="crumbClick($event, crumb.route)">{{ crumb.label }}</a>
                } @else {
                  {{ crumb.label }}
                }
              </li>
            }
          </ol>
        }
      </nav>
    }
  `,
  styles: `
    .app-crumbs-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      column-gap: 16px;
    }
    /* retorno contextual: botão secundário pequeno (ux.md §12.2), na linha da trilha, longe da ação primária */
    .app-crumbs__back {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      margin-bottom: var(--bs-breadcrumb-margin-bottom, 1rem);
    }
    .app-crumbs a {
      color: var(--bs-secondary-color);
      border-radius: var(--bs-border-radius-sm);
    }
    .app-crumbs a:hover {
      color: var(--bs-link-hover-color);
    }
    @media (max-width: 599.98px) {
      .app-crumbs .breadcrumb-item:not(:nth-last-child(-n + 2)) {
        display: none;
      }
      /* o primeiro item visível não mostra o separador */
      .app-crumbs .breadcrumb-item:nth-last-child(2) {
        padding-left: 0;
      }
      .app-crumbs .breadcrumb-item:nth-last-child(2)::before {
        display: none;
      }
    }
  `,
})
export class Breadcrumbs {
  private readonly router = inject(Router);
  private readonly titles = inject(TitleStrategy);
  private readonly override = inject(BreadcrumbService).currentLabel;
  protected readonly history = inject(NavigationHistory);

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

  /** Origem fora da trilha (a lista da trilha já é o caminho de volta) e fora do início. */
  protected readonly backTo = computed(() => {
    const origin = this.history.origin();
    if (!origin || origin.path === '/' || !origin.label) {
      return null;
    }
    const inTrail = this.crumbs().some((c) => c.route && c.route !== '/' && pathOf(c.route) === origin.path);
    return inTrail ? null : origin;
  });

  protected goBack(event: MouseEvent): void {
    if (this.isPlainClick(event)) {
      event.preventDefault();
      this.history.back();
    }
  }

  /** A lista da trilha é a origem: o "voltar" do navegador restaura filtros, página e rolagem. */
  protected crumbClick(event: MouseEvent, route: string): void {
    if (this.isPlainClick(event) && this.history.origin()?.path === pathOf(route)) {
      event.preventDefault();
      this.history.back();
    }
  }

  private isPlainClick(event: MouseEvent): boolean {
    return event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey;
  }
}
