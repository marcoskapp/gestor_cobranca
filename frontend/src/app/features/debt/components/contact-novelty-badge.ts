import { Component, computed, input } from '@angular/core';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { contactNoveltyBadge } from '../data/contact-novelty';
import { ContactReceiptView } from '../data/debt.models';

/**
 * Selo "Novo" do contato recebido numa nova carga para devedor já existente (ficha do devedor e painel do caso).
 * Ícone e texto (nunca só cor); a dica (recebimento, tentativa, confirmação do credor) também vai para o leitor de
 * tela. Contato só reenviado pelo credor, sem novidade, mostra apenas um ícone discreto com a dica. É um rótulo, não
 * um botão: foco pelo teclado só para abrir a dica.
 *
 * ```html
 * <app-contact-novelty-badge [receipt]="contact.receipt" />
 * ```
 */
@Component({
  selector: 'app-contact-novelty-badge',
  imports: [Icon, NgbTooltip],
  template: `
    @if (badge(); as b) {
      @if (b.label) {
        <span class="app-status app-status--info cnb-badge" tabindex="0" [ngbTooltip]="b.hint" data-tour="contact-novelty-badge"
          ><app-icon class="app-status__icon" name="stars" />{{ b.label }}<span class="visually-hidden">. {{ b.hint }}</span></span
        >
      } @else {
        <span class="cnb-confirmed app-muted" tabindex="0" [ngbTooltip]="b.hint"
          ><app-icon name="check2-circle" /><span class="visually-hidden">{{ b.hint }}</span></span
        >
      }
    }
  `,
  styles: `
    :host { display: inline-flex; align-items: center; }
    .cnb-badge { white-space: nowrap; }
    .cnb-confirmed { display: inline-flex; align-items: center; }
  `,
})
export class ContactNoveltyBadge {
  readonly receipt = input<ContactReceiptView | null | undefined>(null);

  protected readonly badge = computed(() => contactNoveltyBadge(this.receipt()));
}
