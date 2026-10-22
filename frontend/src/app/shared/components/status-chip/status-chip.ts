import { Component, computed, input } from '@angular/core';
import { StatusDomain, describeStatus } from '../../status/status';

/** Indicador de estado com texto (nunca só cor) e tom acessível. */
@Component({
  selector: 'app-status-chip',
  template: `<span class="app-status" [class]="'app-status--' + descriptor().tone">{{ descriptor().label }}</span>`,
})
export class StatusChip {
  readonly domain = input.required<StatusDomain>();
  readonly status = input.required<string | boolean | null | undefined>();
  protected readonly descriptor = computed(() => describeStatus(this.domain(), this.status()));
}
