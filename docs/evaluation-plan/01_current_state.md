# 01. 현재 구현과 검증 범위 — 근거 기록

> **초안 · 2026-11-10 작성.** 이 문서의 판정은 **정적 코드 열람**으로 내린 것이다. 서버·실제 모델·브라우저를 이번 작업에서 실행하지 않았다.
> "실행 결과"라고 적은 곳은 이 문서 작성 이전, 같은 커밋 내용에서 돌린 `./gradlew test` 결과 파일(`build/test-results/`)을 인용한 것이고 그 사실을 표시했다.

## 0. 기준 상태

| 항목 | 값 |
|---|---|
| 저장소 | `/Users/inwoo/Projects/skala-taxdesk` |
| 브랜치 | `improve/income-overview` (main 미병합 · 원격 미푸시) |
| HEAD | `56bf9bf8002f882c141f53c529a736efe76c89f1` — `docs: README …` |
| 작업 트리 | 깨끗함 (이 문서 작성 전 `git status --short` 출력 없음) |
| 프로젝트 지침 | `AGENTS.md` · `CLAUDE.md` 없음 (저장소 루트 · `.claude/` · 사용자 전역 모두 확인) |
| 주 모델 설정 | `gpt-4o`, `temperature=0.0` · 라우터/폴백 `gpt-4o-mini` · 임베딩 `text-embedding-3-small` (`application.properties:17-19,78-79`) |
| 마지막 자동 테스트 | 126개 통과 — `build/test-results/test/*.xml`, 2026-11-10 13:40 KST, 커밋 `5bb061c`(HEAD 와 코드 동일, README 만 차이) |

## 1. 누가 무엇을 결정하는가 — 서버 통제 워크플로

**모델은 도구를 자율 선택하지 않는다.** 주 `ChatClient` 에는 도구가 등록되어 있지 않다 (`AiConfig.taxDeskChatClient` — `defaultAdvisors` 만 있고 `defaultTools` 없음). 조회·판정·계산은 서버 코드가 정해진 순서로 호출하고, 그 결과를 문자열로 프롬프트에 넣는다.

```
POST /chat (ChatController.ask → 대화방 키 = 로그인계정:방이름)
 └ TaxDeskService.ask
    ① 규칙 버전 검증 실패 → 보류 (모델 X)
    ② 확인 대기 중인 신청이 있으면 예/아니오 판정 → 실행·취소 (모델 X) / 애매하면 만료
    ③ QueryRouter.route — 규칙(거절·SIMULATION·ACTION·기한·계산입력) → 안 걸리면 라우터 모델 1회 → 실패 시 규칙 폴백
    ④ ConversationState.merge — 문장에서 금액 파싱해 상태 저장 (거절보다 먼저)
    ⑤ 거절(OUT_OF_SCOPE) → 고정 문구 (모델 X)
    ⑥ 총급여·매출 언급 + 다른 소득 미상 → 되묻기 (모델 X)
    ⑦ REGULATION 이 아니면 gather(): lookupMyIncome → classifyTaxation → (분리과세면 끝)
         → 다른 소득 미상이면 되묻기 (모델 X) → calculateTax   ※ ACTION 이면 proposeAction(미리보기 저장)
    ⑧ 검색(귀속연도 필터) — REGULATION 인데 0건이면 보류 (모델 X)
    ⑨ 주 모델 1회 호출 (실패 → 폴백 모델 → 둘 다 실패면 503)
    ⑩ groundCitations — 답변의 [출처:] 가 회수 조문·도구 조문에 없으면 답변 전체를 고정 문구로 교체
```

**LLM 이 맡는 범위**: (a) 규칙에 안 걸린 질문의 경로 분류(라우터), (b) 확정 사실·근거를 문장으로 설명. **맡지 않는 것**: 조회 대상 결정, 판정, 세액, 신청 실행 여부. 다만 (b) 에서 모델이 근거에 없는 세법 내용을 **출처 없이** 말하면 코드가 잡지 못한다(§3 참조).

## 2. 이전 리뷰 항목 재확인

판정: **확인됨**(지적이 지금도 맞다) / **해소됨**(코드가 바뀌어 지적이 더는 맞지 않다) / **추가 확인 필요**(정적으로 결론 못 냄).

