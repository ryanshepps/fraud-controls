import unittest

from scoring_sidecar.demo_server import score_transaction


class DemoScoringServerTest(unittest.TestCase):
    def test_scores_are_deterministic_and_bounded(self) -> None:
        payload = {
            "amount": 125.0,
            "sender_account_age_days": 0.25,
            "is_new_counterparty": True,
        }

        first = score_transaction(payload)
        second = score_transaction(payload)

        self.assertEqual(first, second)
        self.assertEqual("deterministic_demo", first["mode"])
        self.assertEqual("deterministic-demo-v1", first["model_version"])
        self.assertGreaterEqual(first["calibrated_score"], 0.0)
        self.assertLessEqual(first["calibrated_score"], 1.0)


if __name__ == "__main__":
    unittest.main()
