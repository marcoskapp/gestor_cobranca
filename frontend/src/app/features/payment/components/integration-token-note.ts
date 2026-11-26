import { Component, computed, input, model } from '@angular/core';
import { Permission } from '../../../core/auth/permission';
import { ConfigLink } from '../../../shared/components/config-link/config-link';
import { Icon } from '../../../shared/components/icon/icon';
import { PaymentView } from '../data/payment.models';
import { integrationTokenInactive, integrationTokenInactiveReason, integrationTokenText } from '../data/payment.rules';

let nextId = 0;

/**
 * Aviso do pagamento registrado pela integração do credor (UX-05): qual token informou e, com destaque, o motivo de
 * a integração estar inativa (acesso revogado, pausado pela plataforma ou expirado, ou integração não encontrada). Com `confirmable` (decisão de aprovação), o token inativo exige
 * a confirmação explícita de quem aprova (o servidor confere de novo no momento da decisão). O atalho "Abrir token"
 * só aparece para quem tem `PUBLIC_API_TOKEN_VIEW`.
 */
@Component({
  selector: 'app-integration-token-note',
  imports: [ConfigLink, Icon],
  template: `
    @if (payment().integration; as i) {
      <div class="alert d-flex gap-2 align-items-start" [class.alert-info]="!inactive()" [class.alert-warning]="inactive()"
           role="note" data-testid="integration-token-note">
        <app-icon [name]="inactive() ? 'exclamation-triangle' : 'info-circle'" />
        <div>
          <span>Informado pela integração do credor · {{ label() }}.</span>
          @if (reason(); as r) {
            <span class="d-block">{{ r }}{{ confirmable() ? ' Confira com o credor antes de aprovar.' : '' }}</span>
          }
          @if (i.tokenName && i.tokenState !== 'NOT_FOUND') {
            &ngsp;<app-config-link [route]="'/integracoes/tokens/' + i.tokenId" label="Abrir token" [permissions]="tokenPermissions" />
          }
          @if (confirmable() && inactive()) {
            <div class="form-check mt-2 mb-0">
              <input class="form-check-input" type="checkbox" [id]="checkId" [checked]="confirmed()"
                     (change)="confirmed.set($any($event.target).checked)" data-testid="integration-token-confirm" />
              <label class="form-check-label" [for]="checkId">Conferi com o credor e quero aprovar mesmo com a integração inativa</label>
            </div>
          }
        </div>
      </div>
    }
  `,
})
export class IntegrationTokenNote {
  readonly payment = input.required<PaymentView>();
  /** Mostra a confirmação explícita (aprovação) quando o token não está ativo; sem ela, o aviso não fala em aprovar. */
  readonly confirmable = input(false);
  readonly confirmed = model(false);
  protected readonly tokenPermissions = [Permission.PUBLIC_API_TOKEN_VIEW] as const;
  protected readonly checkId = `itn-confirm-${++nextId}`;
  protected readonly inactive = computed(() => integrationTokenInactive(this.payment()));
  protected readonly reason = computed(() => {
    const i = this.payment().integration;
    return i ? integrationTokenInactiveReason(i) : null;
  });
  protected readonly label = computed(() => {
    const i = this.payment().integration;
    return i ? integrationTokenText(i) : '';
  });
}
