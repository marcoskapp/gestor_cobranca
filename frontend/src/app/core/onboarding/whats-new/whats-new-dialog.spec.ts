import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../auth/auth.service';
import { provideAppLocale } from '../../config/app-locale';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { ReleaseNoteItem } from '../onboarding.models';
import { WhatsNewDialog } from './whats-new-dialog';

describe('WhatsNewDialog — "Conhecer" só para a tela que a pessoa abre', () => {
  function canOpen(permissions: string[], route: string): boolean {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [WhatsNewDialog],
      providers: [
        provideAppLocale(),
        provideRouter([]),
        { provide: DialogRef, useValue: { close: () => undefined } },
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser({ permissions })), hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)) },
        },
      ],
    });
    const dialog = TestBed.createComponent(WhatsNewDialog).componentInstance as unknown as { canOpen(item: ReleaseNoteItem): boolean };
    return dialog.canOpen({ text: 'Novidade', route });
  }

  it('rota com seção (#) vale pela tela', () => {
    expect(canOpen(['DEBTOR_MANAGE'], '/organizacao/configuracoes#contatos-recebidos')).toBe(true);
    expect(canOpen(['ORG_SETTINGS_MANAGE'], '/organizacao/configuracoes#outra-secao')).toBe(true);
    expect(canOpen(['USER_VIEW'], '/organizacao/configuracoes#contatos-recebidos')).toBe(false);
  });

  it('seção registrada no menu exige a permissão da seção', () => {
    expect(canOpen(['ORG_SETTINGS_MANAGE'], '/organizacao/configuracoes')).toBe(true);
    expect(canOpen(['ORG_SETTINGS_MANAGE'], '/organizacao/configuracoes#contatos-recebidos')).toBe(false);
  });
});