| # | 항목 | 판정 | 근거 (파일 · 함수/줄) |
|---|---|---|---|
| 1 | 소득현황 조회 화면 연결 | **해소됨** | `static/index.html` `loadOverview()`·`renderOverview()` 가 `GET /income/overview` 를 읽어 그린다. `IncomeController.overview` 는 조회 실패를 `AccountIncomeTools.failed()` (`lookupStatus=FAILED`) 로 돌려준다. 이 세션에서 헤드리스 Chrome 으로 계정 3개 캡처 확인(커밋 `057c290` 시점) |
| 2 | 신청 미리보기·확인·취소 화면 연결 | **해소됨 (부분)** | `index.html` `previewCard()` 가 `pendingAction` 을 카드로 그리고 버튼은 "신청합니다"/"아니요" 문장을 보낼 뿐이다. 실행 판단은 서버 `TaxDeskService.resolvePendingAction` + `Confirmation.isYes/isNo`. **남은 점**: 이전 턴의 카드 버튼이 계속 살아 있고(자기 카드만 비활성화), 요청 처리 중 중복 제출을 막는 장치가 없다 → 2-D4 |
| 3 | 서버가 조회·판정·계산 제어 | **확인됨 (긍정)** | §1. `TaxDeskService.gather` 주석과 구현 일치. 주 모델에 도구 미등록(`AiConfig`) |
| 4 | `golden.json` 비어 있음 · `GoldenSetEvalTest` TODO | **해소됨** | `src/test/resources/golden.json` 52건, `GoldenSetOfflineTest`(기본 test 포함), `GoldenSetEvalTest`(`@Tag("eval")`) 구현 — 커밋 `5bb061c`. 단 온라인 쪽은 **한 번도 실행되지 않았다** |
| 5 | 화면 `calculation.total` ↔ DTO 필드 불일치 | **확인됨 — 결함** | `index.html:276` `won(a.calculation.total)`. `TaxCalculation` 레코드에는 `total` 이 없고 `decidedTax`·`payable`·`localTax` 가 있다. `won()` 은 `(n ?? 0)` 이므로 **세액 계산이 된 모든 답변에서 "결정세액 0원"이 표시될 것으로 추론**된다. 화면 실측은 아직 없다(계산 경로는 모델 호출이 필요해 키 없이 캡처하지 못함) |
| 6 | 누락값의 0원 표시 | **부분 해소 / 일부 확인됨** | 해소: 현황 패널은 타사 `UNKNOWN`→"미확인", 조회 실패→"확인 불가"로 그린다(`renderOverview`). 확인됨: ① #5 로 인한 결정세액 0원 ② **소득공제·기납부세액은 미상과 0을 구분하지 않는다** — `ConversationState.Facts` 의 `deduction`·`prepaidTax` 가 `long`(기본 0), `gather` 가 그대로 `calculateTax` 에 넣고 별도 단서가 없다. 그 결과 근로 기납부세액을 모르는 상태에서도 "추가 납부하실 세액 = …" 이 계산 전개에 확정 표현으로 들어간다(`TaxCalcTools.calculateTax` steps 마지막 줄) |
| 7 | 검색된 근거 vs 실제 인용 근거 · 연도·원문 링크 표시 | **확인됨 (서버 부분 해소, 화면 미반영)** | 서버: `toCitations()` 가 답변의 `[출처:]` 와 대조해 `Citation.used` 를 채우고 `effectiveYear`·`lawVersion`·`verifiedOn`·`excerpt` 를 싣는다. **원문 링크(`DocumentMeta.sourceUrl`)는 `Citation` 에 없다.** 화면: `renderMeta()` 가 `citations` 전부를 "인용한 근거"로 그리며 `used`·연도·버전·링크를 쓰지 않는다 |
| 8 | 알 수 없는 평가 유형이 통과 | **확인됨** | `EvalService.score` `default -> new ItemResult(..., true, ..., "라우팅만 검사")`. 오프라인 러너 `GoldenSetOfflineTest` 도 모르는 `kind` 를 `serviceFlow` 로 보내고, 기대 필드가 하나도 없으면 통과시킨다 |
| 9 | 평가 데이터 필드 미사용 | **확인됨** | `EvalService` 는 `inputs`·`offline`·`turns`·`seed`·`routerReply`·`expectNotice` 등 대부분을 쓰지 않는다. `expectTaxation` 은 `expectAmount==null` 일 때만 본다. `inputs` 를 쓰지 않으므로 온라인 calculation 항목은 다른 소득 미상으로 되묻기에 걸려 "계산 결과 없음"이 될 것으로 추론된다(오프라인 러너는 `inputs` 로 도구를 직접 채점) |
| 10 | 금액이 답변 어딘가에 있으면 통과 | **확인됨** | `EvalService` calculation: `a.answer().contains(formatted)` 하나. 틀린 금액이 함께 있거나, 결정세액을 "납부할 세액"이라고 잘못 부른 답변도 통과한다 |
| 11 | 고정 대화 ID 로 평가 간 상태 혼입 | **확인됨 (온라인), 해소됨 (오프라인)** | `EvalService.score` 는 `"eval-" + item.id()` 를 쓰고 계정 접두가 없다. 같은 Spring 컨텍스트에서 반복 실행하면 `ConversationState`·`ChatMemory`(인메모리, TTL 없음)에 앞 실행의 금액·미리보기가 남는다. 오프라인 러너는 항목마다 `new ServiceHarness()` 라 독립 |
| 12 | 모델 호출 여부를 토큰 수로만 판단 | **확인됨** | `EvalService` safety: `tokens.prompt()==0 && completion()==0`. 문제 ① `TaxDeskService.ask` 는 usage 메타데이터가 없으면 0 으로 채운다 ② `SafetyAdvisor` 차단은 모델 호출 단계 안에서 일어나 토큰 0 이지만 **라우트는 OUT_OF_SCOPE 가 아니고 조회 도구는 이미 실행된 뒤**다. 오프라인 러너는 `StubChatModel.calls()` 로 판단(더 정확) |

