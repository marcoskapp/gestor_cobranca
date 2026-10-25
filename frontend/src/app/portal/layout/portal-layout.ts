import { Component, ViewEncapsulation, computed, effect, inject, input } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { ThemeService } from '../../core/preferences/theme.service';
import { PortalApi } from '../data/portal.api';
import { PortalSession } from '../data/portal-session';
import { PortalSite } from './portal-site';
import { Icon } from '../../shared/components/icon/icon';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';

/**
 * Moldura do portal do devedor: marca do credor (nome, logotipo, cores validadas para contraste AA),
 * navegação simples para celular, tema claro/escuro e rodapé com o atendimento. Não usa o shell, o
 * menu nem a sessão dos usuários internos.
 */
@Component({
  selector: 'app-portal-layout',
  imports: [NgbTooltip, Icon, RouterOutlet, RouterLink, RouterLinkActive],
  providers: [PortalSite],
  encapsulation: ViewEncapsulation.None,
  host: {
    class: 'portal',
    '[style.--portal-primary]': 'site.value()?.primaryColor ?? "#1D4E89"',
    '[style.--portal-accent]': 'site.value()?.accentColor ?? "#0B6E4F"',
  },
  template: `
    <a class="app-skip-link" href="#portal-conteudo">Pular para o conteúdo</a>
    <header class="portal__header">
      <div class="portal__bar">
        <a class="portal__brand" [routerLink]="loggedIn() ? ['/portal', slug(), 'dividas'] : ['/portal', slug()]">
          @if (site.value()?.hasLogo) {
            <img class="portal__logo" [src]="logoUrl()" [alt]="site.value()?.displayName ?? ''" />
          } @else {
            <span class="portal__monogram" aria-hidden="true">{{ initials() }}</span>
            <span class="portal__name">{{ site.value()?.displayName }}</span>
          }
        </a>
        <span class="portal__spacer"></span>
        <button
          class="btn btn-outline-secondary border-0 portal__icon-btn"
          type="button"
          (click)="theme.toggleTheme()"
          [attr.aria-label]="theme.effectiveTheme() === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'"
          [ngbTooltip]="theme.effectiveTheme() === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'"
        >
          <app-icon [name]="theme.effectiveTheme() === 'dark' ? 'sun' : 'moon-stars'" />
        </button>
        @if (loggedIn()) {
          <button class="btn btn-link portal-btn-link" type="button" (click)="logout()">
            <app-icon name="box-arrow-right" />
            Sair
          </button>
        }
      </div>
      @if (loggedIn()) {
        <nav class="portal__nav" aria-label="Seções do portal">
          <a [routerLink]="['/portal', slug(), 'dividas']" routerLinkActive="portal__nav--active" ariaCurrentWhenActive="page">
            <app-icon name="receipt" />Minhas dívidas
          </a>
          <a [routerLink]="['/portal', slug(), 'acordos']" routerLinkActive="portal__nav--active" ariaCurrentWhenActive="page">
            <app-icon name="person-hearts" />Meus acordos
          </a>
          <a [routerLink]="['/portal', slug(), 'atendimento']" routerLinkActive="portal__nav--active" ariaCurrentWhenActive="page">
            <app-icon name="headset" />Ajuda
          </a>
        </nav>
      }
    </header>

    <main class="portal__main" id="portal-conteudo" tabindex="-1">
      @if (site.error()) {
        <section class="portal-card portal-center" role="alert">
          <app-icon class="portal-big-icon" name="link" />
          <h1 class="portal-title">Endereço não encontrado</h1>
          <p>Confira o link recebido ou procure a empresa que enviou a cobrança.</p>
        </section>
      } @else if (site.value()) {
        <router-outlet />
      } @else {
        <p class="portal-loading" role="status" aria-live="polite">Carregando…</p>
      }
    </main>

    <footer class="portal__footer">
      @if (site.value()?.supportText; as support) {
        <p>{{ support }}</p>
      }
      <p>Ambiente seguro. Seus acessos são registrados para sua proteção.</p>
    </footer>
  `,
  styleUrl: './portal-layout.scss',
})
export class PortalLayout {
  readonly slug = input.required<string>();

  private readonly api = inject(PortalApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly title = inject(Title);
  private readonly portalSite = inject(PortalSite);
  protected readonly session = inject(PortalSession);
  protected readonly theme = inject(ThemeService);

  protected readonly site = rxResource({ params: () => this.slug(), stream: ({ params }) => this.api.site(params) });
  protected readonly loggedIn = computed(() => !!this.session.token() && this.session.slug() === this.slug());
  protected readonly logoUrl = computed(() => this.api.logoUrl(this.slug(), this.site.value()?.logoVersion ?? null));
  protected readonly initials = computed(() =>
    (this.site.value()?.displayName ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase())
      .join(''),
  );

  private readonly pageTitle = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => deepestTitle(this.route)),
    ),
    { initialValue: deepestTitle(this.route) },
  );

  constructor() {
    effect(() => this.portalSite.set(this.site.value() ?? null));
    // título da aba com a marca do credor (o devedor não vê o nome do sistema interno)
    effect(() => {
      const brand = this.site.value()?.displayName;
      const page = this.pageTitle();
      if (brand) {
        this.title.setTitle(page ? `${page} · ${brand}` : brand);
      }
    });
  }

  protected logout(): void {
    const slug = this.slug();
    this.api.logout().subscribe({ error: () => undefined });
    this.session.clear('logout');
    void this.router.navigate(['/portal', slug]);
  }
}

function deepestTitle(route: ActivatedRoute): string | null {
  let current: ActivatedRoute | null = route;
  let title: string | null = null;
  while (current) {
    const t = current.snapshot?.routeConfig?.title;
    if (typeof t === 'string') {
      title = t;
    }
    current = current.firstChild;
  }
  return title;
}
