import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Component } from '@angular/core';
import { fakeUser } from '../../../testing/auth-fixtures';
import { AuthService } from '../auth/auth.service';
import { DEFAULT_UI_STATE, UiState, UiStatePatch } from '../preferences/ui-state.models';
import { applyPatch } from '../preferences/ui-state.patch';
import { UiStateStore } from '../preferences/ui-state.store';
import { LATEST_RELEASE, RELEASE_NOTES_SCREEN } from './content/release-notes';
import { findTour } from './content/tours';
import { loadScreenManual } from './manual/manual-index';
import { OBJECTIVE_STEP_TITLE } from './manual/screen-tour';
import { TourDefinition } from './onboarding.models';
import { OnboardingService, WELCOME_TOUR } from './onboarding.service';
import { TourEngine } from './tour/tour-engine.service';

@Component({ template: '' })
class Blank {}

/** Dublê do store com a mesma semântica de aplicação de patches. */
class FakeStore {
  readonly state = signal<UiState>({ ...DEFAULT_UI_STATE });
  readonly ready = signal(false);
  update(patch: UiStatePatch): void {
    this.state.update((s) => applyPatch(s, patch));
  }
  recordTour(tourId: string, version: number, outcome: 'COMPLETED' | 'DISMISSED'): void {
    this.update({ tours: [{ tourId, version, outcome }] });
  }
  markScreenSeen(screenKey: string, version: number): void {
    this.update({ screens: [{ screenKey, version }] });
  }
  setChecklistItem(itemId: string, done: boolean): void {
    this.update({ checklist: [{ itemId, done }] });
  }
}

