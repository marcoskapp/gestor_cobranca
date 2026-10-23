import { Injectable, inject } from '@angular/core';
import { Observable, map, of, shareReplay } from 'rxjs';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { NameSource } from '../../../shared/data/name-map';
import { Option } from '../../../shared/models/lookup';
import { TeamsApi } from '../../admin/data/teams.api';
import { UsersApi } from '../../admin/data/users.api';
import { QueuesApi } from './collection.api';

/** Equipes (lista curta, carregada uma vez por tela que a injeta). */
@Injectable({ providedIn: 'root' })
export class TeamLookup implements EntitySource, NameSource {
  private readonly api = inject(TeamsApi);
  private cache: Observable<Option[]> | null = null;

  private all(): Observable<Option[]> {
    this.cache ??= this.api.list().pipe(
      map((teams) => teams.map((t): Option => ({ value: t.id, label: t.active ? t.name : `${t.name} (inativa)` }))),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.cache;
  }

  search(q: string): Observable<Option[]> {
    const term = q.trim().toLowerCase();
    return this.all().pipe(map((list) => list.filter((o) => !term || o.label.toLowerCase().includes(term))));
  }

  resolve(ids: string[]): Observable<Option[]> {
    return ids.length ? this.all().pipe(map((list) => ids.map((id) => list.find((o) => o.value === id) ?? { value: id, label: 'Indisponível' }))) : of([]);
  }

  /** Recarrega após mudanças nas equipes. */
  invalidate(): void {
    this.cache = null;
  }
}

/** Nomes de filas para listas (a listagem completa já vem ordenada por prioridade). */
@Injectable({ providedIn: 'root' })
export class QueueNames implements NameSource {
  private readonly api = inject(QueuesApi);
  private cache: Observable<Option[]> | null = null;

  /** Filas visíveis ao usuário, para seletores. */
  all(): Observable<Option[]> {
    this.cache ??= this.api.list().pipe(
      map((list) => list.map((q): Option => ({ value: q.id, label: q.active ? q.name : `${q.name} (inativa)` }))),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.cache;
  }

  resolve(ids: string[]): Observable<Option[]> {
    return ids.length ? this.all().pipe(map((list) => ids.map((id) => list.find((o) => o.value === id) ?? { value: id, label: 'Indisponível' }))) : of([]);
  }

  invalidate(): void {
    this.cache = null;
  }
}

/** Nomes de usuários (operadores, autores) para listas. */
@Injectable({ providedIn: 'root' })
export class UserNames implements NameSource {
  private readonly api = inject(UsersApi);

  resolve(ids: string[]): Observable<Option[]> {
    return ids.length === 1 ? this.api.get(ids[0]).pipe(map((u) => [{ value: u.id, label: u.name }])) : of(ids.map((id) => ({ value: id, label: '…' })));
  }
}
