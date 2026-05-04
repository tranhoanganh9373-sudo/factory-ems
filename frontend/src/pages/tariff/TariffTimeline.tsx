import { Tooltip } from 'antd';
import type { TariffPeriodDTO } from '@/api/tariff';
import { translate, TARIFF_PERIOD_LABEL } from '@/utils/i18n-dict';

interface Props {
  periods: TariffPeriodDTO[];
  height?: number;
}

const COLORS: Record<string, string> = {
  SHARP: '#cf1322',
  PEAK: '#fa8c16',
  FLAT: '#1677ff',
  VALLEY: '#52c41a',
};

const MIN_LABEL_WIDTH_PCT = 6;

function timeToMinutes(t: string): number {
  const [h, m] = t.split(':').map(Number);
  return (h ?? 0) * 60 + (m ?? 0);
}

interface Segment {
  startMin: number;
  endMin: number;
  period: TariffPeriodDTO;
  isPrimary: boolean;
}

function explode(periods: TariffPeriodDTO[]): Segment[] {
  const out: Segment[] = [];
  for (const p of periods) {
    const s = timeToMinutes(p.timeStart);
    const e = timeToMinutes(p.timeEnd);
    if (e > s) {
      out.push({ startMin: s, endMin: e, period: p, isPrimary: true });
    } else if (e < s) {
      const main = { startMin: s, endMin: 1440, period: p, isPrimary: true };
      const wrap = { startMin: 0, endMin: e, period: p, isPrimary: false };
      if (e > 1440 - s) {
        main.isPrimary = false;
        wrap.isPrimary = true;
      }
      out.push(main);
      if (e > 0) out.push(wrap);
    } else {
      out.push({ startMin: 0, endMin: 1440, period: p, isPrimary: true });
    }
  }
  return out;
}

export default function TariffTimeline({ periods, height = 32 }: Props) {
  const segs = explode(periods);
  return (
    <div>
      <div
        role="img"
        aria-label="24 小时电价时段覆盖图"
        style={{
          position: 'relative',
          height,
          background: 'var(--ems-color-muted, #f0f0f0)',
          borderRadius: 4,
          overflow: 'hidden',
        }}
      >
        {segs.map((s, i) => {
          const left = (s.startMin / 1440) * 100;
          const width = ((s.endMin - s.startMin) / 1440) * 100;
          const color = COLORS[s.period.periodType] ?? '#888';
          const label = translate(TARIFF_PERIOD_LABEL, s.period.periodType);
          const showLabel = s.isPrimary && width >= MIN_LABEL_WIDTH_PCT;
          return (
            <Tooltip
              key={i}
              title={`${label} ${s.period.timeStart} → ${s.period.timeEnd} | ¥${s.period.pricePerUnit}`}
            >
              <div
                style={{
                  position: 'absolute',
                  left: `${left}%`,
                  width: `${width}%`,
                  top: 0,
                  bottom: 0,
                  background: color,
                  opacity: 0.92,
                  boxShadow: 'inset -1px 0 0 rgba(255,255,255,0.6)',
                  overflow: 'hidden',
                }}
              >
                {showLabel && (
                  <span
                    style={{
                      position: 'absolute',
                      left: '50%',
                      top: '50%',
                      transform: 'translate(-50%, -50%)',
                      color: '#fff',
                      fontSize: 12,
                      fontWeight: 500,
                      whiteSpace: 'nowrap',
                      textShadow: '0 1px 2px rgba(0,0,0,0.45)',
                      pointerEvents: 'none',
                    }}
                  >
                    {label}
                  </span>
                )}
              </div>
            </Tooltip>
          );
        })}
      </div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          fontSize: 11,
          color: 'var(--ems-color-text-tertiary, #6B7785)',
          marginTop: 4,
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        <span>00:00</span>
        <span>06:00</span>
        <span>12:00</span>
        <span>18:00</span>
        <span>24:00</span>
      </div>
    </div>
  );
}
