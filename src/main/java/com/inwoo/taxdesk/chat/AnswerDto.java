package com.inwoo.taxdesk.chat;

import com.inwoo.taxdesk.domain.TaxCalculation;
import java.util.List;

/**
 * Phase 6 — 구조화 응답.
 *
 * <p>답변 문장만 돌려주면 "근거로 답했는지"를 밖에서 검증할 수 없다.
 * 인용·도구 호출·계산 전개를 같이 실어야 검증표의 "도구 호출 로그 없는 금액 언급 = 실패"를
 * 기계로 판정할 수 있다. {@code static/index.html} 이 이 필드들을 그대로 화면에 펼쳐 보인다.
 */
public record AnswerDto(Route route,
                        String answer,
                        List<Citation> citations,
                        List<ToolCall> toolCalls,
                        TaxCalculation calculation,
                        Tokens tokens,
                        long latencyMs,
                        String disclaimer) {

    /** 근거 한 조각. 출처가 파일명이 아니라 조문 단위인 것이 practice3 과의 차이다. */
    public record Citation(String doc, String article, double score) {
    }

    public record ToolCall(String name, String args, String result) {
    }

    public record Tokens(int prompt, int completion) {
    }
}
