# 구현에 사용한 키움 조회 API

2026-10-09 공식 API 명세 확인. 원문은 [키움 공식 저장소](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json)에서 확인할 수 있습니다. 아래는 앱 구현 시 선택한 요청·저장 기준입니다.

| API | 요청 기준 | 읽는 배열 |
|---|---|---|
| kt00015 | 국내주식 매매, KRW, 모든 거래소, 거래일 기간 | trst_ovrl_trde_prps_array |
| ka10074 | 실현 손익 일자 기간 | dt_rlzt_pl |
| ka10073 | 종목별 손익 일자 기간, 선택 항목인 종목코드는 생략 | dt_stk_rlzt_pl |
| kt00004 | KRX, 상장폐지 포함 | stk_acnt_evlt_prst |

공통 조회 경로: `/api/dostk/acnt`. 토큰 발급 경로: `/oauth2/token`. HTTP 헤더 `api-id`로 조회 종류를 선택하며 응답의 `cont-yn=Y`이면 `next-key`를 전달해 연속 조회합니다. 조회 사이에 최소 1.1초를 두어 모의투자 호출 제한에도 여유를 둡니다.

실현 손익은 키움 반환값을 그대로 사용합니다. 거래 내역은 거래일과 체결일을 구분합니다. 실제 계좌·지원 조회 기간·빈 계좌 응답 등은 실기기 연동에서 추가 확인할 수 있습니다.
