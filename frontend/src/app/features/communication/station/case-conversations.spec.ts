import { page, render, setup, text } from '../../../../testing/component-harness';
import { ConversationView } from '../data/communication.models';
import { CaseConversations } from './case-conversations';

const conv = (o: Partial<ConversationView>): ConversationView =>
  ({
    id: 'cv-1',
    channel: 'WHATSAPP',
    contactMasked: '(11) 9****-1234',
    status: 'OPEN',
    ownerType: 'ASSISTANT',
    ownerUserId: null,
    requiredProfile: null,
    awaitingSince: null,
    waitingSeconds: null,
    unreadCount: 0,
    messageCount: 3,
    lastMessageAt: '2026-11-05T12:00:00Z',
    closedAt: null,
    ...o,
  }) as ConversationView;

describe('CaseConversations (aba Conversas da ficha)', () => {
  it('lista as conversas do caso em qualquer situação, com quem atende e atalho para a caixa de entrada', async () => {
    const { http } = setup(CaseConversations, ['INBOX_VIEW']);
    const urls: string[] = [];
    const fixture = await render(
      CaseConversations,
      http,
      (url, req) => {
        if (url.endsWith('/communication/conversations')) {
          urls.push(req.request.urlWithParams);
          return page([
            conv({ id: 'cv-1', unreadCount: 2, awaitingSince: '2026-11-05T12:00:00Z', waitingSeconds: 600 }),
            conv({ id: 'cv-2', channel: 'EMAIL', contactMasked: 'a***@exemplo.com.br', status: 'CLOSED', ownerType: 'USER', ownerUserId: 'u-1', messageCount: 1, closedAt: '2026-11-01T12:00:00Z' }),
          ]);
        }
        return undefined;
      },
      { caseId: 'k-1' },
    );
    expect(urls[0]).toContain('caseId=k-1');
    expect(urls[0]).toContain('status=ALL');
    const t = text(fixture);
    expect(t).toContain('Com o assistente');
    expect(t).toContain('aguardando há 10 min');
    expect(t).toContain('2 não lidas');
    expect(t).toContain('Encerrada');
    expect(t).toContain('1 mensagem');
    const links = [...(fixture.nativeElement as HTMLElement).querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/conversas/cv-1', '/conversas/cv-2']);
  });

  it('sem conversas: orienta a usar Mensagem', async () => {
    const { http } = setup(CaseConversations, ['INBOX_VIEW']);
    const fixture = await render(CaseConversations, http, (url) => (url.endsWith('/communication/conversations') ? page([]) : undefined), { caseId: 'k-1' });
    expect(text(fixture)).toContain('Nenhuma conversa neste caso.');
  });
});
