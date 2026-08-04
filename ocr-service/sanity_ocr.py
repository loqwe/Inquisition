import re
import threading
from collections import Counter
from dataclasses import dataclass
from io import BytesIO
from typing import Iterable, Optional, Sequence, Tuple

import cv2
import numpy as np
from PIL import Image, UnidentifiedImageError


MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_IMAGE_SIDE = 4096
MIN_MATCHING_VARIANTS = 2


@dataclass(frozen=True)
class OcrLine:
    text: str
    score: float
    box: Tuple[Tuple[float, float], ...]

    @property
    def center_y(self) -> float:
        return sum(point[1] for point in self.box) / len(self.box)

    @property
    def height(self) -> float:
        ys = [point[1] for point in self.box]
        return max(ys) - min(ys)


@dataclass(frozen=True)
class SanityObservation:
    current_sanity: int
    max_sanity: int
    confidence: float
    votes: int


def _valid_values(current: int, maximum: int) -> bool:
    return 0 <= current <= maximum <= 999 and maximum > 0


def parse_maximum(lines: Sequence[OcrLine]):
    candidates = []
    for detected in lines:
        maximum_match = re.search(r"/\s*(\d{1,3})(?!\d)", detected.text)
        if maximum_match:
            maximum = int(maximum_match.group(1))
            if 0 < maximum <= 999:
                candidates.append((detected.score, maximum))
    if not candidates:
        return None
    score, maximum = max(candidates)
    return maximum, score


def parse_observation(lines: Sequence[OcrLine]) -> Optional[SanityObservation]:
    maximum_candidates = []
    for detected in lines:
        combined = re.search(r"(?<!\d)(\d{1,3})\s*/\s*(\d{1,3})(?!\d)", detected.text)
        if combined:
            current = int(combined.group(1))
            maximum = int(combined.group(2))
            if _valid_values(current, maximum):
                return SanityObservation(current, maximum, detected.score, 1)

        maximum_match = re.search(r"/\s*(\d{1,3})(?!\d)", detected.text)
        if maximum_match:
            maximum_candidates.append((int(maximum_match.group(1)), detected))

    pure_numbers = []
    for detected in lines:
        normalized = detected.text.strip()
        if re.fullmatch(r"\d{1,3}", normalized):
            pure_numbers.append((int(normalized), detected))

    candidates = []
    for maximum, maximum_line in maximum_candidates:
        for current, current_line in pure_numbers:
            if current_line.center_y >= maximum_line.center_y:
                continue
            if not _valid_values(current, maximum):
                continue
            confidence = min(current_line.score, maximum_line.score)
            vertical_gap = maximum_line.center_y - current_line.center_y
            candidates.append((confidence, current_line.height, -vertical_gap, current, maximum))

    if not candidates:
        return None
    confidence, _, _, current, maximum = max(candidates)
    return SanityObservation(current, maximum, confidence, 1)


