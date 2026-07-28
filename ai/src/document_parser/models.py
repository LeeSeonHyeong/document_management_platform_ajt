"""Shared result types for document parsing."""

from dataclasses import dataclass
from typing import Literal


Method = Literal["native", "ocr"]


@dataclass(frozen=True)
class ParseError:
    code: str
    message: str
    failed_pages: tuple[int, ...] = ()


@dataclass(frozen=True)
class ParseOptions:
    ocr_language: str = "eng"


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
