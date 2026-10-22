import { Injectable, inject } from '@angular/core';
import { forkJoin, map, of } from 'rxjs';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { UserView } from './admin.models';
import { UsersApi } from './users.api';

/** Fonte de usuários ativos para seletores (equipes, filtros de governança). */
@Injectable({ providedIn: 'root' })
export class UserLookup implements EntitySource {
  private readonly api = inject(UsersApi);

  search(q: string) {
    return this.api.search({ q, status: 'ACTIVE' }, { page: 0, size: 20 }).pipe(map((page) => page.content.map(userOption)));
  }

  resolve(ids: string[]) {
    return ids.length ? forkJoin(ids.map((id) => this.api.get(id).pipe(map(userOption)))) : of<Option[]>([]);
  }
}

export function userOption(u: UserView): Option {
  return { value: u.id, label: `${u.name} (${u.email})` };
}
