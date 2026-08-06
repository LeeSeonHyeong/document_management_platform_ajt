from agent_runtime.reply_sanitizer import sanitize_admin_reply, scrub_internal_tokens


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


# ----- 하네스 실측으로 잡은 누출 (2026-08-04) ---------------------------------
#
# 강화된 `edit_instruction` 으로도 4~6회 중 1회 새는 것을 하네스에서 관측했다. 프롬프트
# 지시는 확률적이라 이 안전망이 실제 누출 모양을 덮어야 한다. 아래 두 문자열은 실측
# 원문이다 — 가공하지 않았다.


def test_strips_english_jargon_paragraph_without_korean_keywords():
    """영어 점검 문단. 한국어 키워드가 없어 그냥 통과하던 것이다.

    `Everything checks out fine` 은 지시문이 금지한 「점검이 끝났다는 말로 시작」 그 자체인데
    `error`·`오류` 같은 키워드가 없어 첫 문단 판별을 빠져나갔다.
    """
    text = (
        "Everything checks out fine — index.md already reflects the current page "
        "content correctly, so nothing more needed there.\n\n"
        "The page already opens with a one-line summary of the Slack channel naming rule."
    )
    result = sanitize_admin_reply(text)
    assert not result.startswith("Everything checks out fine")
    assert "index.md" not in result
    assert "one-line summary" in result


def test_replaces_index_md_with_a_word_the_admin_knows():
    """`index.md` 는 지우지 않고 「목차」로 바꾼다.

    지우면 문장의 주어가 사라진다 — 실측에서 `document-32` 를 지웠더니 "기존 각주 전체가
    (존재하지 않는 ID)를 참조하고 있어" 처럼 무엇이 참조하는지가 사라졌다. 관리자가 읽을 수
    있는 말로 바꿔치기하는 것이 삭제보다 안전하다.
    """
    text = "목차 페이지는 손대지 않았습니다. index.md 의 요약은 그대로 맞습니다."
    result = sanitize_admin_reply(text)
    assert "index.md" not in result
    assert "목차" in result
    # 문장이 깨지지 않았다 — 주어가 남아 있다
    assert "요약은 그대로 맞습니다" in result


def test_strips_a_jargon_parenthetical_in_the_middle_of_a_sentence():
    """문단 중간에 섞인 점검 서술도 걷어낸다 (실측 8/3 답변).

    「오류가 없다」는 정보는 관리자에게 가치가 0이므로 지워도 잃는 것이 없다. 첫 문단만
    보던 제약 때문에 이런 괄호가 그대로 남았다. 괄호 안만 지우고 문장 본체는 살린다 —
    무엇을 고쳤는지는 관리자가 알아야 한다.
    """
    text = (
        "각주 문서명을 일괄 수정했습니다. 기존 각주가 잘못된 문서를 참조하고 있어 올바른 "
        "문서명으로 교체했습니다. (이는 이번 편집 전부터 있던 오류였으나, lint가 이번 작업 "
        "대상 페이지를 검사하면서 error로 잡혔기 때문에 함께 수정함)"
    )
    result = sanitize_admin_reply(text)
    assert "lint" not in result
    assert "error" not in result
    assert "올바른 문서명으로 교체했습니다" in result


def test_strips_a_standalone_check_result_sentence_anywhere():
    """점검 결과만 말하는 문장은 위치와 무관하게 지운다."""
    text = (
        "출장비 정산 안내 페이지에 개정 문구를 넣었습니다. lint 검사 통과 (error 없음). "
        "나머지 내용은 그대로 두었습니다."
    )
    result = sanitize_admin_reply(text)
    assert "lint" not in result and "error" not in result
    assert "개정 문구를 넣었습니다" in result
    assert "나머지 내용은 그대로 두었습니다" in result


def test_never_drops_a_table_row_even_when_it_contains_a_jargon_word():
    """표 행은 사실 내용이라 문장 단위 제거 대상이 아니다.

    작업 요약이 표 형식이므로(S15P11B106-251) 「주요 내용」 칸에 위키 주제로서 「검증」·
    「통과」가 들어갈 수 있다. 그것을 점검 서술로 오인해 행을 지우면 관리자가 무슨 페이지가
    바뀌었는지 아예 못 본다 — 누출을 막으려다 정보를 없애는 쪽이 더 나쁘다.
    """
    text = (
        "**2장** 을 다뤘습니다 (새로 1장, 수정 1장).\n\n"
        "| 페이지 | 처리 | 주요 내용 | 근거 |\n"
        "| --- | --- | --- | --- |\n"
        "| 코드 리뷰 절차 | 새로 만듦 | 리뷰 검증 단계와 승인 기준 | 9곳 |\n"
        "| 배포 절차 | 고침 | 배포 전 통과 조건 정리 | 5곳 |"
    )
    result = sanitize_admin_reply(text)
    assert "코드 리뷰 절차" in result
    assert "배포 절차" in result
    assert "리뷰 검증 단계와 승인 기준" in result


def test_still_scrubs_internal_addresses_inside_a_table_row():
    """행을 지우지는 않지만 내부 경로·파일명은 표 안에서도 걷어낸다."""
    text = (
        "| 페이지 | 처리 |\n| --- | --- |\n"
        "| 교육 및 도서 지원 (`pages/454c405163f2.md`) | 새로 만듦 |"
    )
    result = sanitize_admin_reply(text)
    assert "pages/" not in result
    assert "교육 및 도서 지원" in result


