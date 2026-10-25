import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { campaign, flushAll, page, panel, segment, tick } from '../../../../testing/campaign-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CampaignView } from '../data/campaign.models';
import { CampaignDetail } from './campaign-detail';

const COUNT = { cases: 1500, debtors: 1234, openBalance: 2845123.55, evaluatedAt: '2026-10-23T12:00:00Z' };

describe('CampaignDetail', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);

  beforeEach(() => {
    permissions.set(['CAMPAIGN_VIEW', 'CAMPAIGN_MANAGE', 'CAMPAIGN_EXECUTE', 'EXPERIMENT_MANAGE']);
    TestBed.configureTestingModule({
      imports: [CampaignDetail],
      providers: [
        provideAppLocale(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser()),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(current: CampaignView): Promise<ComponentFixture<CampaignDetail>> {
    const fixture = TestBed.createComponent(CampaignDetail);
    fixture.componentRef.setInput('id', current.id);
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      flushAll(http, {
        [`/api/v1/campaign/campaigns/${current.id}`]: current,
        '/api/v1/campaign/segments/s-1': segment(),
        '/api/v1/campaign/segments': page([segment()]),
        '/api/v1/creditors/c-1': { id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' },
      });
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('ativar mostra quantos devedores serão alcançados antes de confirmar', async () => {
    const fixture = await render(campaign());
    const confirm = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    button(fixture, 'Ativar')!.click();
    const count = http.expectOne((r) => r.url === '/api/v1/campaign/segments/s-1/count');
    expect(count.request.params.get('creditorId')).toBe('c-1');
    count.flush(COUNT);
    await tick();
    const data = confirm.mock.calls[0][0];
    expect(data.title).toBe('Ativar campanha');
    expect(data.details?.find((d) => d.label === 'Devedores alcançados')?.value).toContain('até 1.234 devedores');
    expect(data.warning).toContain('grupo COBRANCA');
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/campaigns/cp-1/activate').flush({ campaignId: 'cp-1', status: 'ACTIVE', affected: 0 });
    fixture.detectChanges();
    await tick();
    http.expectOne('/api/v1/campaign/campaigns/cp-1').flush(campaign({ status: 'ACTIVE' }));
  });

  it('sem régua publicada a ativação fica indisponível', async () => {
    const fixture = await render(campaign({ publishedCadenceId: null, publishedCadenceVersion: null }));
    expect(button(fixture, 'Ativar')?.disabled).toBe(true);
    expect(el(fixture).textContent).toContain('Sem régua publicada');
  });

  it('encerrar exige motivo e a palavra ENCERRAR, com as inscrições afetadas', async () => {
    const fixture = await render(campaign({ status: 'ACTIVE', activatedAt: '2026-10-22T12:00:00Z' }));
    expect(button(fixture, 'Pausar')).toBeTruthy();
    const confirm = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of('Campanha substituída'));
    button(fixture, 'Encerrar')!.click();
    http.expectOne((r) => r.url === '/api/v1/campaign/segments/s-1/count').flush(COUNT);
    http.expectOne('/api/v1/campaign/campaigns/cp-1/dashboard').flush(panel());
    await tick();
    const data = confirm.mock.calls[0][0];
    expect(data).toMatchObject({ reason: 'required', confirmationText: 'ENCERRAR', destructive: true });
    // em andamento = ativas + pausadas + com falha + controle
    expect(data.details?.find((d) => d.label === 'Inscrições encerradas')?.value).toBe('286');
    const finish = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/campaigns/cp-1/finish');
    expect(finish.request.body).toEqual({ note: 'Campanha substituída' });
    finish.flush({ campaignId: 'cp-1', status: 'FINISHED', affected: 286 });
    fixture.detectChanges();
    await tick();
    http.expectOne('/api/v1/campaign/campaigns/cp-1').flush(campaign({ status: 'FINISHED' }));
  });

  it('sem CAMPAIGN_EXECUTE não há ações do ciclo; sem CAMPAIGN_MANAGE os parâmetros ficam somente leitura', async () => {
    permissions.set(['CAMPAIGN_VIEW']);
    const fixture = await render(campaign({ status: 'PAUSED', pausedAt: '2026-10-22T12:00:00Z', pauseNote: 'Revisão do template' }));
    expect(button(fixture, 'Reativar')).toBeUndefined();
    expect(button(fixture, 'Encerrar')).toBeUndefined();
    expect(button(fixture, 'Salvar parâmetros')).toBeUndefined();
    expect(el(fixture).textContent).toContain('Revisão do template');
    const name = el(fixture).querySelector<HTMLInputElement>('input[formcontrolname="name"]')!;
    expect(name.disabled).toBe(true);
  });
});
