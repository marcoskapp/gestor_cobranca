import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { CredentialAction, credentialToken } from './credential-action';

function route(fragment: string | null, query: Record<string, string> = {}): ActivatedRoute {
  return { snapshot: { fragment, queryParamMap: convertToParamMap(query) } } as unknown as ActivatedRoute;
}

describe('CredentialAction', () => {
  it('lê o token do fragmento (#token=), que não vai ao servidor', () => {
    expect(credentialToken(route('token=abc123'))).toBe('abc123');
    expect(credentialToken(route(null))).toBeNull();
    expect(credentialToken(route(''))).toBeNull();
  });

  it('aceita links antigos (?token=) emitidos antes da mudança', () => {
    expect(credentialToken(route(null, { token: 'antigo' }))).toBe('antigo');
    expect(credentialToken(route('token=novo', { token: 'antigo' }))).toBe('novo');
  });

  it('remove o token do endereço logo ao abrir a tela', async () => {
    TestBed.configureTestingModule({
      imports: [CredentialAction],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: route('token=abc123') },
      ],
    });
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(CredentialAction);
    fixture.componentRef.setInput('purpose', 'reset');
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ fragment: undefined, replaceUrl: true }));
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Redefinir senha');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Link inválido');
  });
});
