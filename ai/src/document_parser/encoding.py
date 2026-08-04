"""CSV 바이트열의 인코딩을 결정적으로 판별한다 — 추측이 아니라 검증이다.

BOM 이 있으면 UTF-8 이 확정이다. 없으면 UTF-8 엄격 디코딩을 시도한다 — UTF-8 은
바이트 구조가 엄격해서 규칙에 안 맞는 바이트가 하나라도 있으면 반드시 예외가
난다. 그래서 이 시도가 성공하면 "그럴듯해서 골랐다"가 아니라 "검증을 통과했다"는
뜻이다. 실패하면 한국어 엑셀이 내보내는 CSV 의 실질적인 유일한 대안인 CP949 를
시도한다. 그것도 실패하면 조용히 깨진 텍스트를 만들지 않고 예외로 알린다.
"""

_UTF8_BOM = b"\xef\xbb\xbf"


def decode_csv_bytes(raw: bytes) -> str:
    if raw.startswith(_UTF8_BOM):
        return raw[len(_UTF8_BOM):].decode("utf-8")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        pass
    try:
        return raw.decode("cp949")
    except UnicodeDecodeError as exc:
        raise ValueError("인코딩을 판별할 수 없는 CSV입니다") from exc