class SanityOcr:
    def __init__(self, engine=None):
        if engine is None:
            from rapidocr_onnxruntime import RapidOCR

            engine = RapidOCR()
        self.engine = engine
        self._engine_lock = threading.Lock()

    def recognize_bytes(self, image_bytes: bytes) -> Optional[SanityObservation]:
        if not image_bytes or len(image_bytes) > MAX_IMAGE_BYTES:
            raise ValueError("image size is invalid")
        try:
            with Image.open(BytesIO(image_bytes)) as image_header:
                width, height = image_header.size
        except (UnidentifiedImageError, OSError, ValueError) as exception:
            raise ValueError("image cannot be decoded") from exception
        if width <= 0 or height <= 0 or max(width, height) > MAX_IMAGE_SIDE:
            raise ValueError("image dimensions exceed the limit")
        encoded = np.frombuffer(image_bytes, dtype=np.uint8)
        image = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("image cannot be decoded")
        return self.recognize_image(image)

    def recognize_image(self, image: np.ndarray) -> Optional[SanityObservation]:
        if image is None or image.ndim != 3 or image.shape[2] != 3:
            raise ValueError("a BGR image is required")
        frame = self.extract_game_frame(image)
        roi = self.crop_sanity(frame)
        observations = []
        with self._engine_lock:
            for variant in self.preprocessing_variants(roi):
                raw_result, _ = self.engine(variant)
                lines = self.to_lines(raw_result)
                parsed = parse_observation(lines)
                if parsed is None:
                    maximum = parse_maximum(lines)
                    current = self.recognize_current(variant)
                    if maximum is not None and current is not None:
                        maximum_value, maximum_score = maximum
                        current_value, current_score = current
                        if _valid_values(current_value, maximum_value):
                            parsed = SanityObservation(
                                current_value,
                                maximum_value,
                                min(current_score, maximum_score),
                                1,
                            )
                if parsed is not None:
                    observations.append(parsed)

        if not observations:
            return None
        counts = Counter((item.current_sanity, item.max_sanity) for item in observations)
        values, votes = counts.most_common(1)[0]
        if votes < MIN_MATCHING_VARIANTS:
            return None
        matching = [
            item for item in observations
            if (item.current_sanity, item.max_sanity) == values
        ]
        return SanityObservation(values[0], values[1], min(item.confidence for item in matching), votes)

    @staticmethod
    def extract_game_frame(image: np.ndarray) -> np.ndarray:
        height, width = image.shape[:2]
        ratio = width / height
        if 1.65 <= ratio <= 1.90:
            return image

        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        edges = cv2.Canny(gray, 50, 150)
        contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        candidates = []
        for contour in contours:
            x, y, candidate_width, candidate_height = cv2.boundingRect(contour)
            if candidate_width < width * 0.45 or candidate_height < height * 0.15:
                continue
            candidate_ratio = candidate_width / candidate_height
            if 1.65 <= candidate_ratio <= 1.90:
                candidates.append((candidate_width * candidate_height, x, y, candidate_width, candidate_height))
        if not candidates:
            raise ValueError("game frame was not found")
        _, x, y, candidate_width, candidate_height = max(candidates)
        return image[y:y + candidate_height, x:x + candidate_width]

    @staticmethod
    def crop_sanity(frame: np.ndarray) -> np.ndarray:
        height, width = frame.shape[:2]
        left = round(width * 0.56)
        right = round(width * 0.70)
        top = round(height * 0.16)
        bottom = round(height * 0.37)
        roi = frame[top:bottom, left:right]
        if roi.size == 0 or roi.shape[0] < 20 or roi.shape[1] < 20:
            raise ValueError("sanity region is too small")
        return roi

    def recognize_current(self, sanity_roi: np.ndarray):
        height, width = sanity_roi.shape[:2]
        current_roi = sanity_roi[
            round(height * 0.08):round(height * 0.68),
            round(width * 0.38):round(width * 0.74),
        ]
        raw_result, _ = self.engine(
            current_roi,
            use_det=False,
            use_cls=False,
            use_rec=True,
        )
        candidates = []
        for item in raw_result or []:
            if not item or len(item) < 2:
                continue
            text = str(item[0]).strip()
            if re.fullmatch(r"\d{1,3}", text):
                candidates.append((float(item[1]), int(text)))
        if not candidates:
            return None
        score, current = max(candidates)
        return current, score

    @staticmethod
    def preprocessing_variants(roi: np.ndarray) -> Iterable[np.ndarray]:
        yield roi
        yield cv2.resize(roi, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
        gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
        enhanced = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)
        yield cv2.resize(enhanced, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)

    @staticmethod
    def to_lines(raw_result) -> Sequence[OcrLine]:
        if not raw_result:
            return []
        lines = []
        for item in raw_result:
            if not item or len(item) < 3:
                continue
            box, text, score = item[0], item[1], item[2]
            try:
                normalized_box = tuple((float(point[0]), float(point[1])) for point in box)
                lines.append(OcrLine(str(text), float(score), normalized_box))
            except (TypeError, ValueError, IndexError):
                continue
        return lines
