import { Signal, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { HeldTitle, ReversalHoldsApi } from '../creditor-reported/reversal-holds.api';

/** Texto do selo e da dica (sem código interno). */
export const HELD_TITLE_BADGE = 'Estorno em conferência';
export const HELD_TITLE_HINT =
  'Um pagamento informado como recebido pelo credor foi estornado. Não cobre este valor até o financeiro conferir com o credor e liberar.';

/**
 * Títulos retidos do devedor ("Estorno em conferência", H07 parte 3), para o selo nas listas de títulos e no detalhe.
 * Só consulta quem vê títulos (`DEBTOR_VIEW` ou `CASE_VIEW`); erro ou sem permissão = nenhum selo. Chamar no contexto
 * de injeção do componente.
 */
export function heldTitles(debtorId: () => string | null | undefined): Signal<Map<string, HeldTitle>> {
  const api = inject(ReversalHoldsApi);
  const auth = inject(AuthService);
  const resource = rxResource({
    params: () => {
      const id = debtorId();
      return id && auth.hasAnyPermission([Permission.DEBTOR_VIEW, Permission.CASE_VIEW]) ? id : undefined;
    },
    stream: ({ params }) => api.heldTitles(params).pipe(catchError(() => of([] as HeldTitle[]))),
  });
  return computed(() => {
    const list = resource.hasValue() ? resource.value() : [];
    return new Map(list.map((h) => [h.titleId, h]));
  });
}