def test_never_drops_a_labelled_supplement_line():
    """작업 요약의 보충 항목은 라벨이 정해져 있고, 그 줄은 지우지 않는다.

    「반영하지 않은 내용」은 관리자가 누락을 판단하는 근거다. 그 줄에 위키 주제로서 「검증」이
    들어갔다고 줄째 지우면, 정직하게 밝힌 미반영 항목이 조용히 사라진다 — 안전망이 오히려
    숨기는 쪽으로 작동한다.
    """
    text = (
        "**1장**을 다뤘습니다 (새로 1장).\n\n"
        "| 페이지 | 처리 |\n| --- | --- |\n| 배포 절차 | 새로 만듦 |\n\n"
        "- 태그: 배포, 검증, 품질\n"
        "- 시각 자료: 없습니다\n"
        "- 상호 링크: 없습니다\n"
        "- 반영하지 않은 내용: 근거가 약한 사전 검증 항목은 뺐습니다\n"
    )
    result = sanitize_admin_reply(text)
    assert "태그: 배포, 검증, 품질" in result
    assert "반영하지 않은 내용: 근거가 약한 사전 검증 항목은 뺐습니다" in result


def test_still_drops_a_jargon_bullet_that_is_not_a_supplement_line():
    """라벨 없는 점검 서술 불릿은 그대로 지운다 (실측 8/3 답변의 그 줄)."""
    text = (
        "부업 신청 절차 요약을 맨 위에 넣었습니다.\n\n"
        "- lint 검사 통과 (error 없음)\n"
        "- 태그: 부업, 승인\n"
    )
    result = sanitize_admin_reply(text)
    assert "lint" not in result and "error" not in result
    assert "태그: 부업, 승인" in result


def test_keeps_the_reply_when_removing_check_sentences_would_empty_it():
    """전부 점검 서술이면 지우지 않는다 — 빈 답변보다 낫다는 기존 판단을 유지한다."""
    text = "lint 검사를 통과했습니다. 오류는 없습니다."
    assert sanitize_admin_reply(text) == text


def test_keeps_a_good_korean_reply_that_mentions_the_table_of_contents():
    """「목차 페이지」라고 제대로 부른 답변은 손대지 않는다 (실측 4회차 답변)."""
    text = (
        "「Slack 사용 규칙과 채널 명명 규칙」 페이지 맨 위에, 채널 이름은 팀·프로젝트·고객 등 "
        "용도별 접두사에 이름을 붙여 정한다는 요약 한 줄을 추가했습니다. 나머지 내용과 각주는 "
        "그대로 두었고, 목차 페이지의 소개 문구도 이 페이지가 다루는 내용과 여전히 일치해 "
        "따로 손대지 않았습니다."
    )
    assert sanitize_admin_reply(text) == text


# ----- 목차 요약용 토큰 청소 (S15P11B106-251) --------------------------------
#
# 위키 상세 화면의 제목 아래 요약은 `wiki.summary` 이고, 그 값은 에이전트 답변이 아니라
# **페이지 frontmatter `description` 에서 가져온 것**이다 (`changes.py::
# index_entries_from_pages`). 그래서 `sanitize_admin_reply` 경로를 지나지 않아 내부
# 토큰이 그대로 화면까지 갔다. 실측:
#   "… 사전 승인 절차 (document-32 반영)"  ← 위키 8의 요약, MySQL 에 저장된 값
#
# 요약에는 답변용 문장 제거를 걸지 않는다. 요약은 위키 내용이라 「검증 절차 정리」 같은
# 정당한 요약이 점검 서술로 오인될 수 있다 — 토큰만 걷어낸다.


def test_scrub_removes_a_parenthetical_that_only_names_an_internal_document():
    """`(document-36 반영)` 은 괄호째 지운다.

    토큰만 지우면 `( 반영)` 이 남는다. 어떤 원본문서에서 왔는지는 근거 문서 목록이 이미
    보여주므로 요약에서는 통째로 빼는 것이 맞다.
    """
    text = "레벨·스텝·벤치마크 구조, 연 2~3회 급여 리뷰 프로세스 (document-36 반영)"
    result = scrub_internal_tokens(text)
    assert result == "레벨·스텝·벤치마크 구조, 연 2~3회 급여 리뷰 프로세스"


def test_scrub_replaces_the_index_file_name():
    assert scrub_internal_tokens("index.md 기준으로 정리") == "목차 기준으로 정리"


def test_scrub_removes_page_addresses():
    assert "pages/" not in scrub_internal_tokens("연차 규정(`pages/abc123.md`) 정리")


def test_scrub_keeps_a_summary_that_looks_like_check_prose():
    """요약에는 문장 제거를 걸지 않는다 — 위키 주제로서의 「검증」·「통과」를 지키기 위해서다."""
    text = "배포 전 통과 조건, 사전 검증 단계 정리"
    assert scrub_internal_tokens(text) == text


def test_scrub_leaves_a_clean_summary_untouched():
    text = "출장 기간(기본 3일), 일비(1일 8만원), 보고서 제출 의무(5일 이내)"
    assert scrub_internal_tokens(text) == text
