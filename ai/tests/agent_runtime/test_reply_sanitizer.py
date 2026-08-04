from agent_runtime.reply_sanitizer import sanitize_admin_reply


def test_strips_leading_jargon_paragraph():
    text = (
        "error 없이 완료되었습니다. warn(고아 페이지)은 이번 작업 범위 밖의 기존 문제입니다.\n\n"
        "출장비(여비) 정산 안내 페이지의 제목 바로 아래에 (2026년 개정판) 문구를 한 줄 추가했습니다."
    )
    result = sanitize_admin_reply(text)
    assert result == "출장비(여비) 정산 안내 페이지의 제목 바로 아래에 (2026년 개정판) 문구를 한 줄 추가했습니다."


def test_strips_leading_jargon_paragraph_with_separator():
    text = (
        "lint 통과 확인 완료.\n\n---\n\n"
        "연차 신청 규정을 반영했습니다."
    )
    result = sanitize_admin_reply(text)
    assert result == "연차 신청 규정을 반영했습니다."


def test_removes_internal_page_addresses():
    text = (
        "요청하신 페이지 주소(`pages/67910e3f5c0c.md`)는 연차유급휴가 규정 페이지여서, "
        "실제 출장비 정산 안내 페이지(`pages/8300a3625b5f.md`)를 찾아 작업했습니다."
    )
    result = sanitize_admin_reply(text)
    assert "pages/" not in result
    assert "연차유급휴가 규정 페이지" in result


def test_removes_document_id_tokens():
    text = "document-33 원본문서를 근거로 페이지를 반영했습니다."
    result = sanitize_admin_reply(text)
    assert "document-33" not in result


def test_leaves_clean_reply_untouched():
    text = "연차 신청 규정 문서를 반영해 '연차 휴가 산정' 페이지에 이월 규칙을 추가했습니다."
    assert sanitize_admin_reply(text) == text


def test_does_not_strip_when_first_paragraph_is_the_only_content():
    """잡소리 문단만 있고 뒤에 실질 내용이 없으면 지우지 않는다 — 빈 답변보다 낫다."""
    text = "error 없이 완료되었습니다.\n\n   "
    assert sanitize_admin_reply(text) == "error 없이 완료되었습니다."


def test_empty_text_passthrough():
    assert sanitize_admin_reply("") == ""
