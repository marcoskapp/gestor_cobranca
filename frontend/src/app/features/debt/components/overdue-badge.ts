import { Component, computed, input } from '@angular/core';
import { TONE_ICON } from '../../../shared/components/status-chip/status-chip';
import { overdueText, overdueTone } from '../data/debt.labels';
import { Icon } from '../../../shared/components/icon/icon';

/**
 * Atraso do título (aging) com tom, ícone e texto — o significado nunca depende só da cor.
 * Títulos fora de cobrança (quitados, cancelados, devolvidos) não exibem atraso.
 */
@Component({
  selector: 'app-overdue-badge',
  imports: [Icon],
  template: `
    @if (active()) {
      <span class="app-status" [class]="'app-status--' + tone()"
        ><app-icon class="app-status__icon" [name]="icon()" />{{ text() }}</span
      >
    } @else {
      <span class="app-muted">—</span>
    }
  `,
})
export class OverdueBadge {
  readonly days = input.required<number>();
  /** Situação do título; atraso só é relevante em cobrança (OPEN/SUSPENDED). */
  readonly status = input<string>('OPEN');

  protected readonly active = computed(() => this.status() === 'OPEN' || this.status() === 'SUSPENDED');
  protected readonly tone = computed(() => overdueTone(this.days()));
  protected readonly icon = computed(() => (this.days() <= 0 ? 'calendar-check' : TONE_ICON[this.tone()]));
  protected readonly text = computed(() => overdueText(this.days()));
}
