import { page, render, setup, text } from '../../../../testing/component-harness';
import { AuditTrail } from './audit-trail';

describe('AuditTrail', () => {
  const respond = (url: string) => (url.endsWith('/audit') ? page([]) : undefined);
  const labels = (el: HTMLElement) => [...el.querySelectorAll('label.form-label')].map((l) => l.textContent?.trim());

  it('com consulta de usuários e de credores, filtra por autor e por credor', async () => {
    const { http } = setup(AuditTrail, ['AUDIT_VIEW', 'USER_VIEW', 'CREDITOR_VIEW']);
    const fixture = await render(AuditTrail, http, respond);
    expect(labels(fixture.nativeElement)).toEqual(expect.arrayContaining(['Autor', 'Credor']));
  });

  it('sem USER_VIEW nem CREDITOR_VIEW (ex.: administrador da plataforma), os seletores não aparecem', async () => {
    const { http, notifications } = setup(AuditTrail, ['AUDIT_VIEW']);
    const fixture = await render(AuditTrail, http, respond);
    expect(labels(fixture.nativeElement)).not.toContain('Autor');
    expect(labels(fixture.nativeElement)).not.toContain('Credor');
    expect(text(fixture)).toContain('Id da entidade');
    expect(notifications.error).toEqual([]);
    http.expectNone((r) => r.url.includes('/users') || r.url.includes('/creditors'));
  });
});
