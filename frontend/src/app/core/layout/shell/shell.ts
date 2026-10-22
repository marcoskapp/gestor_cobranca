import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { APP_NAME } from '../../config/page-title.strategy';
import { NAVIGATION, NavSection } from '../navigation';

/** Estrutura da área autenticada: barra superior, menu lateral por permissão e conteúdo. */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatSidenavModule,
    MatToolbarModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatMenuModule,
    MatDividerModule,
    MatTooltipModule,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly auth = inject(AuthService);
  protected readonly appName = APP_NAME;
  protected readonly user = this.auth.user;

  protected readonly compact = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );
  /** Menu fixo (telas largas) ou sobreposto (telas estreitas), com estados independentes. */
  private readonly desktopOpen = signal(true);
  private readonly mobileOpen = signal(false);
  protected readonly sidenavOpened = computed(() => (this.compact() ? this.mobileOpen() : this.desktopOpen()));

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

  protected toggleMenu(): void {
    const target = this.compact() ? this.mobileOpen : this.desktopOpen;
    target.update((open) => !open);
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

  protected logout(): void {
    this.auth.logout().subscribe();
  }
}
