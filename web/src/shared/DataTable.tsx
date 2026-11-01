import type { ReactNode } from 'react';

export interface Column<T> {
  key: string;
  header: string;
  /** Numbers and times line up right with tabular figures; everything else left. */
  align?: 'left' | 'right';
  cell: (row: T) => ReactNode;
  className?: string;
}

interface Props<T> {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** Makes rows clickable (and keyboard-operable with Enter). */
  onRowClick?: (row: T) => void;
  rowClassName?: (row: T) => string | undefined;
  testId?: string;
}

/** The one table renderer used by every panel. Alignment is declared per column, not per cell. */
export function DataTable<T>({ columns, rows, rowKey, onRowClick, rowClassName, testId }: Props<T>) {
  return (
    <table className="data-table" data-testid={testId}>
      <thead>
        <tr>
          {columns.map((c) => (
            <th key={c.key} className={c.align === 'right' ? 'num' : undefined}>
              {c.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={rowKey(row)}
            className={[onRowClick ? 'clickable' : '', rowClassName?.(row) ?? ''].join(' ').trim() || undefined}
            onClick={onRowClick ? () => onRowClick(row) : undefined}
            onKeyDown={onRowClick ? (e) => e.key === 'Enter' && onRowClick(row) : undefined}
            tabIndex={onRowClick ? 0 : undefined}
          >
            {columns.map((c) => (
              <td
                key={c.key}
                className={[c.align === 'right' ? 'num' : '', c.className ?? ''].join(' ').trim() || undefined}
              >
                {c.cell(row)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
