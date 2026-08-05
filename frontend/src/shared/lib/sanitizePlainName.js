// 사용자명·부서명처럼 일반 이름을 받는 입력에는 문자, 숫자, 공백만 허용한다.
// 유니코드 문자 속성을 사용해 한글과 영문 외 다른 언어의 이름도 불필요하게 차단하지 않는다.
export function sanitizePlainName(value) {
  return value.replace(/[^\p{L}\p{N} ]/gu, '')
}