## 3. 이번 열람에서 새로 발견한 위험 (정적 추론 — 실행 확인 전)

| ID | 내용 | 근거 | 상태 |
|---|---|---|---|
| N1 | **사용자 정정이 반영되지 않을 가능성.** "아, 3,000만원 아니고 4,000만원이에요"는 항목명이 없어 `OTHER_INCOME` 패턴에 안 걸리고, 되묻기 대기도 끝난 뒤라 `bareAmount` 도 안 쓰인다 → 이전 값 유지. "근로소득금액 3,000만원 아니고 4,000만원"은 `find()` 가 **첫 번째** 금액(3,000만)을 잡는다 | `ConversationState.merge`, `AmountParser.otherIncome`·`find` | 추가 확인 필요 (단위 테스트로 확정 가능) |
| N2 | **"근로소득은 없고 사업소득금액 2,000만원"이 0 으로 읽힐 가능성.** `NO_OTHER_INCOME` 의 `(근로|사업|연금|기타)\s*소득.{0,6}(없|0원)` 이 먼저 맞으면 0L 반환 | `AmountParser.otherIncome` | 추가 확인 필요 |
| N3 | **타사 금액이 누적되지 않고 덮어써진다.** "타사 배당 500만원" 뒤 "다른 은행 이자 300만원도" → 300만으로 교체 | `ConversationState.merge` (`external == null ? p.externalAmount() : external`) | 추가 확인 필요 — 의도(총액 재진술 vs 추가)를 되물을지 설계 결정 필요 |
| N4 | **모델 장애 시 보이지 않는 미리보기가 남는다.** ACTION 경로는 `gather` 안에서 `state.proposeAction` 을 먼저 저장하고 그 뒤 `callModel` 이 실패하면 503 이 나간다. 고객은 미리보기를 못 봤는데 서버에는 확인 대기가 남아, 다음 턴의 "네"가 실행으로 이어질 수 있다 | `TaxDeskService.proposeAction` → `ask` 의 `callModel` | 추가 확인 필요 (모델 실패 대역으로 결정적 재현 가능) |
| N5 | **미리보기에 식별자·만료 시각 검사가 없다.** `PendingAction.proposedAt` 은 기록만 되고 쓰이지 않는다. 확인 메시지는 "지금 걸려 있는 미리보기"에 적용되며 어떤 미리보기에 대한 확인인지 구분하지 않는다 | `ConversationState.PendingAction`, `resolvePendingAction` | 확인됨 (구조) / 영향은 추가 확인 |
| N6 | **동시 확인 경합.** `resolvePendingAction` 은 상태 읽기 → `clearAction` → 중복 조회 → 저장 순서이고 하나의 원자적 연산이 아니다. 같은 방에 "신청합니다" 두 건이 동시에 오면 둘 다 실행될 여지가 있다 | 같은 함수, `findPending` | 추가 확인 필요 (동시성 테스트) |
| N7 | **문서에 심은 지시(간접 인젝션) 방어는 구조적 방어뿐이다.** 검색 문서 본문은 검사 없이 system 슬롯에 들어간다(`compose`). `SafetyAdvisor` 는 고객 원문만 본다. 도구가 모델에 없으므로 데이터 접근 확대는 불가능하지만, **답변 문장이 지시를 따를 가능성**은 남는다 | `TaxDeskService.compose`, `SafetyAdvisor.adviseCall` | 추가 확인 필요 (L2) |
| N8 | **연도 다른 질문을 감지하지 않는다.** 검색은 `props.taxYear()`(2026) 고정 필터이고, "2025년 귀속은요?" 를 별도로 처리하지 않는다 → 2026 근거로 답할 수 있다 | `TaxDeskService.search`, `QueryRouter` | 추가 확인 필요 (L2) |
| N9 | **출처 없이 말한 세법 주장은 검사되지 않는다.** `groundCitations` 는 `[출처:]` 표기만 대조한다. 출처를 안 붙이면 통과 | `TaxDeskService.groundCitations` | 확인됨 (구조) |
| N10 | 기존 문서 `docs/QUESTIONS.md` §1 표의 isabu ② 분리과세 산출세액 `14,266,981` 이 독립 계산(`16,213,981`)과 다르다. 결정세액(16,250,660)은 일치 | 문서 표 · 이 작업의 독립 계산 | 추가 확인 필요 — 기대값 출처로 쓰기 전에 재검토 |

