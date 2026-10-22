import { Component, computed, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { NAVIGATION, NavItem } from '../../core/layout/navigation';

/** Página inicial: boas-vindas e atalhos conforme as permissões (o painel de métricas virá depois). */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MatIconModule],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  private readonly auth = inject(AuthService);
  protected readonly user = this.auth.user;

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
