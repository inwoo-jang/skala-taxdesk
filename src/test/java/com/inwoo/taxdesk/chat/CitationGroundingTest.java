package com.inwoo.taxdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 답변에 붙은 [출처: …] 가 실제 회수 결과 안에 있는지 검사한다.
 *
 * <p>프롬프트로 "근거에 없으면 출처를 지어내지 말 것"을 요구했지만 지켜지지 않는 경우가 있었다.
 * 실제로 비영업대금 세율을 물었을 때 회수되지 않은 §129①1호 를 출처로 붙이고
 * 값까지 틀리게(25% → 14%) 답한 응답이 나왔다. 프롬프트가 아니라 코드가 막아야 한다.
 */
class CitationGroundingTest {

    private static final String UNGROUNDED = "확인되지 않습니다";

    @Test
    @DisplayName("회수되지 않은 조문을 인용하면 답변을 내보내지 않는다")
    void blocksFabricatedCitation() {
        String answer = "비영업대금의 이익은 이자소득과 동일하게 14%가 적용됩니다. "
                + "[출처: 소득세법 §129①1호 (2026 귀속)]";
        List<String> retrieved = List.of("소득세법 §62", "소득세법 §129①2호", "소득세법 §17①");

        assertThat(TaxDeskService.groundCitations(answer, retrieved)).startsWith(UNGROUNDED);
    }

    @Test
    @DisplayName("회수된 조문을 인용하면 그대로 통과시킨다")
    void keepsGroundedCitation() {
        String answer = "배당가산율은 10%입니다. [출처: 소득세법 §17③ (2026 귀속)]";
        List<String> retrieved = List.of("소득세법 §17③", "소득세법 §56");

        assertThat(TaxDeskService.groundCitations(answer, retrieved)).isEqualTo(answer);
    }

    @Test
    @DisplayName("조문 표기가 흔들려도 같은 조문이면 통과한다")
    void toleratesArticleFormatting() {
        String answer = "외국법인 배당은 대상이 아닙니다. [출처: 소득세법 §17③ (2026 귀속)]";
        List<String> retrieved = List.of("소득세법 §17③ 각 호");

        assertThat(TaxDeskService.groundCitations(answer, retrieved)).isEqualTo(answer);
    }

    @Test
    @DisplayName("출처를 붙이지 않은 답변은 검사 대상이 아니다")
    void ignoresAnswerWithoutCitation() {
        String answer = "고객님의 금융소득은 15,259,710원입니다.";

        assertThat(TaxDeskService.groundCitations(answer, List.of("소득세법 §14③"))).isEqualTo(answer);
    }

    @Test
    @DisplayName("회수 결과가 없으면 검사하지 않는다 — 도구 경로는 출처를 쓰지 않는다")
    void skipsWhenNothingRetrieved() {
        String answer = "결정세액은 3,559,890원입니다. [출처: 소득세법 §62 (2026 귀속)]";

        assertThat(TaxDeskService.groundCitations(answer, List.of())).isEqualTo(answer);
    }
}
