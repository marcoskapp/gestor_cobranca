import { signal } from '@angular/core';
import { of } from 'rxjs';
import { button, render, setup, text } from '../../../../testing/component-harness';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { SheetContact } from '../../collection/data/collection.models';
import { CallDialog } from './call-dialog';
import { TelephonySession } from './telephony-session';
import { agentView, callView } from '../../../../testing/telephony-fixtures';

const contacts: SheetContact[] = [
  { contactId: 'ct-1', type: 'PHONE', maskedValue: '(11) 9****-1234', label: 'MOBILE', whatsapp: true, status: 'VALID', priority: 1 },
  { contactId: 'ct-2', type: 'PHONE', maskedValue: '(11) 3****-5678', label: 'HOME', whatsapp: false, status: 'INVALID', priority: 2 },
];

describe('CallDialog (clique para ligar)', () => {
  const close = vi.fn();
  const session = { agent: signal(agentView({ status: 'AVAILABLE' })), configured: signal(true), busy: signal(false), dial: vi.fn(), login: vi.fn(), available: vi.fn() };

  function create() {
    return setup(CallDialog, ['TELEPHONY_USE'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { caseId: 'k-1', debtorName: 'Ana Paula Ribeiro', contacts } },
      { provide: DialogRef, useValue: { close } },
      { provide: TelephonySession, useValue: session },
    ]);
  }

  const gate = (allowed: boolean) => (url: string) =>
    url.endsWith('/cases/k-1/contact-availability')
      ? { caseId: 'k-1', evaluatedAt: '', caseEligible: true, caseReasons: [], channels: [{ channel: 'PHONE', allowed, reasons: allowed ? [] : ['OUTSIDE_WINDOW'], nextAllowedAt: allowed ? null : '2026-10-25T11:00:00Z', contactId: 'ct-1' }] }
      : undefined;

  afterEach(() => vi.clearAllMocks());

  it('mostra a decisão do porteiro por telefone antes de discar (telefone inválido fica de fora)', async () => {
    const { http } = create();
    const fixture = await render(CallDialog, http, gate(false));
    const t = text(fixture);
    expect(t).toContain('(11) 9****-1234');
    expect(t).not.toContain('(11) 3****-5678');
    expect(t).toContain('Aguardar');
  });

  it('negado no clique: mostra os motivos, não fecha e não registra tentativa', async () => {
    session.dial.mockReturnValue(of(callView({ status: 'FAILED', failureCode: 'CONTACT_DENIED', denialReasons: ['OUTSIDE_WINDOW'], nextAllowedAt: '2026-10-25T11:00:00Z' })));
    const { http } = create();
    const fixture = await render(CallDialog, http, gate(true));
    button(fixture, 'Ligar')!.click();
    fixture.detectChanges();
    expect(session.dial).toHaveBeenCalledWith('k-1', 'ct-1', expect.any(String));
    expect(text(fixture)).toContain('não permitiu a ligação agora');
    expect(text(fixture)).toContain('Nenhuma tentativa foi registrada');
    expect(close).not.toHaveBeenCalled();
  });

  it('em pausa: explica e oferece ficar disponível; o botão Ligar fica desabilitado', async () => {
    session.agent.set(agentView({ status: 'PAUSED', pauseReason: 'BREAK' }));
    const { http } = create();
    const fixture = await render(CallDialog, http, gate(true));
    expect(text(fixture)).toContain('Você está em pausa');
    expect(button(fixture, 'Ficar disponível')).toBeTruthy();
    expect([...fixture.nativeElement.querySelectorAll('.modal-footer button')].at(-1).disabled).toBe(true);
    session.agent.set(agentView({ status: 'AVAILABLE' }));
  });
});
