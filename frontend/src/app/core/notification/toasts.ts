import { Component, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { NgbToast } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../shared/components/icon/icon';
import { IconName } from '../../shared/components/icon/icon-names';
import { AppNotification, NotificationService, NotificationTone } from './notification.service';

const STYLE: Record<NotificationTone, { classes: string; icon: IconName }> = {
  success: { classes: 'bg-success-subtle text-success-emphasis border-success-subtle', icon: 'check-circle' },
  info: { classes: 'bg-body text-body', icon: 'info-circle' },
  error: { classes: 'bg-danger-subtle text-danger-emphasis border-danger-subtle', icon: 'exclamation-circle' },
};

/**
 * Região das notificações (canto inferior direito; largura total no celular). Sobe acima das barras de ações fixas
 * no rodapé (`appBottomBar`, que publicam a altura ocupada em `--app-bottom-inset`), para não cobrir os botões delas.
 */
@Component({
  selector: 'app-toasts',
  imports: [NgbToast, NgTemplateOutlet, Icon],
  template: `
    <div class="toast-container position-fixed end-0 p-3 app-toasts">
      @for (n of notifications.notifications(); track n.id) {
        <!-- aria-live é atributo estático do ngb-toast: um ramo por nível de urgência -->
        @if (n.tone === 'error') {
          <ngb-toast
            aria-live="assertive"
            [class]="'shadow ' + style(n).classes"
            [autohide]="!paused().has(n.id)"
            [delay]="n.delay"
            (hidden)="notifications.dismiss(n.id)"
            (mouseenter)="pause(n, true)"
            (mouseleave)="pause(n, false)"
            (focusin)="pause(n, true)"
            (focusout)="pause(n, false)"
          >
            <ng-container *ngTemplateOutlet="body; context: { $implicit: n }" />
          </ngb-toast>
        } @else {
          <ngb-toast
            aria-live="polite"
            [class]="'shadow ' + style(n).classes"
            [autohide]="!paused().has(n.id)"
            [delay]="n.delay"
            (hidden)="notifications.dismiss(n.id)"
            (mouseenter)="pause(n, true)"
            (mouseleave)="pause(n, false)"
            (focusin)="pause(n, true)"
            (focusout)="pause(n, false)"
          >
            <ng-container *ngTemplateOutlet="body; context: { $implicit: n }" />
          </ngb-toast>
        }
      }
    </div>
    <ng-template #body let-n>
      <div class="d-flex align-items-start gap-2">
        <app-icon class="mt-1" [name]="style(n).icon" />
        <span class="flex-grow-1">{{ n.message }}</span>
        <button type="button" class="btn-close flex-shrink-0" aria-label="Fechar" (click)="notifications.dismiss(n.id)"></button>
      </div>
    </ng-template>
  `,
  styles: `
    .app-toasts {
      z-index: 1090;
      bottom: var(--app-bottom-inset, 0px);
      transition: bottom 0.15s ease-out;
    }
    @media (prefers-reduced-motion: reduce) {
      .app-toasts {
        transition: none;
      }
    }
    @media (max-width: 575.98px) {
      .app-toasts {
        left: 0;
        --bs-toast-max-width: 100%;
      }
    }
  `,
})
export class Toasts {
  protected readonly notifications = inject(NotificationService);
  protected readonly paused = signal<ReadonlySet<number>>(new Set());

  protected style(n: AppNotification) {
    return STYLE[n.tone];
  }

  protected pause(n: AppNotification, paused: boolean): void {
    this.paused.update((set) => {
      const next = new Set(set);
      if (paused) {
        next.add(n.id);
      } else {
        next.delete(n.id);
      }
      return next;
    });
  }
}
