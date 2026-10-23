import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { APP_NAME } from '../../config/page-title.strategy';
import { KeyboardShortcuts, formatKeys } from '../../keyboard/keyboard-shortcuts.service';
import { OnboardingHint } from '../../onboarding/hint/onboarding-hint';
import { OnboardingService } from '../../onboarding/onboarding.service';
import { ThemeService } from '../../preferences/theme.service';
import { DensityPreference, ThemePreference } from '../../preferences/ui-state.models';
import { UiStateStore } from '../../preferences/ui-state.store';
import { Breadcrumbs } from '../breadcrumbs/breadcrumbs';
import { CommandPaletteService, PaletteCommand } from '../command-palette/command-palette.service';
import { HelpService } from '../help/help.service';
import { NAVIGATION, NavSection } from '../navigation';

interface Choice<T> {
  value: T;
  label: string;
  icon: string;
}

const THEMES: readonly Choice<ThemePreference>[] = [
  { value: 'SYSTEM', label: 'Seguir o sistema', icon: 'brightness_auto' },
  { value: 'LIGHT', label: 'Claro', icon: 'light_mode' },
  { value: 'DARK', label: 'Escuro', icon: 'dark_mode' },
];

const DENSITIES: readonly Choice<DensityPreference>[] = [
  { value: 'COMFORTABLE', label: 'Confortável', icon: 'density_medium' },
  { value: 'COMPACT', label: 'Compacta', icon: 'density_small' },
];

/**
 * Estrutura da área autenticada: cabeçalho (contexto da assessoria, busca rápida, ajuda e
 * conta), menu lateral por permissão recolhível em trilho de ícones, trilha de navegação,
 * conteúdo e convites do onboarding.
 */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatMenuModule,
    MatDividerModule,
    MatTooltipModule,
    MatBadgeModule,
    Breadcrumbs,
    OnboardingHint,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly store = inject(UiStateStore);
  private readonly palette = inject(CommandPaletteService);
  private readonly shortcuts = inject(KeyboardShortcuts);
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
  /** Menu fixo (telas largas, expandido ou trilho de ícones) ou sobreposto (telas estreitas). */
  private readonly mobileOpen = signal(false);
  protected readonly rail = computed(() => !this.compact() && this.store.state().navigationCollapsed);
  protected readonly sidenavOpened = computed(() => (this.compact() ? this.mobileOpen() : true));
  protected readonly menuExpanded = computed(() => (this.compact() ? this.mobileOpen() : !this.rail()));

  protected readonly sections = computed<NavSection[]>(() => {
    // recalcula quando o usuário (e suas permissões) muda
    this.user();
    return NAVIGATION.map((section) => ({
      ...section,
      items: section.items.filter((item) => this.auth.hasAnyPermission(item.permissions)),
    })).filter((section) => section.items.length > 0);
  });

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
  }

  protected toggleMenu(): void {
    if (this.compact()) {
      this.mobileOpen.update((open) => !open);
    } else {
      this.store.update({ navigationCollapsed: !this.store.state().navigationCollapsed });
    }
  }

  protected onNavigate(): void {
    if (this.compact()) {
      this.mobileOpen.set(false);
    }
  }

  protected onSidenavClosed(): void {
    if (this.compact()) {
      this.mobileOpen.set(false);
    }
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
    const action = (id: string, label: string, icon: string, run: () => void, keywords: string[] = []): PaletteCommand => ({
      id,
      label,
      icon,
      run,
      keywords,
      group: 'Ações',
    });
    return this.palette.register([
      action('theme.light', 'Usar tema claro', 'light_mode', () => this.theme.setTheme('LIGHT'), ['aparência', 'cor']),
      action('theme.dark', 'Usar tema escuro', 'dark_mode', () => this.theme.setTheme('DARK'), ['aparência', 'noturno']),
      action('theme.system', 'Seguir o tema do sistema', 'brightness_auto', () => this.theme.setTheme('SYSTEM'), ['aparência']),
      action('density.compact', 'Densidade compacta', 'density_small', () => this.theme.setDensity('COMPACT'), ['tabela', 'espaço']),
      action('density.comfortable', 'Densidade confortável', 'density_medium', () => this.theme.setDensity('COMFORTABLE'), ['espaço']),
      action('help.tour', 'Fazer o tour desta tela', 'tour', () => this.onboarding.startScreenTour(), ['ajuda', 'onboarding']),
      action('help.getting-started', 'Primeiros passos', 'checklist', () => this.showGettingStarted(), ['ajuda', 'checklist']),
      action('help.whats-new', 'Novidades da versão', 'new_releases', () => void this.help.openWhatsNew(), ['ajuda', 'release']),
      action('help.shortcuts', 'Atalhos de teclado', 'keyboard', () => void this.help.openShortcuts(), ['ajuda', 'teclas']),
      action('account.profile', 'Meu perfil', 'person', () => void this.router.navigateByUrl('/conta/perfil'), ['conta']),
      action('account.sessions', 'Minhas sessões', 'devices', () => void this.router.navigateByUrl('/conta/sessoes'), ['conta']),
      action('account.password', 'Trocar senha', 'password', () => void this.router.navigateByUrl('/conta/senha'), ['conta']),
      action('account.logout', 'Sair', 'logout', () => void this.logout(), ['logout', 'encerrar']),
    ]);
  }
}
