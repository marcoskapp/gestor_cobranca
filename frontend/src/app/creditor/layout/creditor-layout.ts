import { Component, computed, effect, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { filter, map } from 'rxjs';
import { toApiError } from '../../core/http/problem';
import { ThemeService } from '../../core/preferences/theme.service';
import { Icon } from '../../shared/components/icon/icon';
import { CreditorApi } from '../data/creditor.api';
import { CreditorSession } from '../data/creditor-session';
import { CreditorLinkToken } from '../data/creditor-link-token';
import { CreditorPermission } from '../data/creditor.models';
import { fragmentToken } from '../pages/creditor-errors';

/**
 * Moldura do portal do credor: marca da assessoria (nome), o credor e a pessoa no contexto, navegação curta, tema
 * claro/escuro e "Sair". Não usa o shell, o menu nem a sessão dos usuários internos.
 */
@Component({
  selector: 'app-creditor-layout',
  imports: [NgbTooltip, Icon, RouterOutlet, RouterLink, RouterLinkActive],
  styleUrl: './creditor-layout.scss',
  template: `
    <a class="app-skip-link" href="#credor-conteudo">Pular para o conteúdo</a>
    <header class="cr__header">
      <div class="cr__bar">
        <a class="cr__brand" [routerLink]="['/credor', slug(), me() ? 'inicio' : 'entrar']">
          <span class="cr__monogram" aria-hidden="true">{{ initials() }}</span>
          <span class="cr__names">
            <span class="cr__tenant">{{ site.value()?.name }}</span>
            <span class="cr__portal">Portal do credor{{ me() ? ' · ' + me()!.creditorName : '' }}</span>
          </span>
        </a>
        <span class="cr__spacer"></span>
        <button class="btn btn-outline-secondary border-0" type="button" (click)="theme.toggleTheme()"
                [attr.aria-label]="theme.effectiveTheme() === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'"
                [ngbTooltip]="theme.effectiveTheme() === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'">
          <app-icon [name]="theme.effectiveTheme() === 'dark' ? 'sun' : 'moon-stars'" />
        </button>
        @if (me(); as m) {
          <span class="cr__who d-none d-sm-inline">{{ m.name }}</span>
          <button class="btn btn-link" type="button" (click)="logout()">
            <app-icon name="box-arrow-right" />
            Sair
          </button>
        }
      </div>
      @if (me()) {
        <nav class="cr__nav app-scroll-x" aria-label="Seções do portal do credor">
          <a [routerLink]="['/credor', slug(), 'inicio']" routerLinkActive="cr__nav--active" ariaCurrentWhenActive="page">
            <app-icon name="house" /> Início
          </a>
          @if (can(permission.OVERVIEW)) {
            <a [routerLink]="['/credor', slug(), 'prestacao-de-contas']" routerLinkActive="cr__nav--active" ariaCurrentWhenActive="page">
              <app-icon name="file-earmark-text" /> Prestação de contas
            </a>
            <a [routerLink]="['/credor', slug(), 'contestacoes']" routerLinkActive="cr__nav--active" ariaCurrentWhenActive="page">
              <app-icon name="chat-square-text" /> Contestações
            </a>
          }
        </nav>
      }
    </header>
    <main class="cr__main" id="credor-conteudo" tabindex="-1">
      @if (site.error()) {
        <section class="app-card text-center" role="alert">
          @if (siteNotFound()) {
            <h1 class="h4">Endereço não encontrado</h1>
            <p>Confira o link recebido da assessoria.</p>
          } @else {
            <h1 class="h4">Não foi possível abrir o portal</h1>
            <p>Verifique a internet e tente de novo em alguns instantes.</p>
            <button class="btn btn-outline-secondary" type="button" (click)="site.reload()">Tentar novamente</button>
          }
        </section>
      } @else if (site.value()) {
        <router-outlet />
      } @else {
        <p class="app-muted" role="status">Carregando…</p>
      }
    </main>
    <footer class="cr__footer app-muted">
      Acesso pessoal e registrado. Dúvidas sobre o acesso: fale com a assessoria {{ site.value()?.name }}.
    </footer>
  `,
})
export class CreditorLayout {
  protected readonly theme = inject(ThemeService);
  private readonly api = inject(CreditorApi);
  private readonly session = inject(CreditorSession);
  private readonly router = inject(Router);

  private readonly route = inject(ActivatedRoute);
  protected readonly slug = toSignal(this.route.paramMap.pipe(map((p) => p.get('slug') ?? '')), { initialValue: '' });
  protected readonly site = rxResource({ params: () => this.slug(), stream: ({ params }) => this.api.site(params) });
  protected readonly siteNotFound = computed(() => toApiError(this.site.error()).status === 404);
  /** Só a sessão desta assessoria (o cookie de renovação não distingue assessorias). */
  protected readonly me = computed(() => {
    const m = this.session.me();
    return m && m.tenantSlug === this.slug() ? m : null;
  });
  protected readonly permission = CreditorPermission;
  protected can(permission: string): boolean {
    return this.me()?.permissions.includes(permission) ?? false;
  }
  protected readonly initials = computed(() =>
    (this.site.value()?.name ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase())
      .join(''),
  );

  private readonly title = inject(Title);
  private readonly pageTitle = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => deepestTitle(this.route)),
    ),
    { initialValue: deepestTitle(this.route) },
  );

  constructor() {
    // título da aba com a marca da assessoria (o credor não vê o nome do sistema)
    effect(() => {
      const brand = this.site.value()?.name;
      const page = this.pageTitle();
      if (brand) {
        this.title.setTitle(page ? `${page} · ${brand}` : brand);
      }
    });
    // o token do link (convite, nova senha) sai da barra de endereços antes de qualquer carregamento e fica só em memória
    const link = inject(CreditorLinkToken);
    const token = fragmentToken(window.location.hash.replace(/^#/, ''));
    if (token) {
      link.keep(token);
      history.replaceState(history.state, '', window.location.pathname + window.location.search);
    }
  }

  protected logout(): void {
    const slug = this.slug();
    this.api.logout().subscribe({ complete: () => this.leave(slug), error: () => this.leave(slug) });
  }

  private leave(slug: string): void {
    this.session.clear('logout');
    void this.router.navigate(['/credor', slug, 'entrar']);
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
