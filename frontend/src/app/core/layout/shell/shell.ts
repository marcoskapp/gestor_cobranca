import { BreakpointObserver } from '@angular/cdk/layout';
import { NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, TemplateRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NgbDropdownModule, NgbOffcanvas, NgbOffcanvasRef, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { Icon } from '../../../shared/components/icon/icon';
import { IconName } from '../../../shared/components/icon/icon-names';
import { AuthService } from '../../auth/auth.service';
import { APP_NAME } from '../../config/page-title.strategy';
import { KeyboardShortcuts, formatKeys } from '../../keyboard/keyboard-shortcuts.service';
import { OnboardingHint } from '../../onboarding/hint/onboarding-hint';
import { OnboardingService } from '../../onboarding/onboarding.service';
import { PendingWorkIndicator } from '../../pending-work/pending-work-indicator';
import { ThemeService } from '../../preferences/theme.service';
import { DensityPreference, ThemePreference } from '../../preferences/ui-state.models';
import { UiStateStore } from '../../preferences/ui-state.store';
import { Breadcrumbs } from '../breadcrumbs/breadcrumbs';
import { CommandPaletteService, PaletteCommand } from '../command-palette/command-palette.service';
import { HelpService } from '../help/help.service';
import { NAVIGATION, NavSection, activeNavRoute } from '../navigation';

interface Choice<T> {
  value: T;
  label: string;
  icon: IconName;
}

const THEMES: readonly Choice<ThemePreference>[] = [
  { value: 'SYSTEM', label: 'Seguir o sistema', icon: 'circle-half' },
  { value: 'LIGHT', label: 'Claro', icon: 'sun' },
  { value: 'DARK', label: 'Escuro', icon: 'moon-stars' },
];

const DENSITIES: readonly Choice<DensityPreference>[] = [
  { value: 'COMFORTABLE', label: 'Confortável', icon: 'distribute-vertical' },
  { value: 'COMPACT', label: 'Compacta', icon: 'list' },
];

/**
 * Estrutura da área autenticada (Bootstrap 5.3 + ng-bootstrap): cabeçalho (contexto da
 * assessoria, busca rápida, ajuda e conta em menus `ngbDropdown`), menu lateral por permissão
 * recolhível em trilho de ícones (telas largas) ou em painel `NgbOffcanvas` (telas estreitas),
 * trilha de navegação, conteúdo e convites do onboarding.
 */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    NgTemplateOutlet,
    NgbDropdownModule,
    NgbTooltip,
    Icon,
    Breadcrumbs,
    OnboardingHint,
    PendingWorkIndicator,
  ],
  templateUrl: './shell.html',
  styleUrls: ['./shell.scss', './shell-nav.scss'],
})
export class Shell {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly store = inject(UiStateStore);
  private readonly palette = inject(CommandPaletteService);
  private readonly shortcuts = inject(KeyboardShortcuts);
  private readonly offcanvas = inject(NgbOffcanvas);
  private readonly offcanvasNav = viewChild.required<TemplateRef<unknown>>('offcanvasNav');
  private offcanvasRef: NgbOffcanvasRef | null = null;
  protected readonly theme = inject(ThemeService);
  protected readonly onboarding = inject(OnboardingService);
  protected readonly help = inject(HelpService);

  protected readonly appName = APP_NAME;
  protected readonly user = this.auth.user;
  protected readonly themes = THEMES;
  protected readonly densities = DENSITIES;
  protected readonly paletteKeys = formatKeys('mod+k').join(' ');

