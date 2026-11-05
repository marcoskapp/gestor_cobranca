import { page, render, setup } from '../../../../testing/component-harness';
import { AccessLog } from './access-log';

describe('AccessLog', () => {
  const respond = (url: string) => (url.endsWith('/access-log') ? page([]) : undefined);
  const labels = (el: HTMLElement) => [...el.querySelectorAll('label.form-label')].map((l) => l.textContent?.trim());

  it('com USER_VIEW, filtra por usuário', async () => {
    const { http } = setup(AccessLog, ['ACCESS_LOG_VIEW', 'USER_VIEW']);
    const fixture = await render(AccessLog, http, respond);
    expect(labels(fixture.nativeElement)).toContain('Usuário');
  });

  it('sem USER_VIEW, o seletor de usuário não aparece', async () => {
    const { http } = setup(AccessLog, ['ACCESS_LOG_VIEW']);
    const fixture = await render(AccessLog, http, respond);
    expect(labels(fixture.nativeElement)).not.toContain('Usuário');
    http.expectNone((r) => r.url.includes('/users'));
  });
});
