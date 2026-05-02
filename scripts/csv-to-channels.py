#!/usr/bin/env python3
"""
Factory EMS — meter CSV → channels JSON

将 docs/install/meter-register-mapping-template.csv 转换为
docs/install/channel-config-import.json 同 schema 的 JSON，
可直接喂给 scripts/import-channels.sh。

每个 floor → 一个 channel（一台串口服务器），同 floor 下所有
测点合并到该 channel.protocolConfig.points 列表。

Usage:
  ./scripts/csv-to-channels.py \\
      docs/install/meter-register-mapping-template.csv \\
      --floor-host 1F=10.0.1.11,2F=10.0.1.12,3F=10.0.1.13,4F=10.0.1.14 \\
      --poll PT60S --timeout PT2S \\
      -o channels.json

CSV 列：floor, panel, meter_tag, meter_model, unit_id,
        point_key, register_address, quantity, data_type,
        byte_order, scale, unit, kind, description
"""

import argparse
import csv
import json
import sys
from collections import OrderedDict
from pathlib import Path

CSV_REQUIRED = {
    "floor", "unit_id", "point_key", "register_address",
    "quantity", "data_type", "byte_order", "scale",
}


def parse_floor_host(s: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for pair in s.split(","):
        pair = pair.strip()
        if not pair:
            continue
        if "=" not in pair:
            raise ValueError(f"--floor-host pair missing '=': {pair!r}")
        k, v = pair.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def to_int(v: str, field: str, row: int) -> int:
    try:
        return int(v.strip())
    except (ValueError, AttributeError) as e:
        raise SystemExit(f"row {row}: field {field!r} not int: {v!r}") from e


def to_float(v: str, field: str, row: int) -> float:
    try:
        return float(v.strip())
    except (ValueError, AttributeError) as e:
        raise SystemExit(f"row {row}: field {field!r} not float: {v!r}") from e


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv_file", type=Path, help="meter mapping CSV")
    ap.add_argument(
        "--floor-host", required=True,
        help="floor→host IP map, e.g. '1F=10.0.1.11,2F=10.0.1.12'",
    )
    ap.add_argument("--port", type=int, default=502)
    ap.add_argument("--poll", default="PT60S", help="ISO-8601 Duration")
    ap.add_argument("--timeout", default="PT2S", help="ISO-8601 Duration")
    ap.add_argument(
        "-o", "--output", type=Path, default=None,
        help="output JSON path (default: stdout)",
    )
    args = ap.parse_args()

    if not args.csv_file.is_file():
        raise SystemExit(f"CSV not found: {args.csv_file}")

    floor_host = parse_floor_host(args.floor_host)

    # floor → list[point dict]，保留输入顺序
    by_floor: "OrderedDict[str, list[dict]]" = OrderedDict()
    seen_keys: set[str] = set()

    with args.csv_file.open(newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        if reader.fieldnames is None:
            raise SystemExit("CSV has no header row")
        missing = CSV_REQUIRED - set(reader.fieldnames)
        if missing:
            raise SystemExit(f"CSV missing required columns: {sorted(missing)}")

        for i, row in enumerate(reader, start=2):
            floor = row["floor"].strip()
            if not floor:
                continue
            key = row["point_key"].strip()
            if key in seen_keys:
                raise SystemExit(f"row {i}: duplicate point_key {key!r}")
            seen_keys.add(key)

            point = {
                "key": key,
                "registerKind": "HOLDING",
                "address": to_int(row["register_address"], "register_address", i),
                "quantity": to_int(row["quantity"], "quantity", i),
                "dataType": row["data_type"].strip(),
                "byteOrder": row["byte_order"].strip(),
                "scale": to_float(row["scale"], "scale", i),
                "unit": row.get("unit", "").strip(),
            }
            by_floor.setdefault(floor, []).append(point)

    # 为缺失映射的 floor 报错（避免悄悄忽略）
    unmapped = [f for f in by_floor if f not in floor_host]
    if unmapped:
        raise SystemExit(
            f"--floor-host missing mapping for floors: {unmapped}; "
            f"have: {sorted(floor_host)}"
        )

    channels = []
    for floor, points in by_floor.items():
        channels.append({
            "name": f"{floor}-MCC-485",
            "protocol": "MODBUS_TCP",
            "enabled": True,
            "isVirtual": False,
            "description": f"{floor} 配电室 RS-485 总线",
            "protocolConfig": {
                "protocol": "MODBUS_TCP",
                "host": floor_host[floor],
                "port": args.port,
                "unitId": 1,  # 占位；测点 unitId 在测点级别区分（本字段未来弃用）
                "pollInterval": args.poll,
                "timeout": args.timeout,
                "points": points,
            },
        })

    out = {"channels": channels}
    text = json.dumps(out, ensure_ascii=False, indent=2)

    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
        print(
            f"Wrote {len(channels)} channel(s), "
            f"{sum(len(c['protocolConfig']['points']) for c in channels)} point(s) "
            f"→ {args.output}",
            file=sys.stderr,
        )
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
