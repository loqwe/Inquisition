import io
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np
from PIL import Image


SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT))

from sanity_ocr import OcrLine, SanityOcr, SanityObservation, parse_observation


def line(text, score, top, bottom):
    return OcrLine(
        text=text,
        score=score,
        box=((0.0, top), (20.0, top), (20.0, bottom), (0.0, bottom)),
    )


class FakeEngine:
    def __init__(self, batches):
        self.batches = list(batches)
        self.calls = 0

    def __call__(self, _image, **_kwargs):
        batch = self.batches[min(self.calls, len(self.batches) - 1)]
        self.calls += 1
        return batch, [0.0, 0.0, 0.0]


class SanityOcrTest(unittest.TestCase):
    def test_parser_extracts_current_and_maximum_from_detected_lines(self):
        result = parse_observation([
            line("1", 0.99, 10, 38),
            line("理/210", 0.93, 50, 64),
        ])

        self.assertEqual(SanityObservation(1, 210, 0.93, 1), result)

    def test_parser_rejects_impossible_values(self):
        result = parse_observation([
            line("250", 0.99, 10, 38),
            line("理智/210", 0.99, 50, 64),
        ])

        self.assertIsNone(result)

    def test_recognizer_requires_two_matching_preprocessing_results(self):
        valid = [
            [[[0, 0], [10, 0], [10, 20], [0, 20]], "1", 0.98],
            [[[0, 30], [30, 30], [30, 42], [0, 42]], "理/210", 0.94],
        ]
        conflicting = [
            [[[0, 0], [10, 0], [10, 20], [0, 20]], "2", 0.98],
            [[[0, 30], [30, 30], [30, 42], [0, 42]], "理/210", 0.94],
        ]
        image = np.zeros((720, 1280, 3), dtype=np.uint8)
        recognizer = SanityOcr(engine=FakeEngine([valid, valid, conflicting]))

        result = recognizer.recognize_image(image)

        self.assertEqual(1, result.current_sanity)
        self.assertEqual(210, result.max_sanity)
        self.assertEqual(2, result.votes)

    def test_recognizer_rejects_a_single_unconfirmed_result(self):
        valid = [
            [[[0, 0], [10, 0], [10, 20], [0, 20]], "1", 0.98],
            [[[0, 30], [30, 30], [30, 42], [0, 42]], "理/210", 0.94],
        ]
        image = np.zeros((720, 1280, 3), dtype=np.uint8)
        recognizer = SanityOcr(engine=FakeEngine([valid, [], []]))

        self.assertIsNone(recognizer.recognize_image(image))

    def test_recognition_only_fallback_recovers_a_stylized_current_value(self):
        maximum_only = [
            [[[0, 30], [30, 30], [30, 42], [0, 42]], "理智/210", 0.96],
        ]
        batches = []
        for _ in range(3):
            batches.extend((maximum_only, [["2", 0.99]]))
        image = np.zeros((720, 1280, 3), dtype=np.uint8)
        recognizer = SanityOcr(engine=FakeEngine(batches))

        result = recognizer.recognize_image(image)

        self.assertEqual(SanityObservation(2, 210, 0.96, 3), result)

    def test_nested_game_frame_is_selected_from_a_page_screenshot(self):
        page = np.full((900, 860, 3), 255, dtype=np.uint8)
        cv2.rectangle(page, (80, 490), (780, 883), (0, 0, 0), 3)
        recognizer = SanityOcr(engine=FakeEngine([[]]))

        frame = recognizer.extract_game_frame(page)

        self.assertGreaterEqual(frame.shape[1], 690)
        self.assertGreaterEqual(frame.shape[0], 385)
        self.assertAlmostEqual(16 / 9, frame.shape[1] / frame.shape[0], delta=0.04)

    def test_oversized_dimensions_are_rejected_before_opencv_decode(self):
        encoded = io.BytesIO()
        Image.new("RGB", (5000, 1), "white").save(encoded, format="PNG")
        recognizer = SanityOcr(engine=FakeEngine([[]]))

        with patch("sanity_ocr.cv2.imdecode") as decode:
            with self.assertRaisesRegex(ValueError, "dimensions"):
                recognizer.recognize_bytes(encoded.getvalue())

        decode.assert_not_called()

    @unittest.skipUnless(os.getenv("OCR_SAMPLE_IMAGE"), "set OCR_SAMPLE_IMAGE for real-model verification")
    def test_real_sample_reads_one_of_two_hundred_and_ten(self):
        image = cv2.imread(os.environ["OCR_SAMPLE_IMAGE"])
        self.assertIsNotNone(image)
        result = SanityOcr().recognize_image(image)
        self.assertIsNotNone(result)
        self.assertEqual((1, 210), (result.current_sanity, result.max_sanity))

    @unittest.skipUnless(os.getenv("OCR_SAMPLE_IMAGE_2"), "set OCR_SAMPLE_IMAGE_2 for second real sample")
    def test_second_real_sample_reads_two_of_two_hundred_and_ten(self):
        image = cv2.imread(os.environ["OCR_SAMPLE_IMAGE_2"])
        self.assertIsNotNone(image)
        result = SanityOcr().recognize_image(image)
        self.assertIsNotNone(result)
        self.assertEqual((2, 210), (result.current_sanity, result.max_sanity))


if __name__ == "__main__":
    unittest.main()
