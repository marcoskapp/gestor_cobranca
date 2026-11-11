import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideAppLocale } from '../../../core/config/app-locale';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { button, page, render, setup, text } from '../../../../testing/component-harness';
import { RetentionPolicyView } from '../data/compliance.models';
import { RetentionPage } from './retention';
import { RetentionPolicyForm } from './retention-dialogs';

const policy: RetentionPolicyView = {
  category: 'DEBTOR_INACTIVE',
  action: 'ANONYMIZE',
  unit: 'MONTHS',
  retentionAmount: 60,
  defaultAmount: null,
  minAmount: 1,
  maxAmount: 1200,
  enabled: true,
  executableHere: true,
  configured: true,
  updatedAt: '2026-10-01T12:00:00Z',
};

/** Categoria do portal, em dias, com padrão do sistema e ainda sem prazo da assessoria. */
const portalDefault: RetentionPolicyView = {
  category: 'PORTAL_ACCESS_CHALLENGES',
  action: 'DELETE_RECORD',
  unit: 'DAYS',
  retentionAmount: null,
  defaultAmount: 30,
  minAmount: 1,
  maxAmount: 365,
  enabled: true,
  executableHere: false,
  configured: false,
  updatedAt: null,
};

describe('RetentionPage', () => {
  it('sem nenhuma política, cada categoria aparece com "Configurar" (sem simular nem executar)', async () => {
    const { http } = setup(RetentionPage, ['RETENTION_MANAGE', 'DATA_SUBJECT_REQUEST_MANAGE']);
    const unconfigured: RetentionPolicyView = { ...policy, retentionAmount: null, enabled: false, updatedAt: null, configured: false };
    const fixture = await render(RetentionPage, http, (url) => {
      if (url.endsWith('/compliance/retention/policies')) return [unconfigured];
      if (url.endsWith('/compliance/retention/runs')) return page([]);
      return undefined;
    });
    expect(text(fixture)).toContain('Não configurada');
    expect(button(fixture, 'Configurar')).toBeDefined();
    expect(button(fixture, 'Simular')).toBeUndefined();
    expect(button(fixture, 'Executar')).toBeUndefined();
  });

  it('categoria com padrão do sistema mostra o padrão em dias e "Configurar", sem simular nem executar', async () => {
    const { http } = setup(RetentionPage, ['RETENTION_MANAGE', 'DATA_SUBJECT_REQUEST_MANAGE']);
    const fixture = await render(RetentionPage, http, (url) => {
      if (url.endsWith('/compliance/retention/policies')) return [portalDefault];
      if (url.endsWith('/compliance/retention/runs')) return page([]);
      return undefined;
    });
    expect(text(fixture)).toContain('Portal: pedidos de acesso vencidos');
    expect(text(fixture)).toContain('Padrão do sistema: 30 dias');
    expect(text(fixture)).not.toContain('Não configurada');
    expect(button(fixture, 'Configurar')).toBeDefined();
    expect(button(fixture, 'Simular')).toBeUndefined();
  });

  it('prazo da assessoria diferente do padrão mostra os dois', async () => {
    const { http } = setup(RetentionPage, ['RETENTION_MANAGE']);
    const configured: RetentionPolicyView = { ...portalDefault, retentionAmount: 10, configured: true, updatedAt: '2026-10-01T12:00:00Z' };
    const fixture = await render(RetentionPage, http, (url) => {
      if (url.endsWith('/compliance/retention/policies')) return [configured];
      if (url.endsWith('/compliance/retention/runs')) return page([]);
      return undefined;
    });
    expect(text(fixture)).toContain('10 dias');
    expect(text(fixture)).toContain('Padrão do sistema: 30 dias');
  });

  const respond = (url: string) => {
    if (url.endsWith('/compliance/retention/policies')) return [policy];
    if (url.endsWith('/compliance/retention/runs')) return page([]);
    return undefined;
  };

  it('com a permissão de atendimento a titulares, "Executar" aparece ao lado de "Simular"', async () => {
    const { http } = setup(RetentionPage, ['RETENTION_MANAGE', 'DATA_SUBJECT_REQUEST_MANAGE']);
    const fixture = await render(RetentionPage, http, respond);
    expect(button(fixture, 'Simular')).toBeDefined();
    expect(button(fixture, 'Executar')).toBeDefined();
    expect(button(fixture, 'Executar')!.disabled).toBe(false);
  });

  it('sem a permissão de atendimento a titulares, "Executar" fica visível, desabilitado e explicado, sem chamar o servidor', async () => {
    const { http } = setup(RetentionPage, ['RETENTION_MANAGE']);
    const fixture = await render(RetentionPage, http, respond);
    expect(button(fixture, 'Simular')).toBeDefined();
    const execute = button(fixture, 'Executar')!;
    expect(execute).toBeDefined();
    expect(execute.getAttribute('aria-disabled')).toBe('true');
    expect(text(fixture)).toContain('Para executar o descarte é preciso ter a permissão de atender solicitações de titulares (LGPD).');

    execute.click();
    fixture.detectChanges();
    expect(document.querySelector('ngb-tooltip-window')?.textContent).toContain('atender solicitações de titulares');
    expect(document.querySelector('.modal'), 'nenhuma confirmação de execução').toBeNull();
    http.expectNone((r) => r.method === 'POST');
  });
});

describe('RetentionPolicyForm', () => {
  const close = vi.fn();
  let http: HttpTestingController;

  const open = (data: RetentionPolicyView) => {
    TestBed.configureTestingModule({
      imports: [RetentionPolicyForm],
      providers: [
        provideAppLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DIALOG_DATA, useValue: data },
        { provide: DialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(RetentionPolicyForm);
    fixture.detectChanges();
    return fixture;
  };

  afterEach(() => {
    http.verify();
    close.mockReset();
  });

  it('"Usar o padrão" apaga o prazo da assessoria no servidor (não grava o número do padrão como prazo próprio)', () => {
    const fixture = open({ ...portalDefault, retentionAmount: 10, configured: true, updatedAt: '2026-10-01T12:00:00Z' });
    button(fixture, 'Usar o padrão')!.click();
    const req = http.expectOne((r) => r.method === 'DELETE' && r.url.endsWith('/compliance/retention/policies/PORTAL_ACCESS_CHALLENGES'));
    http.expectNone((r) => r.method === 'PUT');
    req.flush(portalDefault);
    expect(close).toHaveBeenCalledWith(portalDefault);
  });

  it('sem prazo da assessoria não oferece "Usar o padrão" (já segue o padrão)', () => {
    const fixture = open(portalDefault);
    expect(button(fixture, 'Usar o padrão')).toBeUndefined();
    expect(text(fixture)).toContain('Padrão do sistema: 30 dias');
  });

  it('categoria executada aqui lembra que a base legal do credor prevalece', () => {
    const fixture = open(policy);
    expect(text(fixture)).toContain('Prazos maiores exigidos pela base legal de cada credor prevalecem.');
    expect(button(fixture, 'Usar o padrão')).toBeUndefined();
  });
});
