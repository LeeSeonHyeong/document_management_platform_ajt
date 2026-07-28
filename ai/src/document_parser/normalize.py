"""Text normalization helpers for parsers that need canonical output."""

import re


def normalize_text(text: str) -> str:
    """Normalize line endings and whitespace while retaining non-blank lines."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = (re.sub(r"[^\S\r\n]+", " ", line).strip() for line in text.split("\n"))
    return "\n".join(line for line in lines if line).strip()
