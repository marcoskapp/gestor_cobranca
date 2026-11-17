import { matchesSearch, normalizeSearch } from './text-search';

describe('busca local por texto', () => {
  it('normaliza acento, maiúscula e espaços', () => {
    expect(normalizeSearch('  São   PAULO ')).toBe('sao paulo');
    expect(normalizeSearch('Crédito Ação Ç')).toBe('credito acao c');
    expect(normalizeSearch(null)).toBe('');
    expect(normalizeSearch(undefined)).toBe('');
  });

  it('casa trecho em qualquer posição, sem diferenciar acento nem maiúscula', () => {
    const term = normalizeSearch('CARTAO');
    expect(matchesSearch('Cartão 2026', term)).toBe(true);
    expect(matchesSearch('Novo cartão de crédito', term)).toBe(true);
    expect(matchesSearch('Banco Alfa', term)).toBe(false);
    expect(matchesSearch('Banco Alfa', normalizeSearch('alfá'))).toBe(true);
  });

  it('termo vazio casa com tudo; texto vazio só com termo vazio', () => {
    expect(matchesSearch('qualquer', '')).toBe(true);
    expect(matchesSearch(null, '')).toBe(true);
    expect(matchesSearch(null, 'a')).toBe(false);
  });
});
