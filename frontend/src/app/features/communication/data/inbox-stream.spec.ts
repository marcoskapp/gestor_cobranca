import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../../core/auth/auth.service';
import { INBOX_STREAM_URL, InboxEvent, InboxStream, parseSse, toInboxEvent } from './inbox-stream';

function sseBody(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      chunks.forEach((c) => controller.enqueue(encoder.encode(c)));
    },
  });
}

describe('InboxStream (tempo real da caixa de entrada)', () => {
  it('separa mensagens SSE completas, ignora comentários e guarda o resto incompleto', () => {
    const { messages, rest } = parseSse('retry: 5000\nevent: ready\ndata: {}\n\n: ping\n\nevent:conversation\ndata:{"conversationId":"cv-1"}\n\nevent: conv');
    expect(messages).toEqual([
      { event: 'ready', data: '{}' },
      { event: 'conversation', data: '{"conversationId":"cv-1"}' },
    ]);
    expect(rest).toBe('event: conv');
    expect(toInboxEvent(messages[1])).toEqual({ kind: 'conversation', conversationId: 'cv-1' });
    expect(toInboxEvent({ event: 'resync', data: '{}' })).toEqual({ kind: 'resync' });
    expect(toInboxEvent(messages[0])).toBeNull();
    expect(toInboxEvent({ event: 'conversation', data: 'x' })).toBeNull();
  });

  it('autentica pelo cabeçalho (token fora da URL), repassa avisos e fica ao vivo', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(sseBody(['event: ready\ndata: {}\n\n', 'event: conversation\ndata: {"conversationId":"cv-9"}\n\n']), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchMock);
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { accessToken: () => 'tok-1', isTokenExpiring: () => false } }],
    });
    const stream = TestBed.inject(InboxStream);
    const received: InboxEvent[] = [];
    const sub = stream.events().subscribe((e) => received.push(e));
    await vi.waitFor(() => expect(received).toEqual([{ kind: 'conversation', conversationId: 'cv-9' }]));
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(INBOX_STREAM_URL);
    expect(url).not.toContain('tok-1');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer tok-1');
    sub.unsubscribe();
    expect(stream.live()).toBe(false);
    vi.unstubAllGlobals();
  });

  it('sem token (sessão ociosa) não conecta e segue fora do ar', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { accessToken: () => null, isTokenExpiring: () => false } }],
    });
    const stream = TestBed.inject(InboxStream);
    const sub = stream.events().subscribe();
    await new Promise((r) => setTimeout(r, 10));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(stream.live()).toBe(false);
    sub.unsubscribe();
    vi.unstubAllGlobals();
  });
});
