import { hasInboxTabParam, openingTab } from './inbox';

describe('caixa de entrada: aba de abertura', () => {
  it('abre na Fila quando "Minhas" não tem conversa aguardando e a fila tem', () => {
    expect(openingTab({ mine: 0, queue: 2 })).toBe('queue');
  });

  it('fica em "Minhas" quando há conversa sua aguardando, ou quando nada aguarda', () => {
    expect(openingTab({ mine: 1, queue: 5 })).toBe('mine');
    expect(openingTab({ mine: 0, queue: 0 })).toBe('mine');
    expect(openingTab(null)).toBe('mine');
  });

  it('aba válida na URL é escolha do usuário (a regra de abertura não troca)', () => {
    expect(hasInboxTabParam('mine')).toBe(true);
    expect(hasInboxTabParam('all')).toBe(true);
    expect(hasInboxTabParam(null)).toBe(false);
    expect(hasInboxTabParam('xyz')).toBe(false);
  });
});
