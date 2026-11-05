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

  it('sem a permissão de atendimento a titulares, "Executar" não aparece (nem desabilitado)', async () => {
    const { http } = setup(RetentionPage, ['RETENTION_MANAGE']);
    const fixture = await render(RetentionPage, http, respond);
    expect(button(fixture, 'Simular')).toBeDefined();
    expect(button(fixture, 'Executar')).toBeUndefined();
    expect(text(fixture)).not.toContain('Exige a permissão');
  });
});
