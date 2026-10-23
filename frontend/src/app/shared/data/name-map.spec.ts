import { Injector, runInInjectionContext, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { Option } from '../models/lookup';
import { NameSource, UNRESOLVED_NAME, nameMap } from './name-map';

describe('nameMap', () => {
  function source(labels: Record<string, string>) {
    const calls: string[][] = [];
    const src: NameSource = {
      resolve: (ids: string[]): Observable<Option[]> => {
        calls.push(ids);
        const id = ids[0];
        return labels[id] ? of([{ value: id, label: labels[id] }]) : throwError(() => new Error('404'));
      },
    };
    return { src, calls };
  }

  it('resolve cada id separadamente, usa reserva para inacessíveis e não repete ids conhecidos', async () => {
    const { src, calls } = source({ a: 'Credor A', b: 'Credor B' });
    const ids = signal<(string | null)[]>(['a', 'x', null, 'a']);
    const names = runInInjectionContext(TestBed.inject(Injector), () => nameMap(src, ids));

    expect(names.name('a')).toBe('…');
    expect(names.name(null)).toBe('');
    TestBed.tick();
    await Promise.resolve();
    TestBed.tick();

    expect(names.name('a')).toBe('Credor A');
    expect(names.name('x')).toBe(UNRESOLVED_NAME);
    expect(calls).toEqual([['a'], ['x']]);

    ids.set(['a', 'b']);
    TestBed.tick();
    await Promise.resolve();
    TestBed.tick();

    expect(names.name('b')).toBe('Credor B');
    expect(calls).toEqual([['a'], ['x'], ['b']]);
  });
});
