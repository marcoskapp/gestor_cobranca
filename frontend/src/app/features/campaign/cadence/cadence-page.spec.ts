import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { cadence, campaign, flushAll, messageStep, page, templateView, tick } from '../../../../testing/campaign-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideLocale } from '../../../core/config/locale.config';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CadenceDefinition, CadenceView } from '../data/campaign.models';
import { CadencePage } from './cadence-page';

const DRAFT = cadence({ id: 'cd-2', versionNumber: 2, status: 'DRAFT', publishedAt: null });

interface Exposed {
  draft: () => CadenceDefinition;
  edited: () => void;
}

describe('CadencePage', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);

  beforeEach(() => {
    permissions.set(['CAMPAIGN_VIEW', 'CAMPAIGN_MANAGE', 'TEMPLATE_MANAGE']);
    TestBed.configureTestingModule({
      imports: [CadencePage],
      providers: [
        provideLocale(),
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

  async function render(versions: CadenceView[], hasDraft = true): Promise<ComponentFixture<CadencePage>> {
    const fixture = TestBed.createComponent(CadencePage);
    fixture.componentRef.setInput('id', 'cp-1');
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      flushAll(http, {
        '/api/v1/campaign/campaigns/cp-1': campaign({ hasDraft }),
        '/api/v1/campaign/campaigns/cp-1/cadences': versions,
        '/api/v1/communication/templates': page([templateView(), templateView({ id: 't-2', code: 'LEMBRETE', channel: 'SMS', variables: [] })]),
        '/api/v1/teams': [{ id: 'tm-1', name: 'Equipe Varejo', businessUnitId: null, supervisorId: null, active: true, memberIds: [] }],
      });
    }
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('rascunho: etapas em sequência, validação contra o template publicado e salvamento', async () => {
    const fixture = await render([DRAFT, cadence()]);
    expect(el(fixture).querySelector('[data-tour="cadence-steps"]')?.textContent).toContain('Enviar mensagem · WhatsApp · COBRANCA');
    expect(el(fixture).textContent).toContain('Seg a Sex, 09:00 às 12:00');
    expect(button(fixture, 'Publicar versão')?.disabled).toBe(false);

    const c = fixture.componentInstance as unknown as Exposed;
    c.draft().steps[0].action.variables = { nome: 'DEBTOR_FIRST_NAME' };
    c.edited();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('variáveis obrigatórias sem origem: valor');
    expect(button(fixture, 'Publicar versão')).toBeUndefined();

    button(fixture, 'Salvar rascunho')!.click();
    const put = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/campaign/campaigns/cp-1/cadences/draft');
    expect(put.request.body.steps[0].action.variables).toEqual({ nome: 'DEBTOR_FIRST_NAME' });
    expect(put.request.body.steps[0].window).toEqual({ start: '09:00:00', end: '12:00:00', days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'] });
    put.flush(DRAFT);
    fixture.detectChanges();
    await tick();
    flushAll(http, { '/api/v1/campaign/campaigns/cp-1/cadences': [DRAFT, cadence()], '/api/v1/campaign/campaigns/cp-1': campaign({ hasDraft: true }) });
  });

  it('adicionar etapa sugere código livre e destino de resposta inexistente impede salvar', async () => {
    const fixture = await render([DRAFT, cadence()]);
    button(fixture, 'Adicionar etapa')!.click();
    fixture.detectChanges();
    const c = fixture.componentInstance as unknown as Exposed;
    expect(c.draft().steps.map((s) => s.code)).toEqual(['D0', 'ETAPA_2']);
    c.draft().steps[1].onReply = 'GOTO_STEP';
    c.draft().steps[1].onReplyStepCode = 'NAO_EXISTE';
    c.edited();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Etapa 2 (ETAPA_2): escolha a etapa de destino da resposta.');
    expect(button(fixture, 'Salvar rascunho')?.disabled).toBe(true);
  });

  it('publicar exige digitar PUBLICAR e informa a versão retirada', async () => {
    const fixture = await render([DRAFT, cadence()]);
    const confirm = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    button(fixture, 'Publicar versão')!.click();
    const data = confirm.mock.calls[0][0];
    expect(data.confirmationText).toBe('PUBLICAR');
    expect(data.details).toEqual(expect.arrayContaining([{ label: 'Versão retirada', value: 'versão 1' }, { label: 'Canais', value: 'WhatsApp' }]));
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/campaigns/cp-1/cadences/draft/publish').flush({ ...DRAFT, status: 'PUBLISHED' });
    fixture.detectChanges();
    await tick();
    flushAll(http, { '/api/v1/campaign/campaigns/cp-1/cadences': [{ ...DRAFT, status: 'PUBLISHED' }], '/api/v1/campaign/campaigns/cp-1': campaign() });
  });

  it('versão publicada é somente leitura e "Nova versão" copia para um rascunho', async () => {
    const fixture = await render([cadence({ definition: { defaultOnReply: 'STOP', defaultOnReplyStepCode: null, replyTeamId: null, steps: [messageStep(), messageStep({ code: 'D3', waitMinutes: 4320, window: null })] } })], false);
    const inputs = [...el(fixture).querySelectorAll<HTMLInputElement>('app-step-editor input')];
    expect(inputs.length).toBeGreaterThan(0);
    expect(inputs.every((i) => i.disabled)).toBe(true);
    expect(el(fixture).textContent).toContain('Aguarda 3 dias');
    button(fixture, 'Nova versão')!.click();
    fixture.detectChanges();
    button(fixture, 'Salvar rascunho')!.click();
    const put = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/campaign/campaigns/cp-1/cadences/draft');
    expect(put.request.body.steps.map((s: { code: string }) => s.code)).toEqual(['D0', 'D3']);
    put.flush(DRAFT);
    fixture.detectChanges();
    await tick();
    flushAll(http, { '/api/v1/campaign/campaigns/cp-1/cadences': [DRAFT, cadence()], '/api/v1/campaign/campaigns/cp-1': campaign({ hasDraft: true }) });
  });

  it('sem CAMPAIGN_MANAGE não edita e sem acesso a templates não os consulta', async () => {
    permissions.set(['CAMPAIGN_VIEW']);
    const fixture = await render([DRAFT, cadence()]);
    expect(button(fixture, 'Publicar versão')).toBeUndefined();
    expect(button(fixture, 'Adicionar etapa')).toBeUndefined();
    expect(button(fixture, 'Nova versão')).toBeUndefined();
  });
});
