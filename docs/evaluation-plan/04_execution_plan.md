# 04. 실행 계획 — 순서 · 환경 · 비용 · 전후 비교

> **초안 · 아직 아무것도 실행하지 않았다.** 이 문서의 코드 변경은 전부 **제안**이다. 유료 모델 실행(L2)은 §4 의 조건을 사용자가 승인한 뒤에만 한다.

## 1. 실행 순서

비용이 0 이고 결정적인 것부터 한다. 앞 단계가 흔들리면 뒤 단계 점수는 의미가 없다.

| 단계 | 내용 | 층 | 비용 | 산출물 |
|---|---|---|---|---|
| 0 | **기준선 고정** — 커밋 해시, `application.properties`·`AiConfig.SYSTEM`·`tax-docs/` 의 SHA-256, JDK·Gradle 버전 기록. `./gradlew test` 1회 | — | 0 | `runs/<날짜>-baseline/manifest.json` |
| 1 | **평가기 검증** — 02 문서 §4.2 의 EV-01~EV-11 메타 테스트. 평가기가 틀린 입력을 거부·실패로 내는지 | L1 | 0 | 메타 테스트 결과 |
| 2 | **L1 사례 실행 (수정 전)** — 24개 중 L1 부분. 실패를 고치지 않고 그대로 기록 | L1 | 0 | `baseline-L1.md` (통과·실패·판정 불가·미실행 분리) |
| 3 | **L3-자동 (수정 전)** — 고정 응답으로 화면 그리기. CA-04·UI-01·UI-02·UI-03·UI-04 | L3 | 0 | DOM↔API 비교표, 스크린샷 |
| 4 | **L2 파일럿 (승인 후)** — 3턴만 실제 모델로 돌려 턴당 토큰·지연 측정 → 전체 비용 추정 갱신 | L2 | 소액 | 측정치, 갱신된 비용 상한 |
| 5 | **L2 본 실행 (승인 후, 수정 전)** — dev 사례만, 반복 n회 | L2 | §4 | `baseline-L2.md` + 사람 판정 기록 |
| 6 | **실패 분류** — 실패마다 원인 계층(02 문서 §3) 지정, 수정 대상 2건 선정 | — | 0 | 실패 목록 |
| 7 | **수정** — 한 번에 한 원인. 수정마다 L1 테스트 추가 | — | 0 | 커밋 |
| 8 | **재검증 (같은 조건)** — 단계 2·3·5 를 같은 입력·기대값·설정 해시로 반복 | L1·L2·L3 | §4 | `after-*.md`, 전후 표 |
| 9 | **holdout 개봉** — 개발 중 보지 않은 7개 사례(RG-03·CA-03·MT-02·AU-04·FA-03·FA-04·UI-03)를 마지막에 한 번 | 전부 | §4 일부 | holdout 결과 |
| 10 | **L3-사람** (선택) — 합성 계정으로 3~5명, UI-03·UI-04 과제와 이해도 질문 | L3 | 0 | 과제 성공·응답 기록 |

> holdout 7개 중 MT-02·AU-04·FA-03 은 정적 열람으로 이미 결함이 의심된다(01 문서 N1·N5·N6·N4). 이 셋을 수정 대상으로 고르면 holdout 이 개발에 쓰인 셈이 된다. **수정 대상을 고르기 전에 ★ holdout 을 다시 정할지 결정**이 필요하다(§6 질문 1).

## 2. 환경

| 항목 | L1 | L2 | L3-자동 |
|---|---|---|---|
| 모델 | `StubChatModel` (네트워크 없음) | `gpt-4o`(T=0.0) · 라우터/폴백 `gpt-4o-mini` · 임베딩 `text-embedding-3-small` | 고정 응답(제안: 테스트 프로필의 대역 `ChatModel`) |
| 데이터 | `ServiceHarness` 합성 시드 | `DataSeeder` 합성 시드 (`antman`~`americano`) | 같음 |
| 색인 | `FakeVectorStore` | `SimpleVectorStore` 인메모리, `/admin/ingest` 매 실행 | 필요 없음(고정 응답) |
| 상태 초기화 | 케이스마다 `new ServiceHarness()` | 서버 재기동 + 대화방 이름 `{caseId}-{runId}` | 새 브라우저 프로필 + 재기동 |
| 실행 | `./gradlew test` | `./gradlew test -Peval` (키 필요) | 브라우저 자동화(§5 도구) |
| 비밀 | 없음 | `OPENAI_API_KEY_SKALA1` 환경변수만. 보고서·로그·캡처에 남기지 않음 | 없음 |

## 3. 기록 형식 (실행마다)

