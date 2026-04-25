import { Tooltip } from 'antd';
import type { TariffPeriodDTO } from '@/api/tariff';

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

function timeToMinutes(t: string): number {
  // "HH:mm" or "HH:mm:ss"
  const [h, m] = t.split(':').map(Number);
  return (h ?? 0) * 60 + (m ?? 0);
}

interface Segment {
  startMin: number;
  endMin: number;
  period: TariffPeriodDTO;
}

function explode(periods: TariffPeriodDTO[]): Segment[] {
  const out: Segment[] = [];
  for (const p of periods) {
    const s = timeToMinutes(p.timeStart);
    const e = timeToMinutes(p.timeEnd);
    if (e > s) {
      out.push({ startMin: s, endMin: e, period: p });
    } else if (e < s) {
      // cross-midnight: split into [s..1440] + [0..e]
      out.push({ startMin: s, endMin: 1440, period: p });
      if (e > 0) out.push({ startMin: 0, endMin: e, period: p });
    } else {
      // 24h coverage: e == s
      out.push({ startMin: 0, endMin: 1440, period: p });
    }
  }
  return out;
}

/** 24h timeline visualization for tariff period coverage. */
export default function TariffTimeline({ periods, height = 32 }: Props) {
  const segs = explode(periods);
  return (
    <div>
      <div
        style={{
          position: 'relative',
          height,
          background: '#f0f0f0',
          borderRadius: 4,
          overflow: 'hidden',
        }}
      >
        {segs.map((s, i) => {
          const left = (s.startMin / 1440) * 100;
          const width = ((s.endMin - s.startMin) / 1440) * 100;
          const color = COLORS[s.period.periodType] ?? '#888';
          return (
            <Tooltip
              key={i}
              title={`${s.period.periodType} ${s.period.timeStart} → ${s.period.timeEnd} | ¥${s.period.pricePerUnit}`}
            >
              <div
                style={{
                  position: 'absolute',
                  left: `${left}%`,
                  width: `${width}%`,
                  top: 0,
                  bottom: 0,
                  background: color,
                  opacity: 0.85,
                  color: 'white',
                  fontSize: 11,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  borderRight: '1px solid white',
                }}
              >
                {width > 5 ? s.period.periodType : ''}
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
          color: '#888',
          marginTop: 2,
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
