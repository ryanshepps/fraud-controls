from __future__ import annotations

import argparse
from pathlib import Path

from fraudgen.simulator import SimulationConfig, run_to_csv


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="fraudgen")
    subparsers = parser.add_subparsers(dest="command", required=True)

    run_parser = subparsers.add_parser("run", help="run the Stage 1 baseline simulator")
    run_parser.add_argument("--csv-path", type=Path, required=True)
    run_parser.add_argument("--seed", type=int, default=42)
    run_parser.add_argument("--duration-hours", type=float, default=1.0)
    run_parser.add_argument("--population-size", type=int, default=500)
    run_parser.add_argument("--events-per-hour-target", type=float, default=500.0)
    run_parser.add_argument("--tick-seconds", type=int, default=60)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "run":
        config = SimulationConfig(
            seed=args.seed,
            duration_hours=args.duration_hours,
            population_size=args.population_size,
            events_per_hour_target=args.events_per_hour_target,
            tick_seconds=args.tick_seconds,
            csv_path=args.csv_path,
        )
        summary = run_to_csv(config)
        print(f"events={summary.total_events}")
        print(f"active_customers={summary.active_customers}")
        print(f"avg_events_per_active_customer={summary.average_events_per_active_customer}")
        return 0

    parser.error(f"unknown command: {args.command}")
    return 2
