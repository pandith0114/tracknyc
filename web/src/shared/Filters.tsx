import { useState } from 'react';
import type { RouteView, SystemView } from './types';
import type { ColorMode } from '../trains/layers';

interface Props {
  systems: SystemView[];
  hiddenSystems: Set<string>;
  hiddenRoutes: Set<string>;
  colorMode: ColorMode;
  onToggleSystem: (id: string) => void;
  onToggleRoute: (publicId: string) => void;
  onColorMode: (m: ColorMode) => void;
  onLocate?: () => void;
}

/** System and line filters, delay-colour toggle, and the "trains near me" button. */
export function Filters({
  systems, hiddenSystems, hiddenRoutes, colorMode, onToggleSystem, onToggleRoute, onColorMode, onLocate,
}: Props) {
  const [open, setOpen] = useState(false);
  return (
    <div className={`filters ${open ? 'open' : ''}`} data-testid="filters">
      <button className="filters-toggle" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        Filters
      </button>
      <div className="filters-body">
        <fieldset>
          <legend>Colour trains by</legend>
          <label>
            <input type="radio" name="colour" checked={colorMode === 'line'} onChange={() => onColorMode('line')} />
            line
          </label>
          <label>
            <input type="radio" name="colour" checked={colorMode === 'delay'} onChange={() => onColorMode('delay')} />
            delay
          </label>
        </fieldset>
        {systems.map((s) => (
          <fieldset key={s.id}>
            <legend>
              <label>
                <input
                  type="checkbox"
                  checked={!hiddenSystems.has(s.id)}
                  onChange={() => onToggleSystem(s.id)}
                  data-testid={`filter-system-${s.id}`}
                />
                {s.displayName}
              </label>
            </legend>
            <div className="routes">
              {s.routes.map((r: RouteView) => {
                const hidden = hiddenRoutes.has(r.publicId);
                return (
                  <button
                    key={r.publicId}
                    className={`route-chip ${hidden ? 'off' : ''}`}
                    style={{ background: hidden ? 'transparent' : r.color, color: hidden ? r.color : r.textColor,
                      borderColor: r.color }}
                    title={r.longName ?? r.shortName}
                    onClick={() => onToggleRoute(r.publicId)}
                    data-testid={`filter-route-${r.publicId}`}
                  >
                    {r.shortName}
                  </button>
                );
              })}
            </div>
          </fieldset>
        ))}
        {onLocate && (
          <button className="locate" onClick={onLocate} data-testid="locate">
            Trains near me
          </button>
        )}
      </div>
    </div>
  );
}
