import { decodeUtf8, parseCsv, previewStatement } from './statement-preview';

const HEADER = 'data;valor;tipo;id_transacao;referencia;descricao';

describe('prévia do extrato CSV', () => {
  it('resume o exemplo do layout: créditos, débitos, período e identificadores', () => {
    const p = previewStatement(
      [
        HEADER,
        '2026-10-20;171,05;C;E00000000202610201200abcdefghij1;9f1c0a2b3c4d5e6f708192a3b4c5d6e7;PIX RECEBIDO',
        '20/10/2026;100,00;C;DOC-123;;TED RECEBIDA',
        '2026-10-21;10,00;D;;;TARIFA',
        '',
      ].join('\r\n'),
    );
    expect(p.ok).toBe(true);
    expect(p.lines).toBe(3);
    expect(p.credits).toBe(2);
    expect(p.creditTotal).toBe(271.05);
    expect(p.debits).toBe(1);
    expect(p.withTransactionId).toBe(2);
    expect(p.withReference).toBe(1);
    expect(p.firstDate).toBe('2026-10-20');
    expect(p.lastDate).toBe('2026-10-21');
    expect(p.sample[1]).toEqual({ line: 3, date: '2026-10-20', amount: 100, credit: true, transactionId: 'DOC-123', reference: null, description: 'TED RECEBIDA' });
  });

  it('cabeçalho em qualquer ordem e caixa; colunas desconhecidas são ignoradas', () => {
    const p = previewStatement('VALOR;Data;Agencia\n50.5;2026-10-01;0001');
    expect(p.ok).toBe(true);
    expect(p.ignoredColumns).toEqual(['agencia']);
    expect(p.creditTotal).toBe(50.5);
  });

  it('aponta cada linha inválida como o servidor recusaria', () => {
    const p = previewStatement(
      [HEADER, '2026-02-30;10,00;C;;;', '2026-10-01;1.234,56;C;;;', '2026-10-01;0;C;;;', '2026-10-01;5;X;;;', '2026-10-01;5;C;id com espaço;;', '2026-10-01;5;C;;;=HYPERLINK("x")', ';5;C;;;'].join('\n'),
    );
    expect(p.ok).toBe(false);
    expect(p.issues.map((i) => i.line)).toEqual([2, 3, 4, 5, 6, 7, 8]);
    expect(p.issues[0].message).toContain('Data inválida');
    expect(p.issues[1].message).toContain('sem separador de milhar');
    expect(p.issues[2].message).toContain('positivo');
    expect(p.issues[3].message).toContain('C ou D');
    expect(p.issues[4].message).toContain('id_transacao');
    expect(p.issues[5].message).toContain('não pode começar');
    expect(p.issues[6].message).toContain('Data obrigatória');
  });

  it('recusa cabeçalho sem data/valor, BOM, arquivo vazio e codificação fora do UTF-8', () => {
    expect(previewStatement('dt;vl\n2026-10-01;5').issues[0].message).toContain('data e valor');
    expect(previewStatement('﻿' + HEADER + '\n2026-10-01;5;C;;;').issues[0].message).toContain('BOM');
    expect(previewStatement('').issues[0].message).toContain('vazio');
    expect(previewStatement(HEADER).issues[0].message).toContain('só tem o cabeçalho');
    const latin1 = new Uint8Array([0x64, 0x61, 0x74, 0x61, 0x3b, 0xe7]).buffer;
    expect(decodeUtf8(latin1)).toBeNull();
    expect(previewStatement(null).issues[0].message).toContain('UTF-8');
  });

  it('lê aspas, ponto e vírgula e quebra de linha dentro de aspas', () => {
    const records = parseCsv('a;b\n"x;y";"linha\nnova ""citada"""\n\n');
    expect(records.map((r) => r.cells)).toEqual([
      ['a', 'b'],
      ['x;y', 'linha\nnova "citada"'],
    ]);
  });

  it('limita a lista de problemas a 20', () => {
    const lines = [HEADER, ...Array.from({ length: 30 }, () => 'x;y;C;;;')];
    const p = previewStatement(lines.join('\n'));
    expect(p.issues.length).toBe(20);
    expect(p.truncatedIssues).toBe(true);
  });
});
