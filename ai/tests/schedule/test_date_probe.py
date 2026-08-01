"""문서에 날짜 패턴이 있나. 모델이 0건을 냈을 때만 돈다 (설계 §3.3).

거짓 양성은 감수한다 — 회의록의 작성일처럼 일정이 아닌 날짜가 500 을 만든다.
일정이 든 문서를 "없다" 고 답하는 쪽이 더 나쁘다.
"""

import pytest

from schedule_extractor.date_probe import has_any_date_evidence, has_date_pattern


@pytest.mark.parametrize("text", [
    "워크샵은 8월 12일에 진행합니다.",
    "일시: 2026-08-12",
    "기간 2026.08.12 ~ 2026.08.13",
    "제출 기한은 8/5 입니다.",
    "8월 20일(목) 오후 2시",
    "12월 31일까지",
])
def test_finds_korean_and_numeric_dates(text):
    assert has_date_pattern(text) is True


@pytest.mark.parametrize("text", [
    "문의: 인사팀 내선 1234",
    "담당자 김철수, 연락처 010-1234-5678",
    "",
    "회의실 예약은 3층 안내데스크에서 받습니다.",
    "예산은 1,200,000원입니다.",
])
def test_ignores_documents_without_dates(text):
    assert has_date_pattern(text) is False


def test_phone_number_is_not_a_date():
    """010-1234-5678 을 날짜로 읽으면 모든 문서가 날짜를 가진 것이 된다."""
    assert has_date_pattern("연락처 010-1234-5678") is False


def test_amount_with_slash_is_not_a_date():
    assert has_date_pattern("단가 30/개") is False


@pytest.mark.parametrize("text", [
    "8월 중 진행 예정",
    "9월 정기 워크숍",
    "연말에 워크숍을 진행합니다",
])
def test_month_only_is_deliberately_not_a_date(text):
    """일이 없으면 시각을 만들 수 없어 모델의 0건이 정당하다.

    여기서 True 를 내면 그 문서는 재시도해도 영구히 500 이 된다.
    """
    assert has_date_pattern(text) is False


@pytest.mark.parametrize("text", [
    "1/2 분기",
    "1/2 분기에",
    "2/3 이상",
    "2/3 이상은",
    "2/3 이하로",
    "3-4명",
])
def test_fraction_and_ratio_is_not_a_date(text):
    """분수·비율 표현이 뒤따르는 단위 낱말 때문에 날짜로 오판되면 안 된다.

    조사(`에`·`은`·`로`)가 붙어도 두 글자 이상 단위 낱말(분기·이상·이하)은
    배제돼야 한다.
    """
    assert has_date_pattern(text) is False


@pytest.mark.parametrize("text", [
    "제출 기한은 8/5 입니다.",
    "8/5",
    "일정 (8/5)",
    "8/5,",
    "8/5 개최",
    "8/5 명단 제출",
    "8/5 배포",
    "8/5 개강",
    "8/5 이상민 과장 배정",
    "8/5 정도영 대리 참석",
    "8/5 이하영 사원",
])
def test_slash_date_without_trailing_unit_word_is_a_date(text):
    """분수·비율 배제가 정상적인 날짜(8/5)까지 걸러내면 안 된다.

    "개최"·"명단"·"배포"·"개강"은 한 글자 배제 낱말(개·명·배)의 접두어와
    겹치지만, 그 낱말에서 끝나지 않으므로(뒤에 한글이 이어지므로) 배제되면
    안 된다.

    "이상민"·"정도영"·"이하영"은 두 글자 이상 배제 낱말(이상·정도·이하)의
    접두어와 겹치는 사람 이름이다 — 뒤따르는 것이 조사·어미가 아니라 이름
    음절이므로 배제되면 안 된다. "날짜 + 이름 + 직함" 은 사내 문서에 흔한
    서식이고, 이 함수의 거짓 음성은 원본 파일 삭제로 이어진다.
    """
    assert has_date_pattern(text) is True


@pytest.mark.parametrize("text", [
    "# 8월 행사 안내\n- 12일 워크숍 09:00\n",   # 월과 일이 떨어져 있다
    "8월 중 진행 예정",                          # has_date_pattern 은 False
    "12일에 진행합니다",
    "2026년 계획",
    "다음 주 화요일 회의",
    "내일 오전 회의",
    "8월 20일(목) 교육",
])
def test_lenient_probe_accepts_any_date_fragment(text):
    assert has_any_date_evidence(text) is True


@pytest.mark.parametrize("text", [
    "문의: 인사팀 내선 1234",
    "담당자 김철수, 연락처 010-1234-5678",
    "",
    "회의실 예약은 3층 안내데스크에서 받습니다.",
    "카페테리아는 본사 2층에 있으며 음료를 제공합니다.",
])
def test_lenient_probe_still_rejects_documents_without_any_date(text):
    assert has_any_date_evidence(text) is False


def test_the_two_probes_have_different_thresholds():
    """같은 문서에 다른 답을 내는 것이 정상이다 — 쓰임이 반대다."""
    text = "# 8월 행사 안내\n- 12일 워크숍 09:00\n"
    assert has_date_pattern(text) is False
    assert has_any_date_evidence(text) is True
