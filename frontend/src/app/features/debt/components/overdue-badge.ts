import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { TONE_ICONS } from '../../../shared/components/status-chip/status-chip';
import { overdueText, overdueTone } from '../data/debt.labels';

/**
 * Atraso do título (aging) com tom, ícone e texto — o significado nunca depende só da cor.
 * Títulos fora de cobrança (quitados, cancelados, devolvidos) não exibem atraso.
 */
@Component({
  selector: 'app-overdue-badge',
  imports: [MatIconModule],
  template: `
    @if (active()) {
      <span class="app-status" [class]="'app-status--' + tone()"
        ><mat-icon class="app-status__icon" aria-hidden="true">{{ icon() }}</mat-icon>{{ text() }}</span
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
  protected readonly icon = computed(() => (this.days() <= 0 ? 'event_available' : TONE_ICONS[this.tone()]));
  protected readonly text = computed(() => overdueText(this.days()));
}
