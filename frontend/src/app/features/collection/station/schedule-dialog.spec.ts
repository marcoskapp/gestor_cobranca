import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NgbConfig } from '@ng-bootstrap/ng-bootstrap';
import { provideBootstrapComponentDefaults, provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { TaskView } from '../data/collection.models';
import { addDays, zonedNow } from '../data/collection.rules';
import { ScheduleDialog, ScheduleDialogData } from './schedule-dialog';

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('ScheduleDialog (DialogService + ngbDatepicker)', () => {
  let http: HttpTestingController;
  let dialog: DialogService;
  const notifications = { success: vi.fn(), info: vi.fn(), error: vi.fn() };

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [provideBootstrapDefaults(), provideBootstrapComponentDefaults(), provideHttpClient(), provideHttpClientTesting(), { provide: NotificationService, useValue: notifications }],
    });
    TestBed.inject(NgbConfig).animation = false;
    http = TestBed.inject(HttpTestingController);
    dialog = TestBed.inject(DialogService);
  });

  afterEach(() => {
    dialog.closeAll();
    http.verify();
  });

  async function open(data: ScheduleDialogData): Promise<{ result: TaskView[]; el: HTMLElement }> {
    const result: TaskView[] = [];
    dialog.open<ScheduleDialog, ScheduleDialogData, TaskView>(ScheduleDialog, { data }).afterClosed().subscribe((t) => t && result.push(t));
    await tick();
    TestBed.inject(ApplicationRef).tick();
    return { result, el: document.querySelector('ngb-modal-window app-schedule-dialog') as HTMLElement };
  }

  function type(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('change'));
    input.dispatchEvent(new Event('blur'));
  }

  it('data digitada em dd/mm/aaaa vira LocalDateTime no horário de Brasília', async () => {
    const { result, el } = await open({ caseId: 'k-1' });
    const day = addDays(zonedNow().date, 2);
    const [y, m, d] = day.split('-');
    type(el.querySelector<HTMLInputElement>('input[placeholder="dd/mm/aaaa"]')!, `${d}/${m}/${y}`);
    type(el.querySelector<HTMLInputElement>('input[type="time"]')!, '14:30');
    TestBed.inject(ApplicationRef).tick();
    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();

    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/collection/cases/k-1/tasks');
    expect(req.request.body).toMatchObject({ type: 'CALLBACK', dueAt: `${day}T14:30:00`, assigneeUserId: null, note: null });
    req.flush({ id: 't-1', caseId: 'k-1', dueAtLocal: `${day}T14:30:00` });
    await tick();
    expect(result.map((t) => t.id)).toEqual(['t-1']);
    expect(notifications.success).toHaveBeenCalled();
  });

  it('reagendamento preenche a data atual do agendamento no campo', async () => {
    const day = addDays(zonedNow().date, 5);
    const [y, m, d] = day.split('-');
    const { el } = await open({ caseId: 'k-1', task: { id: 't-9', caseId: 'k-1', type: 'CALLBACK', dueAtLocal: `${day}T09:15:00` } as TaskView });
    await tick();
    TestBed.inject(ApplicationRef).tick();
    expect(el.querySelector<HTMLInputElement>('input[placeholder="dd/mm/aaaa"]')!.value).toBe(`${d}/${m}/${y}`);
    expect(el.querySelector<HTMLInputElement>('input[type="time"]')!.value).toBe('09:15');
  });
});
