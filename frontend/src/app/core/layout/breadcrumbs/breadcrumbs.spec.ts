import { buildBreadcrumbs } from './breadcrumbs';

describe('buildBreadcrumbs', () => {
  it('tela inicial não tem trilha', () => {
    expect(buildBreadcrumbs('/', 'Início')).toEqual([]);
  });

  it('item do menu: início → seção → tela atual (sem link)', () => {
    expect(buildBreadcrumbs('/admin/usuarios?q=ana', 'Usuários')).toEqual([
      { label: 'Início', route: '/' },
      { label: 'Administração' },
      { label: 'Usuários' },
    ]);
  });

  it('detalhe: a tela de lista vira link e o título da rota fecha a trilha', () => {
    expect(buildBreadcrumbs('/organizacao/credores/123', 'Credor')).toEqual([
      { label: 'Início', route: '/' },
      { label: 'Organização' },
      { label: 'Credores', route: '/organizacao/credores' },
      { label: 'Credor' },
    ]);
  });

  it('áreas fora do menu usam o nome da área', () => {
    expect(buildBreadcrumbs('/conta/sessoes', 'Minhas sessões')).toEqual([
      { label: 'Início', route: '/' },
      { label: 'Minha conta' },
      { label: 'Minhas sessões' },
    ]);
  });
});
