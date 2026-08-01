"""sources/*.md → documents/ 렌더. 원문(SSOT)에서 배포 형식을 재현한다.

PDF 는 한글 글리프가 있는 시스템 폰트를 임베드한다 — PyMuPDF 기본 base-14 폰트에는
한글이 없어 임베드를 빼면 파싱이 깨진다(빈 텍스트 → OCR 로 오인).
CI(리눅스)에서 도는 게 아니라 로컬 1회 생성용이라 macOS 시스템 폰트를 먼저 찾는다.
렌더 산출물은 커밋하므로 시연에서 이 스크립트를 다시 돌릴 필요는 없다.
"""
from pathlib import Path

import pymupdf
from docx import Document
from docx.enum.style import WD_STYLE_TYPE

ROOT = Path(__file__).resolve().parent
SOURCES = ROOT / "sources"
DOCUMENTS = ROOT / "documents"

FORMAT_MAP = {
    "01-service-rules-v1": "docx",
    "02-hr-committee-minutes-2024-03": "pdf",
    "03-service-rules-amendment": "md",
    "04-business-trip-guide": "docx",
    "05-expense-faq": "txt",
    "06-teamlead-minutes-2024-06": "pdf",
    "07-onboarding-guide": "docx",
    "08-infosec-policy": "md",
}

KOREAN_FONT_CANDIDATES = [
    "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    "/System/Library/Fonts/Supplemental/AppleGothic.ttf",
    "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
    "/Library/Fonts/NanumGothic.ttf",
]


def _korean_font() -> str:
    for candidate in KOREAN_FONT_CANDIDATES:
        if Path(candidate).exists():
            return candidate
    raise SystemExit(
        "한국어 PDF 렌더용 폰트를 찾지 못했습니다. NanumGothic 등을 설치하거나 "
        "build.py 의 KOREAN_FONT_CANDIDATES 에 경로를 추가하세요."
    )


def render_pdf(md_text: str, out: Path) -> None:
    font = _korean_font()
    doc = pymupdf.open()
    lines = md_text.splitlines() or [""]
    per_page = 42
    for start in range(0, len(lines), per_page):
        page = doc.new_page()
        rect = pymupdf.Rect(60, 60, page.rect.width - 60, page.rect.height - 60)
        chunk = "\n".join(lines[start:start + per_page])
        leftover = page.insert_textbox(
            rect, chunk, fontname="ko", fontfile=font, fontsize=11
        )
        if leftover < 0:
            # per_page 가 커서 안 들어감 — 줄 수를 줄여 다시 시도
            raise SystemExit(f"{out.name}: 페이지에 텍스트가 넘침, per_page 조정 필요")
    doc.save(out)
    doc.close()


def render_docx(md_text: str, out: Path) -> None:
    doc = Document()
    doc.styles.add_style("제목 2", WD_STYLE_TYPE.PARAGRAPH)
    for raw in md_text.splitlines():
        line = raw.rstrip()
        if not line:
            continue
        if line.startswith("| ") and line.endswith(" |"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            if set("".join(cells)) <= set("-: "):   # 표 구분선(|---|) 은 건너뛴다
                continue
            table = doc.add_table(rows=1, cols=len(cells))
            for i, cell in enumerate(cells):
                table.rows[0].cells[i].text = cell
        elif line.startswith("#"):
            level = min(len(line) - len(line.lstrip("#")), 6)
            doc.add_heading(line.lstrip("# ").strip(), level=level)
        else:
            doc.add_paragraph(line)
    doc.save(out)


def build_all(root: Path = ROOT) -> list[Path]:
    (root / "documents").mkdir(exist_ok=True)
    written: list[Path] = []
    for slug, fmt in FORMAT_MAP.items():
        md_text = (root / "sources" / f"{slug}.md").read_text(encoding="utf-8")
        out = root / "documents" / f"{slug}.{fmt}"
        if fmt == "md":
            out.write_text(md_text, encoding="utf-8")
        elif fmt == "txt":
            out.write_text(md_text, encoding="utf-8")
        elif fmt == "docx":
            render_docx(md_text, out)
        elif fmt == "pdf":
            render_pdf(md_text, out)
        written.append(out)
    return written


if __name__ == "__main__":
    for path in build_all():
        print(f"wrote {path.relative_to(ROOT.parent.parent)}")
