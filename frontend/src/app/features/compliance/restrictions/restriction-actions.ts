import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, filter, switchMap, tap } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { RestrictionsApi } from '../data/compliance.api';
import { RESTRICTION_TYPE_LABELS } from '../data/compliance.labels';
import { RestrictionView } from '../data/compliance.models';
import { RestrictionForm, RestrictionFormData } from './restriction-form/restriction-form';

/** Ações sobre restrições compartilhadas pela lista, pela ficha do devedor e pelas solicitações LGPD. */
// TRANSITÓRIO (migração para Bootstrap): fornecido nas rotas do shell (shell.routes.ts), não na raiz,
// para usar o MatDialog e os padrões do Material daquele injetor. Volta a `providedIn: 'root'`
// quando passar a usar o DialogService.
@Injectable()
export class RestrictionActions {
  private readonly api = inject(RestrictionsApi);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  create(data: RestrictionFormData): Observable<RestrictionView> {
    return this.dialog
      .open<RestrictionForm, RestrictionFormData, RestrictionView>(RestrictionForm, { data, width: '720px' })
      .afterClosed()
      .pipe(filter((saved): saved is RestrictionView => !!saved));
  }

  /** Revogação com motivo obrigatório; os contatos voltam a ser avaliados pelas demais regras. */
  revoke(restriction: RestrictionView, scope: string): Observable<RestrictionView> {
    return this.confirm
      .confirm({
        title: 'Revogar restrição de contato',
        message: 'Os contatos deixam de ser bloqueados por esta restrição. Outras restrições e a política de contato continuam valendo.',
        confirmLabel: 'Revogar',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo da revogação',
        details: [
          { label: 'Tipo', value: RESTRICTION_TYPE_LABELS[restriction.type] },
          { label: 'Alcance', value: scope },
          { label: 'Motivo original', value: restriction.reason },
        ],
        warning: restriction.type === 'OPT_OUT' || restriction.type === 'LEGAL_BLOCK'
          ? 'Revogue descadastros e bloqueios jurídicos somente com pedido formal do titular ou ordem que os substitua.'
          : undefined,
      })
      .pipe(
        switchMap((reason) => this.api.revoke(restriction.id, reason)),
        tap(() => this.notifications.success('Restrição revogada.')),
      );
  }
}
