import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { ATTENDANCE_IDLE_MS, ATTENDANCE_LIMIT, AttendanceService, emptyDraft } from './attendance.service';

describe('AttendanceService (atendimento por caso)', () => {
  const user = signal<ReturnType<typeof fakeUser> | null>(fakeUser({ id: 'u-1' }));
  let service: AttendanceService;
  const at = (minutes: number) => new Date(Date.UTC(2026, 10, 7, 13, minutes));

  beforeEach(() => {
    user.set(fakeUser({ id: 'u-1' }));
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { user } }] });
    service = TestBed.inject(AttendanceService);
    TestBed.tick();
  });

  it('voltar ao mesmo caso retoma o início da contagem (a duração não é subestimada)', () => {
    const start = service.begin('k-1', at(0));
    expect(service.begin('k-1', at(5))).toBe(start);
    expect(service.startedAt('k-1')).toEqual(at(0));
  });

  it('atendimento parado há mais de 30 minutos recomeça a contagem ao voltar (o rascunho fica)', () => {
    expect(ATTENDANCE_IDLE_MS).toBe(30 * 60 * 1000);
    service.begin('k-1', at(0));
    service.saveDraft('k-1', { ...emptyDraft(), note: 'x' }, at(2));
    service.touch('k-1', at(3)); // saiu da estação às 13:03
    expect(service.begin('k-1', at(33))).toEqual(at(0)); // 30 min exatos: continua
    service.touch('k-1', at(33));
    expect(service.begin('k-1', at(64))).toEqual(at(64)); // 31 min parado: recomeça
    expect(service.draft('k-1')?.note).toBe('x');
  });

  it('abrir outro caso encerra a contagem do anterior, mas guarda o rascunho dele', () => {
    service.begin('k-1', at(0));
    service.saveDraft('k-1', { ...emptyDraft(), note: 'Ligar depois das 18h', channel: 'WHATSAPP' });
    service.begin('k-2', at(3));
    expect(service.startedAt('k-1')).toBeNull();
    expect(service.draft('k-1')?.note).toBe('Ligar depois das 18h');
    // de volta ao primeiro caso, a contagem recomeça (o tempo no outro caso não conta)
    expect(service.begin('k-1', at(8))).toEqual(at(8));
    expect(service.channel('k-1')).toBe('WHATSAPP');
  });

  it('interação registrada limpa o rascunho (mantém o canal) e reinicia a contagem', () => {
    service.begin('k-1', at(0));
    service.saveDraft('k-1', { ...emptyDraft(), channel: 'EMAIL', tabulationId: 'tb-1', note: 'x' });
    expect(service.recorded('k-1', at(4))).toEqual(at(4));
    expect(service.draft('k-1')).toEqual({ ...emptyDraft(), channel: 'EMAIL' });
    expect(service.startedAt('k-1')).toEqual(at(4));
  });

  it('aba e acordo formalizado ficam no caso; devolver à fila descarta tudo', () => {
    service.begin('k-1', at(0));
    service.setTab('k-1', 5);
    service.setAgreement('k-1', 'a-1');
    expect(service.tab('k-1')).toBe(5);
    expect(service.agreement('k-1')).toBe('a-1');
    service.discard('k-1');
    expect(service.tab('k-1')).toBeNull();
    expect(service.draft('k-1')).toBeNull();
    expect(service.begin('k-1', at(9))).toEqual(at(9));
  });

  it('guarda no máximo os casos mais recentes e esquece tudo ao trocar de usuário', () => {
    for (let i = 0; i <= ATTENDANCE_LIMIT; i++) {
      service.saveDraft(`k-${i}`, { ...emptyDraft(), note: `n${i}` });
    }
    expect(service.draft('k-0')).toBeNull();
    expect(service.draft(`k-${ATTENDANCE_LIMIT}`)?.note).toBe(`n${ATTENDANCE_LIMIT}`);

    user.set(null);
    TestBed.tick();
    expect(service.draft(`k-${ATTENDANCE_LIMIT}`)).toBeNull();
  });
});
