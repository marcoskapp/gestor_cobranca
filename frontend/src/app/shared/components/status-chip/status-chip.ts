import { Component, computed, input } from '@angular/core';
import { StatusDomain, StatusTone, describeStatus } from '../../status/status';
import { Icon } from '../icon/icon';
import { IconName } from '../icon/icon-names';

/** Ícone (Bootstrap Icons) por tom: forma e texto reforçam o significado (nunca só a cor — WCAG 1.4.1). */
export const TONE_ICON: Record<StatusTone, IconName> = {
  success: 'check-circle',
  warning: 'exclamation-triangle',
  danger: 'exclamation-circle',
  info: 'clock',
  neutral: 'dash-circle',
};

/** Classe de cor do Bootstrap por tom (`neutral` = secondary). */
const TONE_COLOR: Record<StatusTone, string> = {
  success: 'success',
  warning: 'warning',
  danger: 'danger',
  info: 'info',
  neutral: 'secondary',
};

/**
 * Indicador de estado: badge do Bootstrap em pílula com ícone e texto, nas cores "subtle"
 * (fundo) e "emphasis" (texto, contraste ≥ 7:1 nos dois modos).
 */
@Component({
  selector: 'app-status-chip',
  imports: [Icon],
  template: `<span
    class="badge rounded-pill border d-inline-flex align-items-center gap-1 app-status-chip"
    [class]="classes()"
    ><app-icon class="app-status-chip__icon" [name]="icon()" />{{ descriptor().label }}</span
  >`,
  styles: `
    .app-status-chip {
      font-weight: var(--bs-body-font-weight);
      line-height: 1.25rem;
      padding-block: 0;
    }
    .app-status-chip__icon {
      font-size: 0.875rem;
    }
  `,
})
export class StatusChip {
  readonly domain = input.required<StatusDomain>();
  readonly status = input.required<string | boolean | null | undefined>();
  protected readonly descriptor = computed(() => describeStatus(this.domain(), this.status()));
  protected readonly icon = computed(() => TONE_ICON[this.descriptor().tone]);
  protected readonly classes = computed(() => {
    const color = TONE_COLOR[this.descriptor().tone];
    return `bg-${color}-subtle text-${color}-emphasis border-${color}-subtle`;
  });
}
