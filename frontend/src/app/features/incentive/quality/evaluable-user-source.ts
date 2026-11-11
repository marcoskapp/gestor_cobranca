import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { IncentiveAssignees } from '../data/incentive-assignees';
import { EvaluationLauncher } from './evaluation-launcher';

/**
 * Negociadores que a pessoa logada pode avaliar — seletor da monitoria sem atendimento de origem (decisão 1075) e da
 * ocorrência de conduta. A lista vem do servidor (`GET /quality/evaluable-users/search`) pela mesma regra que ele
 * aplica ao gravar: negociador habilitado, no escopo de quem avalia (equipe ou todos) e nunca a própria pessoa. Só o
 * nome (sem e-mail) e sem exigir `USER_VIEW`. Fica fora do `EvaluationLauncher` para as telas de atendimento não
 * carregarem a busca.
 */
@Injectable({ providedIn: 'root' })
export class EvaluableUserSource implements EntitySource {
  private readonly auth = inject(AuthService);
  private readonly launcher = inject(EvaluationLauncher);
  private readonly source = inject(IncentiveAssignees).source('EVALUATION');

  search(q: string): Observable<Option[]> {
    return this.auth.hasPermission(Permission.QUALITY_EVALUATE) ? this.source.search(q) : of([]);
  }

  resolve(ids: string[]): Observable<Option[]> {
    return this.auth.hasPermission(Permission.QUALITY_EVALUATE) ? this.source.resolve(ids) : of([]);
  }

  emptyReason(): string | null {
    const scope = this.launcher.scopeSnapshot();
    return scope && !scope.all
      ? 'Nenhum negociador da sua equipe com esse nome (você não avalia a si mesmo).'
      : 'Nenhum negociador com esse nome (você não avalia a si mesmo).';
  }
}
