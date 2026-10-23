import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { UserNames } from './user-names';

describe('UserNames', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }));

  it('consulta o lookup leve em lote e devolve só id e nome', async () => {
    const http = TestBed.inject(HttpTestingController);
    const result = firstValueFrom(TestBed.inject(UserNames).resolve(['u-1', 'u-2']));
    const req = http.expectOne((r) => r.url === '/api/v1/users/display-names');
    expect(req.request.params.get('ids')).toBe('u-1,u-2');
    req.flush([{ id: 'u-1', name: 'Ana' }]);
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana' }]);
    http.verify();
  });

  it('sem ids não chama o servidor', async () => {
    expect(await firstValueFrom(TestBed.inject(UserNames).resolve([]))).toEqual([]);
    TestBed.inject(HttpTestingController).verify();
  });
});
