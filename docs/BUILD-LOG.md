# SKALA증권 세무상담 데스크 — 구축 로그

> 보고서용 작업 기록. 무엇을 했는가보다 **왜 그렇게 했는가**와 **무엇을 선택하지 않았는가**를 남긴다.
> 기획은 [PLAN.md](PLAN.md), 여기는 그 기획에 도달하기까지의 과정과 그것을 코드로 옮기는 과정이다.

---

## Step 1 — `final/` 계층 구조 스캐폴딩

### 목표
PLAN.md 5절의 패키지 구조를 실제 디렉터리로 만들고 `./gradlew compileJava` 가 통과하는
빈 골격까지 세운다. 이후 Phase 1~8은 이 골격의 TODO를 채우는 작업이 된다.

### 결정 사항

| 결정 | 선택 | 이유 |
|---|---|---|
| 스캐폴딩 깊이 | 컴파일되는 빈 스텁 | 폴더만 만들면 구조가 말이 되는지 확인할 수 없다. 컴파일이 통과하면 최소한 타입 수준의 모순은 없다는 뜻이다. |
| `practice4/` 처리 | 삭제하지 않고 보존 | 2차안(환불 정산)의 완성된 스캐폴딩. 방향 재전환 대비 + 같은 설계 원리의 이식 가능성 증거. |
| 빌드 파일 | practice3 `build.gradle` 기반 | Spring Boot 4.1.0 · Spring AI 2.0.0 · Java 21 조합을 practice3에서 이미 검증했다. 여기서 버전을 새로 고르는 것은 실습 목적과 무관한 위험이다. |
| `common/` 패키지 | practice3에서 이식 | Phase 6(구조화 응답)·Phase 8(폴백)에서 `ModelUnavailableException` → 503 경로가 필요하다. 나중에 급하게 넣으면 예외 처리가 컨트롤러마다 흩어진다. |
| UI | Swagger + 단일 HTML | 역할이 다르다. Swagger는 **계정을 바꿔가며 소유자 격리를 시연**하는 검증 도구, `index.html`은 **근거와 계산을 눈으로 보여주는** 데모. 빌드 도구 없는 바닐라 HTML+JS라 실습 본질(RAG·Tool·안전)에서 시간을 뺏지 않는다. |
| 기록 형식 | `docs/BUILD-LOG.md` 단일 파일 | Phase별로 쪼개면 "Phase 4 결정이 Phase 7에 미친 영향" 같은 걸 쓸 자리가 없다. |

### practice3 대비 변경점

| 항목 | practice3 | final | 왜 |
|---|---|---|---|
| 패키지 축 | `lab1`/`lab2`/`lab3` (수업 회차) | `config`/`rag`/`tools`/`advisor`/`chat` (기능) | 회차는 배우는 사람의 시간 순서지 시스템의 구조가 아니다. 같은 부품이 lab2·lab3에 흩어져 있던 것을 기능 축으로 모았다. |
| 루트 패키지 | `com.inwoo.practice3` | `com.inwoo.taxdesk` | 실습 번호가 아니라 도메인 이름. |
| DB 의존성 | H2 + PostgreSQL | H2만 | PostgreSQL은 practice3에서도 실제로 쓰이지 않았다. 안 쓰는 드라이버를 남기면 "이 프로젝트는 PG를 쓴다"는 잘못된 신호가 된다. |
| 세율표 | — | `taxdesk.brackets[0..7]` 설정 | 코드 상수로 두면 세법 개정이 배포가 된다. 설정으로 두면 교체가 된다. 같은 표를 RAG 문서 04번에도 넣고 골든셋이 둘의 일치를 검증한다. |
| 출처 단위 | 파일명 | 파일명 + 조문 + 귀속연도 (`DocumentMeta`) | 세법 답변에서 "shipping-policy.md 참고"에 해당하는 출처는 출처가 아니다. "소득세법 §14③ (2026 귀속)"까지 가야 한다. |
| `Masker` 위치 | `lab3/service/` | `advisor/` | 마스킹은 업무 로직이 아니라 SafetyAdvisor가 쓰는 도구다. |
| `SecurityConfig` 위치 | `lab3/config/` | `web/` | 보안은 설정이 아니라 웹 계층의 관문이다. |

### 주체 결정이 코드에 남긴 흔적

