import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { ConfigLink } from './config-link';

describe('ConfigLink (atalho para a tela de configuração)', () => {
  let permissions: string[];

  function render(): HTMLElement {
    TestBed.configureTestingModule({
      imports: [ConfigLink],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { user: signal(fakeUser()), hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)) } },
      ],
    });
    const f = TestBed.createComponent(ConfigLink);
    f.componentRef.setInput('route', '/negociacao/alcadas');
    f.componentRef.setInput('label', 'Gerenciar perfis de alçada');
    f.componentRef.setInput('permissions', ['AUTHORITY_MANAGE']);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  it('com a permissão da tela, abre a configuração em nova aba e avisa o leitor de tela', () => {
    permissions = ['AUTHORITY_MANAGE'];
    const a = render().querySelector('a')!;
    expect(a.getAttribute('href')).toBe('/negociacao/alcadas');
    expect(a.getAttribute('target')).toBe('_blank');
    expect(a.getAttribute('rel')).toBe('noopener');
    expect(a.textContent).toContain('(abre em nova aba)');
  });

  it('sem a permissão, o atalho não aparece', () => {
    permissions = [];
    expect(render().querySelector('a')).toBeNull();
  });
});
