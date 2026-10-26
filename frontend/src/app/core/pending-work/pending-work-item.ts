import { Component, computed, input } from '@angular/core';
import { Icon } from '../../shared/components/icon/icon';
import { badgeText, itemMeta, severityText } from './pending-work.format';
import { PendingWorkItem } from './pending-work.models';

/**
 * Conteúdo de uma pendência (contagem, rótulo, área, prazo e idade), usado dentro do link para a tela
 * de ação no resumo da barra superior, na tela inicial e na lista completa. O destaque de vencido ou
 * "vence hoje" tem ícone e texto, nunca só cor (WCAG 1.4.1).
 */
@Component({
  selector: 'app-pending-work-item',
  imports: [Icon],
  template: `
    <span class="pwi__count" [class]="'pwi__count--' + tone()" aria-hidden="true">{{ count() }}</span>
    <span class="pwi__text">
      <span class="pwi__label"><span class="visually-hidden">{{ item().count }} — </span>{{ item().label }}</span>
      <span class="pwi__meta">{{ meta() }}</span>
      @if (showHint() && item().hint) {
        <span class="pwi__hint">{{ item().hint }}</span>
      }
    </span>
    @if (severity(); as s) {
      <span class="pwi__flag badge rounded-pill border" [class]="flagClasses()">
        <app-icon [name]="item().severity === 'OVERDUE' ? 'exclamation-triangle' : 'clock'" />
        {{ s }}
      </span>
    }
  `,
  styles: `
    :host {
      display: flex;
      align-items: center;
      gap: 12px;
      min-width: 0;
      width: 100%;
    }
    .pwi__count {
      display: grid;
      flex: none;
      place-items: center;
      min-width: 40px;
      height: 40px;
      padding: 0 6px;
      border-radius: var(--bs-border-radius);
      background: var(--bs-primary-bg-subtle);
      color: var(--bs-primary-text-emphasis);
      font-size: 1rem;
      font-weight: var(--bs-font-weight-semibold, 500);
      font-variant-numeric: tabular-nums;
    }
    .pwi__count--danger {
      background: var(--bs-danger-bg-subtle);
      color: var(--bs-danger-text-emphasis);
    }
    .pwi__count--warning {
      background: var(--bs-warning-bg-subtle);
      color: var(--bs-warning-text-emphasis);
    }
    .pwi__text {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }
    .pwi__label {
      color: var(--bs-emphasis-color);
      font-weight: var(--bs-font-weight-medium, 500);
      overflow-wrap: anywhere;
    }
    .pwi__meta,
    .pwi__hint {
      color: var(--bs-secondary-color);
      font-size: var(--app-text-xs, 0.75rem);
    }
    .pwi__flag {
      display: inline-flex;
      flex: none;
      align-items: center;
      gap: 4px;
      font-weight: var(--bs-body-font-weight);
    }
    @media (max-width: 599.98px) {
      :host {
        flex-wrap: wrap;
      }
      .pwi__flag {
        margin-left: 52px;
      }
    }
  `,
})
export class PendingWorkItemView {
  readonly item = input.required<PendingWorkItem>();
  /** Mostra a orientação da ação (tela inicial e lista completa). */
  readonly showHint = input(false);

  protected readonly count = computed(() => badgeText(this.item().count));
  protected readonly meta = computed(() => itemMeta(this.item()));
  /** Só o destaque de vencido ou de hoje vira selo; o próximo prazo fica na linha de apoio. */
  protected readonly severity = computed(() => (this.item().severity === 'NORMAL' ? null : severityText(this.item())));
  protected readonly flagClasses = computed(
    () => `pwi__flag badge rounded-pill border bg-${this.tone()}-subtle text-${this.tone()}-emphasis border-${this.tone()}-subtle`,
  );
  protected readonly tone = computed(() =>
    this.item().severity === 'OVERDUE' ? 'danger' : this.item().severity === 'DUE_TODAY' ? 'warning' : 'neutral',
  );
}