```json
{
  "runId": "2026-11-xx-L2-baseline-1",
  "commit": "<sha>", "dirty": false,
  "configSha256": "...", "systemPromptSha256": "...", "taxDocsSha256": "...",
  "models": {"main": "gpt-4o", "temperature": 0.0, "router": "gpt-4o-mini", "embedding": "text-embedding-3-small"},
  "retrieval": {"topK": 4, "threshold": 0.25, "chunks": 39},
  "startedAt": "...", "repeats": 3,
  "results": [
    {"caseId": "RG-03", "subcase": "-", "repeat": 1, "requestId": "...",
     "route": "REGULATION", "toolCalls": ["-"], "retrieved": [{"doc": "02", "article": "...", "score": 0.0}],
     "usedEvidence": ["..."], "modelCalls": {"router": 1, "main": 1, "fallback": 0, "embedding": 1},
     "tokens": {"in": 0, "out": 0}, "latencyMs": 0, "stateChanges": [], "writes": 0,
     "verdict": "pass|partial|fail|undeterminable|error|not-run", "verdictBy": "code|human|model-judged", "notes": "..."}
  ]
}
```

답변 원문은 별도 파일로 두고 결과 행에는 요청 ID 로만 연결한다. 숨겨진 추론은 요청·저장하지 않는다.

## 4. 유료 실행(L2) — 승인 요청 양식

아래는 **추정**이다. 정확한 토큰은 단계 4 파일럿으로 측정한 뒤 갱신한다. 단가는 실행 당일 공급자 가격표에서 확인해 조회일과 함께 기록한다(이 문서에 단가를 적지 않는다 — 확인하지 않은 값이기 때문).

| 항목 | 값 |
|---|---|
| 대상 | L2 반복이 잡힌 11개 사례 (CA-03·MT-03 은 L2 표기가 있으나 반복 0 — L1 로만 판정). dev 8개는 단계 5·8, holdout 3개(RG-03·MT-02·FA-04)는 단계 9 |
| 턴 수 (반복 포함) | **dev 42턴 · holdout 37턴 · 합계 79턴** — 하위 사례 × 대화 턴 × `repeatsL2`, `03_cases_draft.json` 의 `runs` 로 계산. FA-02(검색 장애)·FA-04-c(오염 문서)는 실서버에서 주입이 어려워 L2 에서 빼면 dev 40턴 |
| 턴당 호출 | 라우터 ≤1 · 질의 임베딩 ≤1 · 주 모델 ≤1 · (장애 시 폴백 ≤1) |
| 호출 상한 (1세트) | dev: 주 모델·라우터·임베딩 각 ≤42회 / holdout: 각 ≤37회 / 색인 임베딩: 청크 39개 × 실행 세트 수 |
| 반복 | 사례별 2~5회(`runs.repeatsL2`). dev 는 전·후 2세트(84턴), holdout 은 수정 후 1세트(37턴) → **총 약 121턴**, 주 모델 호출 ≤121회 |
| 비용 상한 | **★ 사용자 지정** (파일럿 측정 후 "1세트 예상액 × 3 + 여유"를 제안값으로 제시) |
| 중단 조건 | 비용 상한 도달 · 연속 오류 5건 · 권한 침해 1건 · 승인 없는 실행 1건 |
| 기록 | §3 형식. 미실행·오류는 따로 셈 |

## 5. 필요한 도구와 코드 변경 — 제안 (이번에는 하지 않음)

### 5.1 평가기 (EvalService · GoldenSet · 오프라인 러너)

