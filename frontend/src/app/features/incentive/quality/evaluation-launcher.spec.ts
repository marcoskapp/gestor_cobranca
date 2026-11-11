import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { NotificationService } from '../../../core/notification/notification.service';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { EvaluationLauncher, EvaluationTarget, attendanceDate } from './evaluation-launcher';

describe('EvaluationLauncher ("Avaliar este atendimento")', () => {
  let permissions: string[];
  let http: HttpTestingController;
  const open = vi.fn();
  const info = vi.fn();
  const target: EvaluationTarget = {
    userId: 'u-2',
    userName: 'Carla',
    referenceType: 'CALL',
    referenceId: '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b',
    evaluatedOn: '2026-11-07',
    label: 'chamada feita em 07/11/2026 14:32',
  };

  beforeEach(() => {
    permissions = ['QUALITY_EVALUATE'];
    open.mockReset().mockReturnValue({ afterClosed: () => of(undefined) });
    info.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user: signal(fakeUser({ id: 'u-1' })), hasPermission: (p: string) => permissions.includes(p) } },
        { provide: DialogService, useValue: { open } },
        { provide: NotificationService, useValue: { info, success: vi.fn(), error: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const EVALUABLE = '/api/v1/incentive/quality/evaluable-users';

  /** Carrega o escopo de quem avalia (o recurso dispara no próximo ciclo). */
  async function launcherWith(scope: { all: boolean; userIds: string[] }): Promise<EvaluationLauncher> {
    const launcher = TestBed.inject(EvaluationLauncher);
    TestBed.tick();
    http.expectOne(EVALUABLE).flush(scope);
    await TestBed.inject(ApplicationRef).whenStable();
    return launcher;
  }

  it('só para quem avalia e nunca o próprio atendimento (nem atendimento sem pessoa)', async () => {
    const launcher = await launcherWith({ all: true, userIds: [] });
    expect(launcher.canEvaluate('u-2')).toBe(true);
    expect(launcher.canEvaluate('u-1')).toBe(false);
    expect(launcher.canEvaluate(null)).toBe(false);
    permissions = [];
    expect(launcher.canEvaluate('u-2')).toBe(false);
  });

  it('supervisor: só quem está nas equipes dele (o gestor ou outra equipe ficam sem "Avaliar")', async () => {
    const launcher = await launcherWith({ all: false, userIds: ['u-2', 'u-3'] });
    expect(launcher.canEvaluate('u-2')).toBe(true);
    expect(launcher.canEvaluate('u-gestor')).toBe(false);
  });

  it('antes da resposta do servidor (ou se ela falhar), não oferece "Avaliar"', async () => {
    const launcher = TestBed.inject(EvaluationLauncher);
    expect(launcher.canEvaluate('u-2')).toBe(false);
    TestBed.tick();
    http.expectOne(EVALUABLE).flush(null, { status: 500, statusText: 'erro' });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(launcher.canEvaluate('u-2')).toBe(false);
  });

  it('abre o formulário já preenchido com o atendimento; sem formulário ativo, avisa onde criar', async () => {
    const launcher = await launcherWith({ all: true, userIds: [] });
    launcher.open(target).subscribe();
    http.expectOne('/api/v1/incentive/quality/forms').flush([{ id: 'f-1', name: 'Padrão', description: null, active: false, criteria: [] }]);
    expect(info).toHaveBeenCalledWith(expect.stringContaining('Nenhum formulário de monitoria ativo'));
    expect(open).not.toHaveBeenCalled();

    const done = new Promise<void>((resolve) => launcher.open(target).subscribe({ complete: () => resolve() }));
    http.expectOne('/api/v1/incentive/quality/forms').flush([{ id: 'f-2', name: 'Padrão', description: null, active: true, criteria: [] }]);
    await done;
    expect(open).toHaveBeenCalledTimes(1);
    const options = open.mock.calls[0][1];
    expect(options.data.prefill).toEqual(target);
    expect(options.data.forms.map((f: { id: string }) => f.id)).toEqual(['f-2']);
  });

  it('data do atendimento no fuso de operação', () => {
    // 01:30 UTC de 8/11 ainda é 7/11 em São Paulo
    expect(attendanceDate('2026-11-08T01:30:00Z')).toBe('2026-11-07');
  });
});
