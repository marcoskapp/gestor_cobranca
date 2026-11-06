import { button, page, render, setup, text } from '../../../../testing/component-harness';
import { RetentionPolicyView } from '../data/compliance.models';
import { RetentionPage } from './retention';

const policy: RetentionPolicyView = {
  category: 'DEBTOR_INACTIVE',
  retentionMonths: 60,
  action: 'ANONYMIZE',
  enabled: true,
  executableHere: true,
  updatedAt: '2026-10-01T12:00:00Z',
};

describe('RetentionPage', () => {
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