## 4. 지금 있는 자동 검증 (126개, 모두 모델 없이 동작)

| 영역 | 테스트 (건수) | 보는 것 | 보지 않는 것 |
|---|---|---|---|
| 계산 | `TaxCalcToolsTest`(16, 중첩 클래스 포함) | 경계 2천만원, 배당가산 min, 비교과세, 시드 5명 | 실제 운영 설정 값(테스트 설정 사용) — 운영 설정은 `GoldenSetOfflineTest` 가 봄 |
| 입력 파싱 | `AmountParserTest`(9), `ConversationStateTest`(5) | 한글 금액, 타사 0/미상, 되묻기 | 정정(N1), 복수 종류(N2), 누적(N3) |
| 라우팅 | `QueryRouterTest`(24) | 규칙 거절·경로 | 실제 라우터 모델의 분류 |
| 서비스 흐름 | `TaxDeskServiceFlowTest`(20), `TaxDeskServiceWriteTest`(7) | 모델 미호출 보류, 신청 확인·취소·만료·중복·실패 | 모델 장애 중 미리보기(N4), 동시성(N6) |
| 근거 | `CitationGroundingTest`(6), `IngestServiceTest`(5) | 회수 안 된 조문 인용 차단, 메타데이터 | 주장-근거 적합성(N9) |
| 권한 | `OwnerIsolationTest`(5), `ChatControllerSecurityTest`(6) | 소유자 쿼리, 대화방 계정 범위, 401/403 | — |
| 안전 | `AdvisorOrderTest`(11) | 인젝션 차단·마스킹 순서 | 서비스 경유 시 차단 후 응답 형태(#12) |
| 설정 | `TaxDeskPropertiesTest`(5) | 세율표 검증, 운영 설정 한글 | — |
| 평가셋 | `GoldenSetOfflineTest`(1 테스트, 46 항목) | spec 31건 + 미검토 세액·판정 15건 | 조문 인용 6건(온라인 전용, 미실행) |
| 화면 | **없음** | — | DTO↔화면 필드, 숫자 일치, 흐름 |

## 5. 요약 — 검증이 비어 있는 곳

1. **화면 계약** — 자동 테스트가 0건이고, 정적으로 결함 1건(#5)과 표시 누락(#7)이 보인다.
2. **실제 모델 경로** — 조문 인용·의미 정확성·되묻기 문장을 잰 적이 현 HEAD 에서 없다(`docs/RESULTS.md` 는 2026-08-21 과거 조건의 기록).
3. **평가기 자체** — 모르는 유형 통과(#8), 금액 포함 여부만 확인(#10), 고정 대화 ID(#11), 토큰 기반 판단(#12).
4. **멀티턴 입력 정정·누적** — N1~N3.
5. **쓰기 경로의 장애·동시성** — N4~N6.
