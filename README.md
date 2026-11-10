# SKALA증권 세무상담 데스크 (TaxDesk)

가상 증권사 고객이 **내 금융소득(이자·배당)을 한눈에 확인하고, 2천만원 기준 종합과세 가능성과 다음에 할 일을 이해하도록** 돕는 상담 데모다.
Spring Boot + Spring AI. 근거는 RAG(법령 요약 문서), 판정과 세액은 결정적 코드(도구)가 맡고, 모델은 둘을 문장으로 엮기만 한다.

> **실습용 데모다.** 계좌 데이터는 전부 합성이고, 세액은 실습용 예상치이며 세무 자문이 아니다.
> 세액 계산 규칙은 세무 전문가 검토를 거치지 않았다 — [한계](#한계)를 먼저 읽을 것.

## 무엇을 보여주나

- **확인 범위와 빠진 부분을 구분한다.** 자사·연동 기관 금액, 판정에서 빠진 비과세·분리과세 금액, 타사 금액(미확인 / 고객이 없다고 답함 / 고객이 말한 금액)을 따로 보여준다. 모르는 값을 0원으로 그리지 않는다.
- **모르면 계산하지 않는다.** 다른 종합소득을 모르면 되묻고, 총급여·매출을 말하면 '소득금액'을 다시 묻는다. 계좌 조회 실패·근거 없음·규칙 버전 미검증이면 판정을 보류한다.
- **쓰기는 확인 후에만.** 절세상품 자격확인 신청은 미리보기 → "신청합니다" 확인 → 접수. 실제 가입 시스템과는 연결되어 있지 않다(실습용 내부 기록).
- **계정 경계.** 계좌 조회·대화방·현황 API 모두 로그인 계정으로 범위가 묶인다. `admin` 은 상담 API 를 쓸 수 없다.

## 빠른 실행

요구: Java 21. 상담 답변 생성에는 OpenAI API 키가 필요하다(유료).

```bash
export OPENAI_API_KEY_SKALA1=sk-...          # 키 이름이 이것이다 (application.properties)
./gradlew bootRun
curl -u admin:admin -X POST localhost:8080/admin/ingest   # 근거 문서 색인 — 재시작할 때마다 다시
open http://localhost:8080/index.html
```

브라우저 Basic 인증 창이 뜨면 아래 계정으로 로그인한다(`/swagger-ui.html` 에서 먼저 로그인해도 된다).

| 계정 / 비밀번호 | 시드 시나리오 |
|---|---|
| `antman` / `pass1` | 2천만원 이하 — 분리과세 종결 |
| `isabu` / `pass2` | 비과세·분리과세 상품을 빼면 판정이 바뀌는 경우 |
| `jonber` / `pass3` | 비교과세에서 ② 선택 |
| `richman` / `pass4` | 고소득 · 배당가산 |
| `americano` / `pass5` | 해외배당 위주 |
| `admin` / `admin` | 관리자 — 색인·신청 승인·사용량 (`/console.html`) |

**키 없이도 되는 것:** 서버 기동(`OPENAI_API_KEY_SKALA1=dummy`), 내 금융소득 패널(`GET /income/overview`), 거절·되묻기·보류처럼 모델을 거치지 않는 응답, 모든 테스트와 오프라인 평가. 색인(`/admin/ingest`)과 일반 상담 답변은 키가 필요하다.

## 테스트와 평가

```bash
./gradlew test                       # 모델·키·네트워크 없이 돈다 (현재 126개)
open build/eval/offline-report.md    # 위 명령이 함께 만드는 평가 보고서

# 유료 — 실제 모델·색인으로 조문 인용을 채점한다. 기본 test 에서는 빠져 있다.
OPENAI_API_KEY_SKALA1=sk-... ./gradlew test -Peval --tests '*GoldenSetEvalTest*'
```

평가셋은 [`src/test/resources/golden.json`](src/test/resources/golden.json) 52건이다. 스키마와 검토 상태 정의는 [`GoldenSet.java`](src/main/java/com/inwoo/taxdesk/eval/GoldenSet.java).

| 종류 | 건수 | 기대값의 출처 | 통과율에 넣나 |
|---|---:|---|---|
| 서비스 규칙 (라우팅·차단·되묻기·보류·신청 확인·격리) | 31 | 개선계획의 요구사항 (`spec`) | 넣는다 |
| 판정·세액 | 15 | 법령 구조로 별도 계산, **전문가 검토 전** (`unreviewed`) | 빼고 일치 여부만 보고 |
| 조문 인용 | 6 | 문서 머리말의 출처 조문, 검토 전 | 빼고 적중 여부만 보고 (모델 필요) |

세액 항목의 "일치"는 *독립 계산과 구현이 같은 값을 냈다*는 뜻이지 세무상 정답이라는 뜻이 아니다. 각 항목의 `basis` 에 계산 전개와 가정이 적혀 있다 — 검토 후 `reviewStatus` 를 바꾸면 통과율에 들어간다.

## 구조

```text
src/main/java/com/inwoo/taxdesk/
├── chat/      대화 흐름(TaxDeskService) · 라우팅 · 대화 상태 · 금액 파싱 · 응답 계약(AnswerDto)
├── rag/       색인(IngestService) · 문서 메타데이터(귀속연도·시행본·원문·확인일)
├── tools/     계좌 조회 · 판정/세액 계산(TaxCalcTools) · 신청
├── advisor/   입력 안전 · 개인정보 마스킹 · 감사 로그
├── web/       HTTP · 보안(SecurityConfig) · 현황 API(IncomeController)
├── eval/      평가 채점(EvalService) · 평가셋 스키마
└── config/    규칙 설정(TaxDeskProperties) · 합성 데이터 시드
src/main/resources/
├── tax-docs/              검색용 문서 9개 (01~07 법령 요약, 08~09 가상 증권사 자체 안내)
├── static/index.html      고객 상담 화면 + 내 금융소득 패널
├── static/console.html    실험·관리 콘솔
└── application.properties 세율표·기준금액·규칙 버전 — 세법이 바뀌면 코드가 아니라 여기가 바뀐다
```

## 한계

- **세무 정확성 미검증.** 세율표·기준금액·배당가산·비교과세는 소득세법 원문과 대조했다고 기록돼 있으나(`docs/SOURCES.md`), 부칙 적용례와 고배당기업 배당소득 특례(조특법 §104의27)는 확인되지 않았다. 특례는 자동 적용하지 않고 안내만 한다.
- **데이터는 합성이다.** 자사·연동 기관 계좌는 시드 데이터이고 실제 마이데이터 연동은 없다. 타사 금액은 유형 구분 없이 합계 하나로 받는다.
- **공제·기납부세액은 미상과 0을 구분하지 않는다.** 다른 종합소득은 구분하지만, 공제와 기납부세액은 말하지 않으면 0으로 계산된다.
- **휘발성.** H2 와 벡터 스토어가 인메모리라 재시작하면 색인·대화·신청 기록이 사라진다.
- **데모 보안.** 평문 비밀번호의 인메모리 계정, Basic 인증, CSRF 비활성화. 그대로 공개 배포할 수 있는 구성이 아니다.
- **화면.** 정적 HTML 하나다. 로그인 화면이 없고(브라우저 Basic 인증), 새로고침하면 대화가 새로 시작되며, 답변은 스트리밍되지 않는다.

## 문서

현행 설명은 이 README 다. `docs/` 의 문서는 **이력**이다 — 작성 당시 조건의 기록이며 현재 코드의 재현 결과가 아니다.

| 문서 | 내용 |
|---|---|
| [docs/PLAN.md](docs/PLAN.md) | 최초 구현 계획 (Phase 1~8) |
| [docs/RESULTS.md](docs/RESULTS.md) · [docs/EXPERIMENTS.md](docs/EXPERIMENTS.md) | 과거 수동 실험과 실패 기록 |
| [docs/RUNBOOK.md](docs/RUNBOOK.md) · [docs/QUESTIONS.md](docs/QUESTIONS.md) | 콘솔 실험 절차와 질문·기대값 |
| [docs/SOURCES.md](docs/SOURCES.md) · `docs/raw_law/` | 법령 출처와 원문 |
| [docs/REPORT.md](docs/REPORT.md) | 최종 보고서 (과거 시점) |
