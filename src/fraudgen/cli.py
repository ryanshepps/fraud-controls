from __future__ import annotations

import argparse
from pathlib import Path

from fraudgen.run_config import load_run_config
from fraudgen.runner import run_from_config
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

    run_config_parser = subparsers.add_parser(
        "run-config", help="run baseline and fraud scenarios from a YAML config"
    )
    run_config_parser.add_argument("--config", type=Path, required=True)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "run":
        simulation_config = SimulationConfig(
            seed=args.seed,
            duration_hours=args.duration_hours,
            population_size=args.population_size,
            events_per_hour_target=args.events_per_hour_target,
            tick_seconds=args.tick_seconds,
            csv_path=args.csv_path,
        )
        simulation_summary = run_to_csv(simulation_config)
        print(f"events={simulation_summary.total_events}")
        print(f"active_customers={simulation_summary.active_customers}")
        print(
            f"avg_events_per_active_customer={simulation_summary.average_events_per_active_customer}"
        )
        return 0

    if args.command == "run-config":
        run_config = load_run_config(args.config)
        run_summary = run_from_config(run_config)
        print(f"events={run_summary.total_events}")
        print(f"scenario_events={run_summary.scenario_events}")
        print(f"labels={run_summary.total_labels}")
        return 0

    parser.error(f"unknown command: {args.command}")
    return 2
