import { InputNumber } from 'antd';

interface Props {
  value?: string;
  onChange?: (v: string) => void;
  min?: number;
  max?: number;
  placeholder?: string;
}

const ISO_RE = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/i;

function parseIsoToSeconds(v?: string): number | undefined {
  if (!v) return undefined;
  const m = ISO_RE.exec(v.trim());
  if (!m) return undefined;
  const h = parseFloat(m[1] ?? '0');
  const min = parseFloat(m[2] ?? '0');
  const s = parseFloat(m[3] ?? '0');
  const total = h * 3600 + min * 60 + s;
  if (!Number.isFinite(total) || total <= 0) return undefined;
  return Math.round(total);
}

export function DurationInput({ value, onChange, min = 1, max = 86400, placeholder }: Props) {
  const seconds = parseIsoToSeconds(value);
  return (
    <InputNumber
      style={{ width: '100%' }}
      min={min}
      max={max}
      step={1}
      precision={0}
      value={seconds}
      placeholder={placeholder}
      onChange={(v) => {
        if (v == null) {
          onChange?.('');
        } else {
          onChange?.(`PT${Math.round(Number(v))}S`);
        }
      }}
      addonAfter="秒"
    />
  );
}
