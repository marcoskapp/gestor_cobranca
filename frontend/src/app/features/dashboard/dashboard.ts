import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { NAVIGATION, NavItem } from '../../core/layout/navigation';
import { GettingStarted } from '../../core/onboarding/getting-started/getting-started';
import { OnboardingService } from '../../core/onboarding/onboarding.service';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Permission } from '../../core/auth/permission';
import { HomeKpis } from '../analytics/components/home-kpis';

/**
 * Página inicial: boas-vindas, resumo da operação (para quem vê painéis, carregado sob demanda),
 * atalhos conforme as permissões e "primeiros passos" do perfil.
 */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, Icon, GettingStarted, EmptyState, HomeKpis],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  private readonly auth = inject(AuthService);
  protected readonly user = this.auth.user;
  protected readonly onboarding = inject(OnboardingService);
  protected readonly today = new Intl.DateTimeFormat('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' }).format(
    new Date(),
  );

  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    const first = this.user()?.name.split(/\s+/)[0] ?? '';
    const period = hour < 12 ? 'Bom dia' : hour < 18 ? 'Boa tarde' : 'Boa noite';
    return first ? `${period}, ${first}` : period;
  });

  protected readonly shortcuts = computed<(NavItem & { section: string })[]>(() => {
    this.user();
    return NAVIGATION.flatMap((section) =>
      section.items
        .filter((item) => item.route !== '/' && this.auth.hasAnyPermission(item.permissions))
        .map((item) => ({ ...item, section: section.label ?? '' })),
    );
  });

  /** Resumo de indicadores para quem tem acesso ao painel operacional. */
  protected readonly showKpis = computed(() => {
    this.user();
    return this.auth.hasAnyPermission([Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW]);
  });

  protected readonly mfaPending = computed(() => this.user()?.mfaEnabled === false);
}
