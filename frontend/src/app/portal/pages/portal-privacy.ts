import { Component, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { PortalApi, portalMessage } from '../data/portal.api';
import { PortalSite } from '../layout/portal-site';

/** Aviso de privacidade (LGPD art. 9º), exigido antes de consultar as dívidas; registrado no compliance. */
@Component({
  selector: 'app-portal-privacy',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <section class="portal-card" aria-labelledby="aviso-titulo">
      <h1 class="portal-title" id="aviso-titulo">Antes de continuar</h1>
      <p class="portal-subtitle">Leia como seus dados são usados neste portal.</p>
      <div class="portal-notice" tabindex="0" role="document" aria-label="Aviso de privacidade">{{ site()?.privacyNotice }}</div>
      @if (error()) {
        <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon><span>{{ error() }}</span></p>
      }
      <div class="portal-actions">
        <button mat-flat-button type="button" (click)="acknowledge()" [disabled]="busy() || !site()">Li o aviso e quero continuar</button>
      </div>
    </section>
  `,
  styles: `
    .portal-notice {
      max-height: 50vh;
      overflow: auto;
      padding: 12px;
      border: 1px solid var(--app-border);
      border-radius: 8px;
      white-space: pre-wrap;
    }
  `,
})
export class PortalPrivacy {
  readonly slug = input.required<string>();

  private readonly api = inject(PortalApi);
  private readonly router = inject(Router);
  protected readonly site = inject(PortalSite).site;
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected acknowledge(): void {
    const version = this.site()?.privacyNoticeVersion;
    if (!version) {
      return;
    }
    this.busy.set(true);
    this.api.acknowledgePrivacy(version).subscribe({
      next: () => void this.router.navigate(['/portal', this.slug(), 'dividas']),
      error: (e: unknown) => {
        this.busy.set(false);
        this.error.set(portalMessage(e));
      },
    });
  }
}
