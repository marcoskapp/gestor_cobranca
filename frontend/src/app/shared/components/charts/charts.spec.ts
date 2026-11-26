import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BarChart } from './bar-chart';
import { columnPath, ColumnChart, niceCeil } from './column-chart';

const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;

describe('gráficos acessíveis', () => {
  it('eixo arredonda o máximo e coluna tem base reta', () => {
    expect(niceCeil(1100)).toBe(2000);
    expect(niceCeil(2400)).toBe(2500);
    expect(niceCeil(0)).toBe(1);
    expect(columnPath(10, 20, 24, 100)).toMatch(/^M10,100V24Q10,20 14,20H30Q34,20 34,24V100Z$/);
    expect(columnPath(10, 100, 24, 100)).toBe('');
  });

  it('barras: legenda com duas séries, valor escrito, rótulo acessível e tabela equivalente', async () => {
    const fixture = TestBed.createComponent(BarChart);
    fixture.componentRef.setInput('title', 'Acordado × pago');
    fixture.componentRef.setInput('summary', 'Resumo do gráfico');
    fixture.componentRef.setInput('series', [
      { name: 'Valor acordado', color: '--app-chart-1' },
      { name: 'Valor pago', color: '--app-chart-2' },
    ]);
    fixture.componentRef.setInput('rows', [{ key: 'HUMAN', label: 'Negociador', values: [1900, 900], display: ['R$ 1.900,00', 'R$ 900,00'], note: '47,4% pago' }]);
    fixture.detectChanges();
    const figure = el(fixture).querySelector('figure')!;
    expect(figure.getAttribute('aria-describedby')).toBeTruthy();
    expect(el(fixture).querySelector('.bc__legend')!.textContent).toContain('Valor pago');
    const bars = el(fixture).querySelectorAll('.bc__bar-line');
    expect(bars).toHaveLength(2);
    expect(bars[1].getAttribute('aria-label')).toBe('Negociador, Valor pago: R$ 900,00 (Observação: 47,4% pago)');
    expect(bars[0].getAttribute('tabindex')).toBe('0');
    (bars[1] as HTMLElement).dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    expect(el(fixture).querySelector('.bc__tip')!.textContent).toContain('R$ 900,00');
    el(fixture).querySelector<HTMLButtonElement>('.bc__toggle')!.click();
    fixture.detectChanges();
    const table = el(fixture).querySelector('table')!;
    expect(table.textContent).toContain('Negociador');
    expect(table.textContent).toContain('R$ 1.900,00');
    expect(table.textContent).toContain('47,4% pago');
  });

  it('barras vazias mostram estado sem dados', () => {
    const fixture = TestBed.createComponent(BarChart);
    fixture.componentRef.setInput('title', 'x');
    fixture.componentRef.setInput('summary', 'y');
    fixture.componentRef.setInput('series', [{ name: 's', color: '--app-chart-1' }]);
    fixture.componentRef.setInput('rows', []);
    fixture.detectChanges();
    expect(el(fixture).querySelector('[role="status"]')!.textContent).toContain('Sem dados');
  });

  it('colunas por período com rótulo do último valor e tabela', () => {
    const fixture = TestBed.createComponent(ColumnChart);
    fixture.componentRef.setInput('title', 'Recuperado por mês');
    fixture.componentRef.setInput('summary', 'Resumo');
    fixture.componentRef.setInput('series', { name: 'Recuperado líquido', color: '--app-chart-1' });
    fixture.componentRef.setInput('rows', [
      { key: '2026-05', label: 'mai/26', values: [400], display: ['R$ 400,00'] },
      { key: '2026-06', label: 'jun/26', values: [1100], display: ['R$ 1.100,00'] },
    ]);
    fixture.detectChanges();
    const cols = el(fixture).querySelectorAll('g[role="img"]');
    expect(cols).toHaveLength(2);
    expect(cols[1].getAttribute('aria-label')).toBe('jun/26: R$ 1.100,00');
    expect(el(fixture).querySelector('.cc__value')!.textContent).toBe('R$ 1.100,00');
    expect([...el(fixture).querySelectorAll('.cc__tick')].map((t) => t.textContent!.replace(/\s/g, ' '))).toContain('R$ 2 mil');
    el(fixture).querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('table')!.textContent).toContain('mai/26');
  });

  it('colunas sem linhas mostram o texto de vazio escolhido pela tela', () => {
    const fixture = TestBed.createComponent(ColumnChart);
    fixture.componentRef.setInput('title', 'Recuperado por demonstrativo');
    fixture.componentRef.setInput('summary', 'Resumo');
    fixture.componentRef.setInput('series', { name: 'Recuperado', color: '--app-chart-1' });
    fixture.componentRef.setInput('rows', []);
    fixture.detectChanges();
    expect(el(fixture).querySelector('[role="status"]')!.textContent).toContain('Sem dados no período');
    fixture.componentRef.setInput('emptyText', 'Nenhum demonstrativo enviado ainda.');
    fixture.detectChanges();
    expect(el(fixture).querySelector('[role="status"]')!.textContent).toBe('Nenhum demonstrativo enviado ainda.');
  });

  it('colunas de índice pequeno (PSI) com marcas de 2 casas; o padrão continua com 1', () => {
    const fixture = TestBed.createComponent(ColumnChart);
    fixture.componentRef.setInput('title', 'PSI');
    fixture.componentRef.setInput('summary', 'Resumo');
    fixture.componentRef.setInput('series', { name: 'PSI', color: '--app-chart-1' });
    fixture.componentRef.setInput('rows', [{ key: 'a', label: '18/10', values: [0.08], display: ['0,080'] }]);
    fixture.componentRef.setInput('money', false);
    fixture.detectChanges();
    const ticks = () => [...el(fixture).querySelectorAll('.cc__tick')].map((t) => t.textContent);
    expect(ticks()).toEqual(['0', '0,1', '0,1']);
    fixture.componentRef.setInput('axisFractionDigits', 2);
    fixture.detectChanges();
    expect(ticks()).toEqual(['0', '0,05', '0,1']);
  });

  it('ranking: "Outros" por último abre a lista completa com busca, total e "Mostrar mais"; barra filtra o painel', async () => {
    const fixture = TestBed.createComponent(BarChart);
    const rows = Array.from({ length: 30 }, (_, i) => ({ key: 'c' + i, label: 'Credor ' + i, values: [300 - i], display: ['R$ ' + (300 - i)] }));
    fixture.componentRef.setInput('title', 'Carteira por credor');
    fixture.componentRef.setInput('summary', 'Resumo');
    fixture.componentRef.setInput('series', [{ name: 'Carteira', color: '--app-chart-1', format: (v: number) => 'R$ ' + v }]);
    fixture.componentRef.setInput('rows', rows);
    fixture.componentRef.setInput('limit', 10);
    fixture.componentRef.setInput('noun', { one: 'credor', many: 'credores' });
    fixture.componentRef.setInput('selectable', true);
    const picked: string[] = [];
    fixture.componentInstance.selected.subscribe((r) => picked.push(r.key));
    fixture.detectChanges();
    expect(el(fixture).querySelector('.bc__notice')!.textContent).toContain('Mostrando 10 de 30 credores');
    const items = el(fixture).querySelectorAll('.bc__row');
    expect(items).toHaveLength(11);
    expect(items[10].textContent).toContain('Outros (20 credores)');
    expect(el(fixture).querySelector('.bc__toggle')!.textContent).toContain('Ver todos (30)');
    const first = items[0].querySelector<HTMLButtonElement>('button.bc__select')!;
    expect(first.getAttribute('aria-label')).toContain('Filtrar o painel por este item.');
    first.click();
    expect(picked).toEqual(['c0']);
    // "Outros" abre a lista completa (não filtra)
    items[10].querySelector<HTMLButtonElement>('button.bc__select')!.click();
    fixture.detectChanges();
    expect(picked).toEqual(['c0']);
    expect(el(fixture).querySelectorAll('tbody tr')).toHaveLength(25);
    expect(el(fixture).querySelector('tfoot')!.textContent).toContain('Total (30 credores)');
    expect(el(fixture).querySelector('app-show-more')!.textContent).toContain('Mostrando 25 de 30 credores');
    el(fixture).querySelector<HTMLButtonElement>('app-show-more button')!.click();
    fixture.detectChanges();
    expect(el(fixture).querySelectorAll('tbody tr')).toHaveLength(30);
    expect(el(fixture).querySelector('app-show-more button')).toBeNull();
    // ordem padrão anunciada; o 1º clique na coluna já ordenada inverte (não "não faz nada")
    const sorted = el(fixture).querySelector<HTMLElement>('th[aria-sort="descending"]')!;
    expect(sorted.textContent).toContain('Carteira');
    sorted.querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('th[aria-sort="ascending"]')!.textContent).toContain('Carteira');
  });
});
