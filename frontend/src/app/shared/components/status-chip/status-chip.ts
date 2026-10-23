import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { StatusDomain, StatusTone, describeStatus } from '../../status/status';

/** Ícone por tom: forma e texto reforçam o significado (nunca só a cor — WCAG 1.4.1). */
export const TONE_ICONS: Record<StatusTone, string> = {
  success: 'check_circle',
  warning: 'warning_amber',
  danger: 'error_outline',
  info: 'schedule',
  neutral: 'remove_circle_outline',
};

/** Indicador de estado com texto, ícone e tom acessível. */
@Component({
  selector: 'app-status-chip',
  imports: [MatIconModule],
  template: `<span class="app-status" [class]="'app-status--' + descriptor().tone"
    ><mat-icon class="app-status__icon" aria-hidden="true">{{ icon() }}</mat-icon>{{ descriptor().label }}</span
  >`,
})
export class StatusChip {
  readonly domain = input.required<StatusDomain>();
  readonly status = input.required<string | boolean | null | undefined>();
  protected readonly descriptor = computed(() => describeStatus(this.domain(), this.status()));
  protected readonly icon = computed(() => TONE_ICONS[this.descriptor().tone]);
}