| 변경 | 이유 | 관련 |
|---|---|---|
| 로딩 시 `kind` 화이트리스트 검사, 기대 필드 0개 항목 거부 | 모르는 유형·빈 항목이 통과 (01 #8) | `EvalService.score` default 분기, `GoldenSetOfflineTest.serviceFlow` |
| `passRate()` 를 `OptionalDouble` 로 — 분모 0 이면 "미측정" | 0.0 과 미측정 구분 (EV-03) | `EvalService.EvalReport` |
| 대화방 키를 `user + ":eval-" + id + "-" + runId` 로 | 실행 간 상태 혼입 (01 #11) | `EvalService.score` |
| 모델 호출 판단을 토큰이 아니라 호출 기록으로 — 응답에 `modelInvoked`·`blocked` 추적 필드 추가(또는 평가 전용 리스너) | 토큰 0 ≠ 미호출 (01 #12) | `AnswerDto`, `SafetyAdvisor.blocked`, `TokenMeterAdvisor` |
| 금액 채점을 "라벨-값 쌍" 추출로 — 결정세액·납부·환급·지방세 각각 | 답변 어딘가 포함이면 통과 (01 #10, EV-04·05) | `EvalService` calculation |
| 인용 채점에 귀속연도 비교 추가 | 연도 틀린 인용 통과 (EV-06) | `EvalService` regulation |
| 예외를 `error` 로 별도 집계 | 실패와 오류 구분 (EV-09) | `EvalService.score` |
| 온라인 calculation 이 `inputs` 를 `ask(..., otherIncome, deduction, prepaidTax)` 로 전달 | 지금은 되묻기에 걸려 계산이 안 됨 (01 #9) | `EvalService.score` |
| `ServiceHarness` 확장: 검색 장애 대역, 모델 장애 대역(주·폴백), 동시 요청 헬퍼 | FA-02·FA-03·AU-04 결정적 재현 | `support/` |

### 5.2 애플리케이션 (사례가 실패를 확인한 뒤에만)

| 변경 | 관련 사례 |
|---|---|
| `index.html` 계산 패널: `calculation.decidedTax` 사용, 추가 납부/환급(`payable`) 줄 추가 | CA-04 (정적 확인된 결함) |
| 근거 패널: `used` 로 "사용한 근거 / 참고 자료" 분리, 귀속연도·LAW/INTERNAL 표시, `Citation` 에 `sourceUrl` 추가 | UI-02 |
| `deduction`·`prepaidTax` 를 `Long`(미상 null)으로, 미상이면 되묻기 또는 "0원 가정" notice | CA-03 |
| 정정 문장 처리(마지막 금액 채택 또는 되묻기), 복수 종류 소득 합산, 타사 금액 누적 vs 총액 되묻기 | MT-02·MT-03 |
| 미리보기 저장을 모델 응답 성공 이후로 옮기거나 실패 시 폐기 | FA-03 |
| 확인 처리 원자화(대화방 단위 잠금 또는 `compute` 안에서 pending 을 꺼내며 비움), 미리보기 ID·만료 | AU-04 |
| 질문 연도 감지 → 지원 연도 밖이면 보류 또는 연도 명시 | RG-01 |
| 차단 응답 형태 통일(route·조회 실행 여부) | FA-04 |

### 5.3 L3 도구

| 선택지 | 장점 | 비용·제약 |
|---|---|---|
| **Chrome DevTools Protocol 스크립트 (Node 내장 WebSocket)** — 이 세션에서 캡처에 쓴 방식 | 설치 없음, 이미 동작 확인 | 단언·리포트를 직접 짜야 함 |
| Playwright | 대기·단언·트레이스 내장 | 브라우저 바이너리 다운로드 필요(★ 설치 승인) |
| 고정 응답 테스트 프로필 (`ChatModel` 대역 빈) | L3 를 무료·결정적으로 | 애플리케이션에 테스트 전용 프로필 추가 |

## 6. 새 JSON 스키마 vs 기존 평가기 스키마

`03_cases_draft.json` 은 **사람이 읽고 합의하기 위한 초안**이다. 지금의 `GoldenSetOfflineTest`·`EvalService` 로 **실행할 수 없다.**

| 항목 | 기존 `golden.json` (`GoldenSet.Item`) | 초안 `03_cases_draft.json` |
|---|---|---|
| 단위 | 질문 1개 = 항목 1개, 평평한 `expect*` 필드 | 사례 1개 = 목적·위험·층·대화 순서·하위 사례 |
| 대화 | `turns`(앞 턴 문장) + `question` | 턴별 `user`·`as`(계정)·`room`·`action`(클릭)·`expect` |
| 판정 | 통과/실패 2값 | `pass`·`partial`·`fail`·`undeterminable` (+ 실행 시 `error`·`not-run`) |
| 상태 | `expectWrites`·`expectPending` | `allowedStateChanges`·`forbiddenStateChanges` (자연어 — 변환 시 구조화 필요) |
| 층 | `offline` 참/거짓 | `layers` L1·L2·L3 + 층별 자동화 범위 |
| 검토 수준 | `spec`·`self-checked`·`reviewed`·`unreviewed` — **self-checked 는 통과율에 포함** | `spec`·`self-check`·`official-doc`·`independent`·`expert`·`unreviewed` — **self-check 는 불포함** |
| 분할 | `tuning`·`holdout` | `dev`·`holdout` |
| 기타 | — | `risk`·`failureImpact`·`expectedErrorLayers`·`openQuestions`·`runs`(하위 사례 수 vs 반복 수) |

변환 방향(제안): ① 사례의 L1 부분 → `golden.json` 의 `flow`/`calculation` 항목으로 쪼개고 필요한 필드(장애 주입·동시성·상태 단언)를 `GoldenSet.Item` 에 추가 ② L2 부분 → 개정한 `EvalService` 입력 ③ L3 부분 → 별도 UI 시나리오 파일. 기존 `golden.json` 52건은 덮어쓰지 않고 유지한다.

★ 검토 수준 정의가 두 스키마에서 다르다(self-check 의 분모 포함 여부). 하나로 정해야 한다.

## 7. 전·후 비교 체크리스트

- [ ] 같은 커밋에서 바뀐 것은 수정 1건뿐인가 (`git diff --stat` 첨부)
- [ ] 설정·프롬프트·문서 해시가 전과 같은가
- [ ] 같은 사례 ID·하위 사례·반복 수인가
- [ ] 대화방·서버·브라우저를 초기화했는가
- [ ] 비결정 사례의 n회 중 통과 수를 그대로 적었는가 (최고값만 고르지 않았는가)
- [ ] 미검토·미실행·오류를 분모 밖에 따로 적었는가
- [ ] holdout 을 수정 후 한 번만 열었는가
