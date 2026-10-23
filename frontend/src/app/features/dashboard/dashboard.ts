import { Component, computed, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { NAVIGATION, NavItem } from '../../core/layout/navigation';
import { GettingStarted } from '../../core/onboarding/getting-started/getting-started';
import { OnboardingService } from '../../core/onboarding/onboarding.service';
import { EmptyState } from '../../shared/components/empty-state/empty-state';

/**
 * Página inicial: boas-vindas, atalhos conforme as permissões e "primeiros passos" do perfil
 * (o painel de métricas virá depois).
 */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MatIconModule, GettingStarted, EmptyState],
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

  protected readonly mfaPending = computed(() => this.user()?.mfaEnabled === false);
}
