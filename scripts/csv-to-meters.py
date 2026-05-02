#!/usr/bin/env python3
"""
Factory EMS — meter CSV → meters JSON

将 docs/install/meter-register-mapping-template.csv 转换为 EMS Meter 批量
注册 JSON，可直接喂给 scripts/import-meters.sh。

约定（来自 InfluxSampleWriter.java:25-26 + MeterSeeder.java:26-27）:
  - 每个 CSV 行 → 一条 Meter（meter.code = point_key；channel 配置中的
    points[].key 必须与之相同，否则时序无法落库）
  - influxMeasurement = "energy_reading"
  - influxTagKey      = "meter_code"
  - influxTagValue    = meter.code (= point_key)

energy_types 表内置（V1.2.0__init_energy_types.sql:11-13）:
  ELEC=1, WATER=2, STEAM=3

Usage:
  ./scripts/csv-to-meters.py \\
      docs/install/meter-register-mapping-template.csv \\
      --floor-org 1F=5,2F=6,3F=7,4F=8 \\
      --floor-channel-name 1F=1F-MCC-485,2F=2F-MCC-485,3F=3F-MCC-485,4F=4F-MCC-485 \\
      --energy-type-id 1 \\
      --include-suffix power_total,energy_total \\
      -o meters.json

  --include-suffix 可省略，默认导出全部点位（每块物理表 ~10 行 Meter）。
  线上推荐 power_total + energy_total，每块表 2 行 Meter，看板/账单都够用。
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path

CSV_REQUIRED = {"floor", "meter_tag", "point_key", "description"}

INFLUX_MEASUREMENT = "energy_reading"
INFLUX_TAG_KEY = "meter_code"


@dataclass(frozen=True)
class MeterSpec:
    code: str
    name: str
    energy_type_id: int
    org_node_id: int
    channel_name: str | None
    enabled: bool


def parse_kv_list(s: str, label: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for pair in s.split(","):
        pair = pair.strip()
        if not pair:
            continue
        if "=" not in pair:
            raise SystemExit(f"{label} pair missing '=': {pair!r}")
        k, v = pair.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def parse_floor_org(s: str) -> dict[str, int]:
    raw = parse_kv_list(s, "--floor-org")
    out: dict[str, int] = {}
    for floor, val in raw.items():
        try:
            out[floor] = int(val)
        except ValueError as e:
            raise SystemExit(
                f"--floor-org value not int for {floor!r}: {val!r}"
            ) from e
    return out


def parse_include_suffix(s: str | None) -> set[str] | None:
    if not s:
        return None
    return {x.strip() for x in s.split(",") if x.strip()}


def matches_suffix(point_key: str, suffixes: set[str] | None) -> bool:
    if suffixes is None:
        return True
    return any(
        point_key.endswith("-" + suf) or point_key.endswith(suf)
        for suf in suffixes
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv_file", type=Path, help="meter mapping CSV")
    ap.add_argument(
        "--floor-org", required=True,
        help="floor → orgNodeId map, e.g. '1F=5,2F=6,3F=7,4F=8'",
    )
    ap.add_argument(
        "--floor-channel-name", default=None,
        help=(
            "floor → channel name map (channelId 由 import-meters.sh 在 EMS "
            "里查；不填则导出 meter 不绑 channel，时序无法落库)"
        ),
    )
    ap.add_argument(
        "--energy-type-id", type=int, default=1,
        help="ELEC=1 (default), WATER=2, STEAM=3",
    )
    ap.add_argument(
        "--include-suffix", default=None,
        help=(
            "只保留 point_key 以这些后缀结尾的行，逗号分隔，"
            "例如 'power_total,energy_total'。省略则全收"
        ),
    )
    ap.add_argument(
        "--disabled", action="store_true",
        help="导出时 enabled=false（默认 true）",
    )
    ap.add_argument(
        "-o", "--output", type=Path, default=None,
        help="output JSON path (default: stdout)",
    )
    args = ap.parse_args()

    if not args.csv_file.is_file():
        raise SystemExit(f"CSV not found: {args.csv_file}")

    floor_org = parse_floor_org(args.floor_org)
    floor_channel = (
        parse_kv_list(args.floor_channel_name, "--floor-channel-name")
        if args.floor_channel_name else {}
    )
    suffixes = parse_include_suffix(args.include_suffix)

    meters: "OrderedDict[str, MeterSpec]" = OrderedDict()

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
            point_key = row["point_key"].strip()
            if not point_key:
                continue
            if not matches_suffix(point_key, suffixes):
                continue
            if point_key in meters:
                raise SystemExit(
                    f"row {i}: duplicate point_key {point_key!r} "
                    f"(meter.code must be unique)"
                )
            if floor not in floor_org:
                raise SystemExit(
                    f"row {i}: floor {floor!r} not in --floor-org "
                    f"(have: {sorted(floor_org)})"
                )

            meter_tag = row["meter_tag"].strip()
            desc = row["description"].strip()
            name = f"{meter_tag} - {desc}" if desc else meter_tag

            meters[point_key] = MeterSpec(
                code=point_key,
                name=name,
                energy_type_id=args.energy_type_id,
                org_node_id=floor_org[floor],
                channel_name=floor_channel.get(floor),
                enabled=not args.disabled,
            )

    if not meters:
        raise SystemExit("no meters produced; check --include-suffix or CSV")

    payload = {
        "meters": [
            {
                "code": m.code,
                "name": m.name,
                "energyTypeId": m.energy_type_id,
                "orgNodeId": m.org_node_id,
                "influxMeasurement": INFLUX_MEASUREMENT,
                "influxTagKey": INFLUX_TAG_KEY,
                "influxTagValue": m.code,
                "enabled": m.enabled,
                **({"channelName": m.channel_name} if m.channel_name else {}),
            }
            for m in meters.values()
        ],
    }
    text = json.dumps(payload, ensure_ascii=False, indent=2)

    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
        print(
            f"Wrote {len(meters)} meter(s) → {args.output}",
            file=sys.stderr,
        )
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
