"""Deterministic demo scorer used by the local validation stack.

This is intentionally not the production XGBoost sidecar. It gives docker-compose
an always-available scoring dependency for demos while the real model service
remains tracked separately.
"""

from __future__ import annotations

import json
import math
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


PRIMARY_MODEL_VERSION = "deterministic-demo-v1"
CANDIDATE_MODEL_VERSION = "candidate-demo-v1"


def score_transaction(payload: dict[str, Any]) -> dict[str, Any]:
    model_id = str(payload.get("model_id") or PRIMARY_MODEL_VERSION)
    amount = _number(payload.get("amount"))
    sender_age_days = _number(payload.get("sender_account_age_days"), default=30.0)
    is_new_counterparty = bool(payload.get("is_new_counterparty", False))

    if model_id == CANDIDATE_MODEL_VERSION:
        raw_score = -1.5
        raw_score += min(amount / 150.0, 3.0)
        raw_score += 1.1 if is_new_counterparty else 0.0
        raw_score += 1.0 if sender_age_days < 2.0 else 0.0
        shap_values = {"candidate_demo_score": _sigmoid(raw_score)}
    else:
        model_id = PRIMARY_MODEL_VERSION
        raw_score = -2.0
        raw_score += min(amount / 250.0, 3.0) * 0.9
        raw_score += 1.0 if is_new_counterparty else 0.0
        raw_score += 0.8 if sender_age_days < 1.0 else 0.0
        shap_values = {
            "amount": round(min(amount / 250.0, 3.0) * 0.9, 6),
            "is_new_counterparty": 1.0 if is_new_counterparty else 0.0,
            "sender_account_age_days": 0.8 if sender_age_days < 1.0 else 0.0,
        }
    calibrated_score = 1.0 / (1.0 + math.exp(-raw_score))

    return {
        "mode": "deterministic_demo",
        "model_version": model_id,
        "raw_score": round(raw_score, 6),
        "calibrated_score": round(calibrated_score, 6),
        "shap_values": shap_values,
    }


class DemoScoringHandler(BaseHTTPRequestHandler):
    server_version = "controls-demo-scoring-sidecar/1.0"

    def do_GET(self) -> None:
        if self.path != "/health":
            self._json_response(404, {"error": "not_found"})
            return
        self._json_response(200, {"status": "ok", "mode": "deterministic_demo"})

    def do_POST(self) -> None:
        if self.path != "/score":
            self._json_response(404, {"error": "not_found"})
            return

        length = int(self.headers.get("content-length", "0"))
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._json_response(400, {"error": "invalid_json"})
            return

        if not isinstance(payload, dict):
            self._json_response(400, {"error": "payload_must_be_object"})
            return

        self._json_response(200, score_transaction(payload))

    def log_message(self, format: str, *args: object) -> None:
        return

    def _json_response(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, sort_keys=True).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def _number(value: Any, default: float = 0.0) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    return result if math.isfinite(result) else default


def _sigmoid(raw_score: float) -> float:
    return 1.0 / (1.0 + math.exp(-raw_score))


def main() -> None:
    port = int(os.environ.get("PORT", "50051"))
    server = ThreadingHTTPServer(("0.0.0.0", port), DemoScoringHandler)
    server.serve_forever()


if __name__ == "__main__":
    main()