증권사로 정한 것이 문서상의 서술로만 끝나지 않게, 코드 구조에 박아 넣었다.

| 위치 | 무엇을 했나 | 무엇을 막나 |
|---|---|---|
| `AccountIncome` (엔티티명) | `FinancialIncome` → `AccountIncome`, `institution` 필드 삭제 | 자사 계좌만 담긴다. 타사 데이터가 들어올 컬럼 자체가 없다 |
| `AccountIncomeTools` | 파라미터에 기관명·고객ID를 **두지 않음** | "타사는 조회 마"를 프롬프트로 부탁하는 대신 부를 수 없게 만든다 |
| `AccountIncomeRepository` | `ownerId` 없는 조회 메서드 추가 금지를 Javadoc에 명시 | 편의 메서드 하나가 나중에 감사 로그에서 사고로 발견되는 것 |
| `Route.OUT_OF_SCOPE` | 거절을 업무 범위 밖 / 권한 밖 두 갈래로 문서화 | 권한 밖 거절에 대안이 빠지는 것 |
| `IncomeType` (신규 enum) | 내국법인 / 외국법인 배당 구분 | Gross-up 대상을 잘못 잡는 것 (외국법인 배당은 제외) |
| `tax-docs/09-상담범위-안내.md` (신규) | "안내할 수 없는 것"을 문서로 명시 | 거절 응답에 출처가 안 붙는 것 |
| `TaxCalcTools` | `externalAmount`를 "고객 제공"으로 표시하도록 명세 | 확인된 값과 고객이 말한 값이 답변에서 섞이는 것 |

마지막 줄이 특히 중요하다. 증권사는 금융소득의 일부만 알기 때문에,
**어디까지가 우리가 확인한 값인지**를 계산 전개에서 구분해야 한다.
국세청 안이었으면 생기지 않았을 요구사항이다.

### 계획에 없었지만 추가한 것

- **`domain/IncomeType`** — PLAN 초안은 `boolean domesticDividend` 였다. 외국법인 배당까지
  구분해야 Gross-up 대상 판정이 정확해져서 enum으로 승격했다.
- **`ConsultationRepository.findByStatus`** — 승인 대기 목록 조회용. 이 인터페이스에서 **유일하게
  소유자 조건이 없는 메서드**라 Javadoc에 "ADMIN 경로에서만 쓴다"를 명시해 두었다.
- **`Bracket.isTop()` / `effectiveUpTo()`** — 최고 구간 상한을 `9223372036854775807`로 적는 대신
  `up-to=0`을 "상한 없음"으로 해석한다. (1차안 스캐폴딩에서 미해결로 남겼던 항목을 여기서 해결)
- **`AnswerDto.Calculation.localTax`** — 지방소득세는 결정세액과 별도로 부과되므로 분리했다.
  합쳐서 하나로 주면 화면에서 "이게 다인가?"라는 오해가 생긴다.
- **`GoldenSet.Item.asUser` / `expectRoute`** — 골든셋으로 소유자 격리와 라우팅까지 채점하려면
  "누구로 물었나"와 "어느 경로를 기대하나"가 항목에 있어야 한다.

### 결과

```
final/
├─ build.gradle · settings.gradle · gradlew (practice3 wrapper 재사용)
├─ docs/            PLAN.md · BUILD-LOG.md
└─ src/
   ├─ main/java/com/inwoo/taxdesk/   35개 파일 (11 패키지)
   ├─ main/resources/                application.properties
   │                                 tax-docs/ 9종(자리표시자) · static/index.html
   └─ test/java/com/inwoo/taxdesk/   6개 파일
```

검증: `./gradlew compileJava compileTestJava` → **BUILD SUCCESSFUL**

### 이 시점의 미해결 사항

1. **RAG 문서 9종의 본문이 비어 있다.** 자리표시자에 작성 규칙 3가지(조문 단위 헤딩,
   헤딩 아래 조문 번호 재기입, 수치는 `taxdesk.*` 와 일치)만 주석으로 박아 두었다.
   본문 작성 시점은 **Phase 2 이전**이어야 한다 — 인제스트할 대상이 없으면 Phase 2를 검증할 수 없다.
   특히 04번(세율표)은 `application.properties` 의 `taxdesk.brackets` 와 값이 완전히 같아야 한다.
