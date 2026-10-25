import { TestBed } from '@angular/core/testing';
import { Subject, firstValueFrom } from 'rxjs';
import { callView } from '../../../../testing/telephony-fixtures';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { CallView } from '../data/telephony.models';
import { TelephonyLauncher } from './telephony-launcher';

describe('TelephonyLauncher', () => {
  const closed = new Subject<CallView | undefined>();
  const dialog = { open: vi.fn(() => ({ afterClosed: () => closed.asObservable() })) };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [{ provide: DialogService, useValue: dialog }] });
  });

  afterEach(() => vi.clearAllMocks());

  it('tabulação: diálogo que não fecha sem tabular e no máximo um por vez', async () => {
    const launcher = TestBed.inject(TelephonyLauncher);
    const first = firstValueFrom(launcher.tabulation({ call: callView() }));
    expect(await firstValueFrom(launcher.tabulation({ call: callView() }))).toBeUndefined();
    await vi.waitFor(() => expect(dialog.open).toHaveBeenCalledTimes(1));
    expect(dialog.open).toHaveBeenCalledWith(expect.any(Function), expect.objectContaining({ disableClose: true }));
    const done = callView({ tabulationCode: 'CALL_ANSWERED' });
    closed.next(done);
    expect(await first).toEqual(done);
  });

  it('ligar: abre o diálogo da ligação com os dados do caso', async () => {
    const launcher = TestBed.inject(TelephonyLauncher);
    const result = firstValueFrom(launcher.call({ caseId: 'k-1', debtorName: 'Ana', contacts: [] }));
    await vi.waitFor(() => expect(dialog.open).toHaveBeenCalledTimes(1));
    expect(dialog.open).toHaveBeenCalledWith(expect.any(Function), { data: { caseId: 'k-1', debtorName: 'Ana', contacts: [] } });
    closed.next(undefined);
    expect(await result).toBeUndefined();
  });
});
