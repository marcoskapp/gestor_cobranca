import { Injectable, inject } from '@angular/core';
import { Observable, filter, forkJoin, map, of, switchMap, take } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { UserLookup } from '../../admin/data/user.lookup';
import { EvaluationLauncher } from './evaluation-launcher';

const normalized = (text: string) =>
  text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .trim();

/**
 * Negociadores que a pessoa logada pode avaliar, para o seletor da monitoria sem atendimento de origem (decisão 1075).
 * Com visão total, busca usuários ativos (menos a própria pessoa); com escopo de equipe, parte dos membros informados
 * pelo servidor e filtra pelo nome — a busca paginada de usuários cortaria a equipe — mostrando só o nome (sem e-mail).
 * Fica fora do `EvaluationLauncher` para as telas de atendimento não carregarem a busca de usuários.
 */
@Injectable({ providedIn: 'root' })
export class EvaluableUserSource implements EntitySource {
  private readonly users = inject(UserLookup);
  private readonly names = inject(UserNames);
  private readonly auth = inject(AuthService);
  private readonly launcher = inject(EvaluationLauncher);

  search(q: string): Observable<Option[]> {
    if (!this.auth.hasPermission(Permission.QUALITY_EVALUATE)) {
      return of([]);
    }
    return this.launcher.scope$.pipe(
      filter((scope) => scope !== null),
      take(1),
      switchMap((scope) =>
        scope.all
          ? this.users.search(q).pipe(map((options) => options.filter((o) => this.launcher.canEvaluate(o.value))))
          : this.team([...scope.ids]).pipe(map((options) => options.filter((o) => normalized(o.label).includes(normalized(q ?? ''))))),
      ),
    );
  }

  resolve(ids: string[]): Observable<Option[]> {
    return this.users.resolve(ids);
  }

  emptyReason(): string | null {
    const scope = this.launcher.scopeSnapshot();
    return scope && !scope.all ? 'Nenhum negociador da sua equipe com esse nome (você não avalia a si mesmo).' : null;
  }

  /** Nomes dos membros da equipe, em lotes do limite do servidor. */
  private team(ids: string[]): Observable<Option[]> {
    if (!ids.length) {
      return of([]);
    }
    const batches: string[][] = [];
    for (let i = 0; i < ids.length; i += USER_NAMES_BATCH) {
      batches.push(ids.slice(i, i + USER_NAMES_BATCH));
    }
    return forkJoin(batches.map((b) => this.names.resolve(b))).pipe(
      map((parts) => parts.flat().sort((a, b) => a.label.localeCompare(b.label, 'pt-BR'))),
    );
  }
}