2. **SSE를 Spring MVC로 할지 WebFlux를 추가할지** — 현재는 `spring-boot-starter-webmvc` 만 있고
   `Flux` 는 Spring AI가 끌고 오는 reactor-core로 쓸 계획이다. Phase 6에서 실제로 확인한 뒤 결정한다.
   `index.html` 은 지금 비스트리밍 `/chat` 을 쓰고, TODO 주석으로 전환 지점을 표시해 두었다.
3. **`index.html` 의 로그인 처리** — 현재는 Basic 인증이라 Swagger에서 로그인한 세션에 얹혀 간다.
   Phase 7에서 폼 로그인을 붙일지, 데모용 계정 선택 드롭다운을 둘지 결정한다.
   후자가 소유자 격리 시연에는 더 낫다 (계정 전환이 한 번의 클릭이 된다).

### 다음 단계
RAG 문서 9종 본문 작성 → Phase 1 (`AiConfig` 의 ChatClient 조립과 Advisor 체인, `/actuator/health` 200 확인)

---

## Step 2 — 질문셋 사전검증에서 잡힌 실패 (2026-08-20)

코드가 아직 스텁이라 시스템을 돌릴 수 없어서, **계산 엔진만 파이썬 프로토타입으로 구현해
질문셋과 시드가 실제로 변별력이 있는지** 먼저 확인했다. 두 번 실패했다.

### 실패 1 — `antman` 과 `isabu` 의 응답이 구분되지 않았다

초기 시드는 `isabu` 를 분리과세 구간(과세대상 1,714만)에 뒀다.
"ISA 를 빼야 분리과세, 안 빼면 종합과세"라는 함정을 노린 배치였는데, **정답 경로에서
`antman` 과 결과가 똑같았다** — 둘 다 "분리과세 종결 · `calculateTax` 미호출".

```
→ 서로 다른 시그니처 4개 / 유저 5명.  중복: [['antman', 'isabu']]
```

"분리과세 종결"이라는 결과는 하나뿐이라 두 사람이 나눠 가질 수 없다.

**개선**: `isabu` 를 종합과세 구간으로 올렸다(국내배당 801만 → 1,240만, 근로소득 4,480만 → 8,050만).
이제 ISA 제외 실패가 **판정이 아니라 세액을 틀리게** 한다 —
`16,250,660` → `18,044,800`, **179만원 차이**. 판정 뒤집기보다 검증이 오히려 선명해졌다.

### 실패 2 — 실험 설계 자체가 틀렸다

더 근본적인 문제였다. 나는 **"5명이 서로 다른 분기를 밟는가"** 를 기준으로 삼았는데,
이건 시드가 좋은지를 재는 기준이 아니다. 같은 분기를 밟아도 서로 다른 버그를 잡을 수 있고,
다른 분기를 밟아도 아무 버그도 못 잡을 수 있다.

**개선**: 기준을 **"각자가 서로 다른 버그를 잡는가"** 로 바꾸고,
계산 엔진에 고의로 버그 7종을 심어 누가 잡는지 행렬을 만들었다(뮤테이션 테스트).

| 심은 버그 | 검출 |
|---|---|
| M1 ISA·비과세를 제외하지 않음 | `isabu` |
| M2 Gross-up 대상 `min`→`max` | `isabu` `richman` `americano` |
| M3 외국배당도 Gross-up 포함 | `richman` `americano` |
| M4 경계를 "초과"가 아닌 "이상"으로 | **아무도 못 잡음** → CA-02 가 담당 |
| M5 비교과세 생략(① 만) | `jonber` |
| M6 Gross-up 율 옛 11% | `isabu` `richman` |
| M7 판정 없이 `calculateTax` 호출 | `antman` |

결과: **5명의 검출 패턴이 전부 다르다.** 누구를 빼도 잡히지 않는 버그가 생긴다.
`antman` 은 계산을 아예 안 하는 사람이라 세액 버그를 하나도 못 잡지만,
**도구 호출 순서 버그(M7)를 유일하게 잡는다** — 존재 의의가 세액이 아니라 시퀀스에 있다.

