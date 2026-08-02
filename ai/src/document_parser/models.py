"""Shared result types for document parsing."""

from dataclasses import dataclass
from typing import Literal, Protocol


Method = Literal["native", "ocr"]


class OcrEngine(Protocol):
    """스캔 페이지 이미지를 텍스트로 바꾸는 포트. 로컬 Tesseract 든 원격 비전 모델이든
    이 한 함수 뒤에 있다 (`vision_ocr.AnthropicVisionOcr`). PNG 바이트를 받아 텍스트를
    돌려주고, 읽지 못하면 `DocumentParseFailure` 를 올린다."""

    def ocr_image(self, png_bytes: bytes) -> str: ...


@dataclass(frozen=True)
class ParseError:
    code: str
    message: str
    failed_pages: tuple[int, ...] = ()


@dataclass(frozen=True)
class ParseOptions:
    ocr_language: str = "eng"
    # 주면 스캔 페이지 OCR 을 이 엔진으로 한다(원격 비전 모델). 없으면 로컬 Tesseract.
    ocr_engine: OcrEngine | None = None


@dataclass(frozen=True)
class PageResult:
    page: int
    text: str
    method: Method
    quality_score: float


@dataclass(frozen=True)
class ParseResult:
    text: str = ""
    pages: tuple[PageResult, ...] = ()
    used_ocr: bool = False
    quality_score: float = 0.0
    warnings: tuple[str, ...] = ()
    error: ParseError | None = None
