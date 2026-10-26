import { NAVIGATION, activeNavRoute } from './navigation';

describe('activeNavRoute', () => {
  const routes = ['/', '/conversas', '/conversas/templates', '/conversas/canais', '/pagamentos', '/pagamentos/instrucoes'];

  it('marca só o item mais específico', () => {
    expect(activeNavRoute('/conversas/templates', routes)).toBe('/conversas/templates');
    expect(activeNavRoute('/conversas', routes)).toBe('/conversas');
    expect(activeNavRoute('/pagamentos/instrucoes/42', routes)).toBe('/pagamentos/instrucoes');
  });

  it('tela interna sem item próprio marca o item pai', () => {
    expect(activeNavRoute('/conversas/0b6c?aba=historico', routes)).toBe('/conversas');
    expect(activeNavRoute('/pagamentos/123#x', routes)).toBe('/pagamentos');
  });

  it('início só corresponde a ele mesmo e prefixo exige segmento inteiro', () => {
    expect(activeNavRoute('/', routes)).toBe('/');
    expect(activeNavRoute('/?q=1', routes)).toBe('/');
    expect(activeNavRoute('/conversasx', routes)).toBeNull();
    expect(activeNavRoute('/desconhecida', routes)).toBeNull();
  });

  it('o menu real não tem rotas repetidas', () => {
    const all = NAVIGATION.flatMap((s) => s.items.map((i) => i.route));
    expect(new Set(all).size).toBe(all.length);
  });
});