M6 튜닝이 특히 미묘했다. 처음엔 `richman` 만 Gross-up 율 오류를 잡았는데,
비교과세에서 ② 가 선택되면 Gross-up 값이 결과에 영향을 주지 않기 때문이었다.
`isabu` 의 근로소득을 올려 ① 이 선택되게 만들자 검출이 둘로 늘었다.
**"계산은 하지만 결과에 반영되지 않는 값"이 있다는 걸 이 실험 전에는 몰랐다.**

### M4 는 왜 안 고쳤나

시드 금액은 마이데이터에서 오므로 `20,000,000` 처럼 딱 떨어질 수 없다 —
그렇게 만들면 현실성을 잃는다. **경계값은 합성 질문(CA-01~03)의 몫**이고,
이건 결함이 아니라 역할 분담이다. 시드는 현실성을, 질문은 경계를 담당한다.

### 남은 한계

이 검증은 **계산 엔진에 한정**된다. RAG 출처 정확도 · 라우팅 · 안전은
모델을 불러야 하므로 Phase 3 이후에나 측정할 수 있다.
지금 확인한 것은 "골든셋의 계산 항목이 실제로 버그를 잡는다"까지다.

---

## Step 3 — 계산 엔진 자바 구현과 뮤테이션 검증 (2026-08-20)

Step 2 의 파이썬 프로토타입을 실제 코드로 옮겼다. **Phase 4 중 모델이 필요 없는 부분**이라
API 키 없이 완성되고 테스트까지 통과한다.

### 무엇을 만들었나

| 파일 | 내용 |
|---|---|
| `domain/Taxation` · `TaxClassification` | 분리/종합 판정 결과 |
| `domain/TaxCalculation` | 세액 계산 결과 (`steps` · ①② · 결정세액 · 지방세 · `payable` · `GrossUp`) |
| `tools/TaxCalcTools` | `classifyTaxation` · `calculateTax` · `simulateHeadroom` · `estimateDueDate` |
| `test/.../TaxCalcToolsTest` | 16개 케이스 |

`AnswerDto.Calculation`(중첩 record)은 `domain/TaxCalculation` 으로 옮겼다.
도구가 만드는 값을 웹 계층 DTO 안에 정의해 두면 도구가 웹 계층에 의존하게 된다.

### 설계 결정 세 가지

| 결정 | 이유 |
|---|---|
| `calculateTax` 가 기준금액 이하이면 **예외를 던진다** | 판정 → 계산 순서(소득세법 §62)를 프롬프트가 아니라 **타입 시스템 밖 코드**가 강제한다. "분리과세면 계산하지 마"를 모델에게 부탁하지 않는다 |
| 비율 계산에 **`BigDecimal`** | `1_528_440 * 0.10` 은 double 로 `152844.00000000001` 이다. 세금 계산에서 부동소수점 오차는 그대로 세액 차이가 된다 |
| 세액 **10원 미만 절사** | 국고금 단수계산. 파이썬 프로토타입과 자바 구현이 <b>독립적으로 같은 값</b>을 냈다는 것이 교차검증이 됐다 |

### 뮤테이션 검증 — 통과보다 **실패시켜 본 것**이 증거다

테스트 16개가 전부 통과한다는 사실만으로는 아무것도 증명되지 않는다.
`assertThat(1).isEqualTo(1)` 도 통과한다. 그래서 **실제로 버그를 심어 넣고** 잡히는지 확인했다.

| 심은 버그 | 실제로 실패한 테스트 | 행렬 예측과 일치? |
|---|---|---|
| M2 `min` → `max` | 9개 (isabu · richman · americano · jonber · steps · 부동소수점 · 플래닝) | ✅ (예측보다 넓게 잡힘) |
| M4 경계 `>` → `>=` | **1개** — 금융소득 20,000,000 케이스 | ✅ 정확히 그 케이스만 |
| M5 `max(①,②)` → `①` | **1개** — jonber | ✅ |
| M7 판정 없이 계산 허용 | **1개** — antman | ✅ |

**M4·M5·M7 이 각각 딱 하나의 테스트만 실패시켰다.** 이건 그 테스트가 없으면
그 버그를 아무도 못 잡는다는 뜻이고, 동시에 테스트가 과하지 않다는 뜻이기도 하다.

M2 만 예측(3명)보다 넓게 9개를 실패시켰는데, Gross-up 대상금액이 바뀌면 하위 계산이
연쇄적으로 틀리기 때문이다. 과검출은 문제가 아니다 — 미검출이 문제다.

