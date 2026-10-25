import { Component, computed, input, output } from '@angular/core';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../icon/icon';
import { documentType, formatDocument, maskDocument, onlyDigits } from '../../validation/document';

/**
 * CPF/CNPJ do devedor exibido **mascarado por padrão** (LGPD, minimização). Quando a tela
 * permite (`revealable`, usuário com `DEBTOR_SENSITIVE_VIEW`), o botão emite `reveal`: a tela
 * busca o valor completo no servidor — que registra `SENSITIVE_DATA_VIEWED` — e o informa em
 * `value` com `masked=false`. A interface nunca "desmascara" um valor que já tinha.
 */
@Component({
  selector: 'app-document',
  imports: [Icon, NgbTooltip],
  host: { class: 'app-document' },
  template: `
    <span class="app-numeric">
      @if (kind(); as k) {
        <span class="app-document__kind">{{ k }}</span>
      }
      {{ text() }}
    </span>
    @if (revealable() && masked()) {
      <button
        type="button"
        class="btn btn-sm btn-link app-document__reveal"
        (click)="reveal.emit()"
        [attr.aria-label]="'Exibir ' + (kind() ?? 'documento') + ' completo (acesso registrado)'"
        ngbTooltip="Exibir completo (acesso registrado)"
      >
        <app-icon name="eye" />
      </button>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 2px;
    }
    .app-document__kind {
      margin-right: 4px;
      font-size: var(--app-text-xs);
      font-weight: var(--bs-body-font-weight);
      color: var(--bs-secondary-color);
    }
    .app-document__reveal {
      --bs-btn-padding-x: 0.25rem;
      --bs-btn-padding-y: 0.125rem;
      min-width: 28px;
      min-height: 28px;
    }
  `,
})
export class DocumentDisplay {
  readonly value = input.required<string | null | undefined>();
  /** O valor recebido já está mascarado pelo servidor ou deve ser mascarado na exibição. */
  readonly masked = input(true);
  readonly revealable = input(false);
  readonly showKind = input(true);
  readonly reveal = output<void>();

  protected readonly kind = computed(() => {
    const value = this.value();
    if (!this.showKind() || !value) {
      return null;
    }
    return documentType(value) ?? (value.includes('/') ? 'CNPJ' : 'CPF');
  });

  protected readonly text = computed(() => {
    const value = this.value();
    if (!value) {
      return '—';
    }
    if (this.masked()) {
      return /[*•]/.test(value) ? value : maskDocument(value);
    }
    const digits = onlyDigits(value);
    return digits.length === 11 || digits.length === 14 ? formatDocument(digits) : value;
  });
}
