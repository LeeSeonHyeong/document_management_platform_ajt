"""절 읽기 미스 응답 (S15P11B106-315).

절 이름이 안 맞을 때 "없다"만 돌려주면 모델은 이름을 다시 추측하거나 전문 재읽기로
후퇴한다 — 2026-08-07 read 53연속 반복 사고의 직전 행동이 정확히 「절 read 미스 직후
전문 read」였다. 절 이름들은 이 함수가 이미 파싱해 들고 있으므로, 미스 응답에 실제
목록을 실어 한 번에 교정하게 한다.
"""

from wiki_mcp.tools.read import _extract_sections

PAGE = """---
title: 정보보안 기본 정책
---

머리말이다.

## 계정·비밀번호

12자 이상.

## 사고 대응

내선 3000.
"""


def test_절_이름이_맞으면_그_절만_돌려준다():
    result = _extract_sections(PAGE, ["사고 대응"])
    assert "내선 3000" in result
    assert "12자 이상" not in result


def test_대소문자는_구분하지_않는다():
    assert "12자 이상" in _extract_sections("## Account\n\n12자 이상", ["account"])


def test_미스_응답에_실제_절_목록을_싣는다():
    result = _extract_sections(PAGE, ["사고대응"])
    assert "해당하는 절이 없다" in result
    assert "계정·비밀번호" in result
    assert "사고 대응" in result


def test_절이_없는_페이지는_그_사실을_말한다():
    result = _extract_sections("절 제목 없는 본문뿐.", ["아무거나"])
    assert "해당하는 절이 없다" in result
    assert "절 제목이 없다" in result