### 이 시점의 상태

```
./gradlew test  →  BUILD SUCCESSFUL (17 tests)
   contextLoads                        1
   TaxCalcToolsTest                   16
```

**처음으로 `./gradlew test` 가 의미 있는 결과를 낸다.** 이전에는 `contextLoads` 하나뿐이라
"테스트 다 했어?"에 답할 것이 없었다. 이제는 답할 수 있다 —
**계산은 검증됐고, RAG·라우팅·안전은 아직이다.**

### 남은 것

`AccountIncomeTools`(계좌 조회)와 `DataSeeder`(시드 5명)는 아직 스텁이다.
둘도 모델이 필요 없으므로 다음 차례이고, 그것까지 끝나면 `OwnerIsolationTest` 도 돌릴 수 있다.
모델이 필요한 것은 Phase 1~3(ChatClient · 인제스트 · 라우터)부터다.

---

## Step 4 — Phase 1~8 구현과 실제 기동에서 잡힌 것들 (2026-08-20)

스캐폴딩의 `// TODO` 를 전부 채우고 앱을 실제로 띄워 API 를 검증했다.
**설계 문서로는 안 보이던 것이 기동하자마자 5개 나왔다.**

### 무엇을 구현했나

| Phase | 산출물 |
|---|---|
| 1 | `AiConfig` — ChatClient 3종(주·라우터·폴백) · Advisor 체인 · `ChatMemory` · `VectorStore` |
| 2 | `IngestService` · `DocumentMeta` · `tax-docs/` 9종 본문 |
| 3 | `QueryRouter`(규칙→모델 2단) · `TaxDeskService` |
| 4 | `AccountIncomeTools` · `ProductApplicationTools` · `DataSeeder`(시드 5명 13건) |
| 5 | `ConversationState` · `AmountParser` — 대화에서 받은 숫자를 문장과 별도로 기억 |
| 6 | `ChatController` · `CalcController` · `console.html` |
| 7 | `SecurityConfig` · `SafetyAdvisor` · `Masker` · `AuditAdvisor` · `ToolAuditAspect` · `AdminController` |
| 8 | `TokenMeterAdvisor` · `UsageRegistry` · `EvalService` |

### 기동해서 잡은 버그 5개

| # | 증상 | 원인 | 교훈 |
|---|---|---|---|
| 1 | 앱은 뜨는데 계좌 테이블이 없다 | `year` 가 **H2 예약어** | 도메인 언어와 DB 예약어는 충돌한다. 필드명은 지키고 컬럼명만 피했다 |
| 2 | 없는 경로가 **500** | `@ExceptionHandler(Exception.class)` 캐치올이 스프링의 404 예외까지 삼킴 | 운영자가 **"서버 장애"와 "경로 오타"를 구분할 수 없게 된다** |
| 3 | 기동 실패 — 순환참조 | `defaultTools(Object[] toolBeans)` 가 **모든 빈**을 주입 | 편해 보이는 시그니처가 의존성을 통째로 끌어온다 |
| 4 | 응답이 90초 넘게 안 옴 | 서비스가 이미 부른 도구를 **모델이 또 부르며 루프** | 아래 참조 |
| 5 | 도구가 계산한 세액에 "정보가 부족합니다" | `QuestionAnswerAdvisor` 가 자체 템플릿으로 사용자 메시지를 **대체** | 아래 참조 |

### 4번과 5번이 같은 교훈이다 — 모델에게 오케스트레이션을 맡기지 말 것

이 프로젝트의 논지는 처음부터 **"어떤 부품을 부를지 모델에게 매번 맡기지 않는다"** 였다.
그런데 구현할 때는 무심코 반대로 했다.

- **도구를 `defaultTools` 로 붙였다** → 서비스가 결정적으로 부른 뒤에도 모델이 또 불렀다.
  판정 → 계산 순서를 코드로 강제해 놓고, 정작 모델에게 같은 도구를 쥐여준 셈이다.