  protected readonly compact = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );
  /** Menu fixo (telas largas, expandido ou trilho de ícones) ou painel lateral (telas estreitas). */
  private readonly mobileOpen = signal(false);
  protected readonly rail = computed(() => !this.compact() && this.store.state().navigationCollapsed);
  protected readonly menuExpanded = computed(() => (this.compact() ? this.mobileOpen() : !this.rail()));

  protected readonly sections = computed<NavSection[]>(() => {
    // recalcula quando o usuário (e suas permissões) muda
    this.user();
    return NAVIGATION.map((section) => ({
      ...section,
      items: section.items.filter((item) => this.auth.hasAnyPermission(item.permissions)),
    })).filter((section) => section.items.length > 0);
  });

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
      startWith(this.router.url),
    ),
    { initialValue: this.router.url },
  );
  /** Item do menu marcado: o mais específico para o endereço atual (`activeNavRoute`). */
  protected readonly activeRoute = computed(() =>
    activeNavRoute(this.url(), this.sections().flatMap((section) => section.items.map((item) => item.route))),
  );

  protected readonly initials = computed(() => {
    const name = this.user()?.name ?? '';
    const parts = name.trim().split(/\s+/).filter(Boolean);
    return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? (parts[parts.length - 1][0] ?? '') : '')).toUpperCase();
  });

  protected readonly creditorScope = computed(() => {
    const u = this.user();
    if (!u) {
      return '';
    }
    if (u.allCreditors) {
      return 'Todos os credores';
    }
    const n = u.creditorIds.length;
    return n === 0 ? 'Nenhum credor' : n === 1 ? '1 credor' : `${n} credores`;
  });

  constructor() {
    const destroy = inject(DestroyRef);
    for (const unregister of [this.registerShortcuts(), this.registerCommands()]) {
      destroy.onDestroy(unregister);
    }
    destroy.onDestroy(() => this.offcanvasRef?.dismiss());
    // ao alargar a janela com o painel aberto, o menu volta a ser fixo
    effect(() => {
      if (!this.compact()) {
        this.offcanvasRef?.dismiss();
      }
    });
  }

  protected toggleMenu(): void {
    if (!this.compact()) {
      this.store.update({ navigationCollapsed: !this.store.state().navigationCollapsed });
    } else if (this.offcanvasRef) {
      this.offcanvasRef.dismiss();
    } else {
      this.openMobileMenu();
    }
  }

  protected onNavigate(): void {
    this.offcanvasRef?.close();
  }

  private openMobileMenu(): void {
    const ref = this.offcanvas.open(this.offcanvasNav(), {
      position: 'start',
      ariaLabelledBy: 'menu-principal-titulo',
      panelClass: 'app-offcanvas-nav',
    });
    this.offcanvasRef = ref;
    this.mobileOpen.set(true);
    const done = () => {
      this.offcanvasRef = null;
      this.mobileOpen.set(false);
    };
    ref.result.then(done, done);
  }

  protected openPalette(): void {
    void this.palette.open();
  }

  protected showGettingStarted(): void {
    this.onboarding.setChecklistDismissed(false);
    void this.router.navigateByUrl('/');
  }

  protected async logout(): Promise<void> {
    // envia preferências/progresso pendentes antes de encerrar a sessão
    await this.store.flush();
    this.auth.logout().subscribe();
  }

  private registerShortcuts(): () => void {
    const removers = [
      this.shortcuts.register({
        id: 'palette',
        keys: 'mod+k',
        description: 'Busca rápida: telas e ações',
        group: 'Geral',
        allowInInputs: true,
        handler: () => this.openPalette(),
      }),
      this.shortcuts.register({
        id: 'shortcuts-help',
        keys: '?',
        description: 'Mostrar os atalhos de teclado',
        group: 'Geral',
        handler: () => void this.help.openShortcuts(),
      }),
      this.shortcuts.register({
        id: 'toggle-menu',
        keys: 'alt+m',
        description: 'Recolher ou expandir o menu lateral',
        group: 'Geral',
        handler: () => this.toggleMenu(),
      }),
    ];
    return () => removers.forEach((remove) => remove());
  }

  private registerCommands(): () => void {
    const action = (id: string, label: string, icon: IconName, run: () => void, keywords: string[] = []): PaletteCommand => ({
      id,
      label,
      icon,
      run,
      keywords,
      group: 'Ações',
    });
    return this.palette.register([
      action('theme.light', 'Usar tema claro', 'sun', () => this.theme.setTheme('LIGHT'), ['aparência', 'cor']),
      action('theme.dark', 'Usar tema escuro', 'moon-stars', () => this.theme.setTheme('DARK'), ['aparência', 'noturno']),
      action('theme.system', 'Seguir o tema do sistema', 'circle-half', () => this.theme.setTheme('SYSTEM'), ['aparência']),
      action('density.compact', 'Densidade compacta', 'list', () => this.theme.setDensity('COMPACT'), ['tabela', 'espaço']),
      action('density.comfortable', 'Densidade confortável', 'distribute-vertical', () => this.theme.setDensity('COMFORTABLE'), ['espaço']),
      action('help.tour', 'Fazer o tour desta tela', 'signpost', () => void this.onboarding.startScreenTour(), ['ajuda', 'onboarding']),
      action('help.about', 'Sobre esta tela', 'info-circle', () => void this.help.openScreenManual(), ['ajuda', 'manual', 'para que serve']),
      action('help.manual', 'Manual do sistema', 'journal-text', () => void this.router.navigateByUrl('/ajuda'), ['ajuda', 'manual', 'documentação', 'glossário']),
      action('help.getting-started', 'Primeiros passos', 'list-check', () => this.showGettingStarted(), ['ajuda', 'checklist']),
      action('help.whats-new', 'Novidades da versão', 'patch-exclamation', () => void this.help.openWhatsNew(), ['ajuda', 'release']),
      action('help.shortcuts', 'Atalhos de teclado', 'keyboard', () => void this.help.openShortcuts(), ['ajuda', 'teclas']),
      action('account.profile', 'Meu perfil', 'person', () => void this.router.navigateByUrl('/conta/perfil'), ['conta']),
      action('account.sessions', 'Minhas sessões', 'laptop', () => void this.router.navigateByUrl('/conta/sessoes'), ['conta']),
      action('account.password', 'Trocar senha', 'asterisk', () => void this.router.navigateByUrl('/conta/senha'), ['conta']),
      action('account.logout', 'Sair', 'box-arrow-right', () => void this.logout(), ['logout', 'encerrar']),
    ]);
  }
}