describe('OnboardingService', () => {
  let store: FakeStore;
  let service: OnboardingService;
  let router: Router;
  let start: ReturnType<typeof vi.fn>;
  const permissions = signal<string[]>(['USER_VIEW', 'USER_MANAGE', 'ORG_SETTINGS_MANAGE']);
  const user = signal(fakeUser({ mfaEnabled: false }));

  beforeEach(() => {
    store = new FakeStore();
    start = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', component: Blank }]),
        { provide: UiStateStore, useValue: store },
        { provide: TourEngine, useValue: { start, active: () => false } },
        {
          provide: AuthService,
          useValue: {
            user,
            hasAnyPermission: (ps: readonly string[]) => ps.length === 0 || ps.some((p) => permissions().includes(p)),
          },
        },
      ],
    });
    service = TestBed.inject(OnboardingService);
    router = TestBed.inject(Router);
  });

  async function visit(url: string): Promise<void> {
    await router.navigateByUrl(url);
    TestBed.tick();
  }

  function returning(state: Partial<UiState>): void {
    store.state.set({
      ...DEFAULT_UI_STATE,
      screens: { 'app.home': { version: 1, seenAt: 'x' }, [RELEASE_NOTES_SCREEN]: { version: LATEST_RELEASE, seenAt: 'x' } },
      tours: { [WELCOME_TOUR]: { version: 1, outcome: 'COMPLETED', recordedAt: 'x' } },
      ...state,
    });
  }

  it('aguarda o estado do servidor antes de decidir', async () => {
    await visit('/');
    expect(service.hint()).toBeNull();
    store.ready.set(true);
    TestBed.tick();
    expect(service.hint()?.kind).toBe('welcome');
  });

  it('primeiro acesso: convida para o tour de boas-vindas e marca as novidades como lidas', async () => {
    store.ready.set(true);
    await visit('/');
    expect(service.hint()?.kind).toBe('welcome');
    expect(store.state().screens[RELEASE_NOTES_SCREEN]?.version).toBe(LATEST_RELEASE);
    expect(service.unreadReleaseNotes()).toBe(false);

    service.acceptHint();
    expect(start).toHaveBeenCalledWith(expect.objectContaining({ id: WELCOME_TOUR }));
  });

  it('"agora não" registra o convite como dispensado e não volta a oferecer', async () => {
    store.ready.set(true);
    await visit('/');
    service.declineHint();
    expect(store.state().tours[WELCOME_TOUR]?.outcome).toBe('DISMISSED');
    await visit('/admin/usuarios');
    expect(service.hint()?.kind).toBe('tour');
  });

  it('tela ainda não navegada com tour: oferece o tour curto uma única vez', async () => {
    returning({});
    store.ready.set(true);
    await visit('/admin/usuarios');
    const hint = service.hint();
    expect(hint?.kind === 'tour' && hint.tour.id).toBe('admin.users');
    expect(store.state().screens['admin.users']?.version).toBe(1);

    await visit('/');
    await visit('/admin/usuarios');
    expect(service.hint()).toBeNull();
  });

  it('tela com versão nova: destaca as novidades desde a última versão vista', async () => {
    returning({ screens: { ...DEFAULT_UI_STATE.screens, 'admin.users': { version: 1, seenAt: 'x' } } });
    store.ready.set(true);
    const screens = await import('./content/screens');
    const original = screens.SCREENS.find((s) => s.key === 'admin.users');
    const guide = original as { version: number; changes?: unknown };
    const saved = { version: guide.version, changes: guide.changes };
    guide.version = 2;
    guide.changes = [{ version: 2, items: ['Novo filtro por perfil'] }];
    try {
      await visit('/admin/usuarios');
      const hint = service.hint();
      expect(hint?.kind).toBe('changes');
      expect(hint?.kind === 'changes' && hint.items).toEqual(['Novo filtro por perfil']);
      expect(store.state().screens['admin.users'].version).toBe(2);
    } finally {
      Object.assign(guide, saved);
    }
  });

  it('dicas desativadas: só registra a visita', async () => {
    returning({ hintsEnabled: false });
    store.ready.set(true);
    await visit('/admin/usuarios');
    expect(service.hint()).toBeNull();
    expect(store.state().screens['admin.users']?.version).toBe(1);
  });

  it('checklist do perfil: oculta itens sem rota no menu e conclui automaticamente', async () => {
    store.ready.set(true);
    await visit('/organizacao/configuracoes');
    const summary = service.checklist();
    expect(summary?.profile.id).toBe('admin');
    const ids = summary?.entries.map((e) => e.item.id);
    expect(ids).toContain('admin.users');
    expect(ids).not.toContain('admin.creditor'); // sem CREDITOR_MANAGE
    expect(summary?.entries.find((e) => e.item.id === 'admin.settings')?.done).toBe(true);
    expect(summary?.entries.find((e) => e.item.id === 'account.mfa')?.done).toBe(false);

    service.setChecklistItem('admin.users', true);
    expect(service.checklist()?.entries.find((e) => e.item.id === 'admin.users')?.done).toBe(true);
  });

  it('tour desta tela começa pelo objetivo (passo centralizado) e segue o tour próprio', async () => {
    returning({});
    store.ready.set(true);
    await visit('/admin/usuarios');
    expect(await service.startScreenTour()).toBe(true);
    const tour = start.mock.calls.at(-1)![0] as TourDefinition;
    expect(tour.id).toBe('admin.users');
    expect(tour.steps[0].anchor).toBeUndefined();
    expect(tour.steps[0].title).toBe(OBJECTIVE_STEP_TITLE);
    expect(tour.steps[0].body).toBe((await loadScreenManual('admin.users'))!.objective);
    expect(tour.steps.slice(1)).toEqual(findTour('admin.users')!.steps);
  });

  it('tela sem tour próprio: objetivo + onde ler mais, sem gravar no servidor', async () => {
    returning({});
    store.ready.set(true);
    await visit('/admin/unidades');
    expect(service.hasScreenHelp()).toBe(true);
    await service.startScreenTour();
    const tour = start.mock.calls.at(-1)![0] as TourDefinition;
    expect(tour.transient).toBe(true);
    expect(tour.steps.map((s) => s.anchor)).toEqual([undefined, 'help']);
  });

  it('aceitar o convite de tela usa o tour com o objetivo', async () => {
    returning({});
    store.ready.set(true);
    await visit('/admin/perfis');
    service.acceptHint();
    await vi.waitFor(() => expect(start).toHaveBeenCalled());
    const tour = start.mock.calls.at(-1)![0] as TourDefinition;
    expect(tour.id).toBe('admin.roles');
    expect(tour.steps[0].title).toBe(OBJECTIVE_STEP_TITLE);
  });

  it('tela desconhecida não tem tour nem manual', async () => {
    store.ready.set(true);
    await visit('/rota-inexistente');
    expect(service.hasScreenHelp()).toBe(false);
    expect(await service.startScreenTour()).toBe(false);
  });

  it('rever tours volta a oferecer convites já dispensados', async () => {
    returning({});
    store.ready.set(true);
    service.resetTours();
    expect(store.state().tours).toEqual({});
    await visit('/organizacao/credores');
    expect(service.hint()?.kind).toBe('welcome');
  });
});