- **`QuestionAnswerAdvisor` 를 체인에 넣었다** → 그 어드바이저는 검색 결과를
  *"주어진 컨텍스트에만 근거해 답하고, 없으면 모른다고 하라"* 는 자기 템플릿으로 감싸
  사용자 메시지를 **통째로 대체한다.** 우리가 앞에 붙인 `[확정 사실]` 블록이 사라지고,
  이미 계산이 끝난 66,274,240원에 대해 **"정확한 세액을 계산할 수 없습니다"** 라는 답이 나왔다.

둘 다 걷어내고 **도구 호출과 검색을 `TaxDeskService` 가 직접** 하게 바꿨다.
모델에게는 `system` 슬롯으로 확정 사실과 근거를 주고 문장만 다듬게 한다.

> 편의 기능(`defaultTools`, `QuestionAnswerAdvisor`)은 "모델이 알아서 하게" 만들어져 있다.
> 그게 편한 경우도 많지만, **호출 순서 자체가 규정인 도메인에서는 쓰면 안 된다.**
> 프레임워크의 기본값이 우리 설계와 반대 방향일 수 있다는 것을 이번에 배웠다.

### 그 밖에 실제로 돌려 보고 고친 것

| 증상 | 조치 |
|---|---|
| 모든 답이 "질문이 너무 깁니다" | `SafetyAdvisor` 의 길이 상한이 **조립된 프롬프트**에 걸렸다. 검사 대상을 `rawQuestion`(고객이 실제로 친 문장)으로 분리. 근거를 많이 찾아올수록 차단되는 동작이었다 |
| richman 세액이 원천징수액과 같음 | `otherIncome` 을 0 으로 하드코딩. 사업소득 1.52억이 계산에 안 들어갔다 → `AmountParser` + `ConversationState` 신설 |
| 대화 기록에 조립된 프롬프트가 저장됨 | 확정 사실을 `system` 슬롯으로 옮기고 `user` 는 원문 질문으로. 기록에 질문이 남는다 |
| 토큰이 사용자별로 안 잡힘(`-`) | 어드바이저 컨텍스트에 `userId`·`route` 파라미터를 싣지 않았다 |
| ACTION 이 신청을 안 만듦 | 도구를 모델에서 뗀 뒤 서비스가 직접 `requestEligibilityCheck` 를 부르도록 |

### 검증 결과 (실제 실행)

```
POST /admin/ingest       파일 9 · 청크 39 · 조문 메타 누락 0건
POST /chat  REGULATION   [출처: 소득세법 §17③ (2026 귀속)] 자동 부착 · Gross-up 10% 정답
POST /chat  거절 4갈래     tokens 0/0  (양도세 · 투자자문 · 타사계좌 · 신고대리)
POST /chat  HYBRID       ①66,274,248 ②50,360,644 → 66,274,240  (JUnit 값과 일치)
POST /chat  SIMULATION   여유 4,740,290원
멀티턴                    1턴 "근로소득 4150만" → 2턴 "그럼 세금?" 에서 재사용
승인 게이트                USER→403 · 사유없는 거절→400 · 재결정→409
GET /admin/usage         사용자별 · 라우트별 토큰 집계
./gradlew test           BUILD SUCCESSFUL (17)
```

### 설계가 바뀐 지점 (문서와 코드를 맞춰 둘 것)

1. **주 ChatClient 에 도구를 붙이지 않는다.** PLAN 의 "Tool" 은 모델이 부르는 것이 아니라
   서비스가 부르고 결과만 전달하는 구조다.
2. **`QuestionAnswerAdvisor` 를 쓰지 않는다.** Advisor 체인은
   `Audit(0) → Safety(100) → Memory(200) → TokenMeter(900)` 네 개다.
3. **확정 사실은 `system` 슬롯으로 간다.** `user` 는 언제나 고객의 원문 질문이다.
4. **세액을 계산한 답변에는 별도 면책 문구**가 붙는다 — 소득공제·세액공제·타소득 세 가지를
   명시한다. "참고만 하세요"보다 **무엇을 몰라서 달라지는지** 밝히는 편이 정직하다.

### 다음에 이어서 할 것

- `golden.json` 채우기 → `EvalService` 로 골든셋 실행 (Phase 8 마무리)
- `OwnerIsolationTest` · `QueryRouterTest` · `AdvisorOrderTest` 구현 (현재 빈 껍데기)
- SSE 스트리밍(`GET /chat/stream`) — 현재 비스트리밍만 있다
- 실험 E1~E11 실행 및 결과 기록

