package com.inwoo.taxdesk.chat;

import com.inwoo.taxdesk.domain.TaxCalculation;
import com.inwoo.taxdesk.tools.AccountIncomeTools;
import java.util.List;

/**
 * Phase 6 — 구조화 응답.
 *
 * <p>답변 문장만 돌려주면 "근거로 답했는지"를 밖에서 검증할 수 없다.
 * 인용·도구 호출·계산 전개를 같이 실어야 검증표의 "도구 호출 로그 없는 금액 언급 = 실패"를
 * 기계로 판정할 수 있다. {@code static/index.html} 이 이 필드들을 그대로 화면에 펼쳐 보인다.
 *
 * <p><b>검색된 근거와 실제 인용된 근거는 다르다.</b> {@code citations} 는 검색이 돌려준 목록이고,
 * 그중 답변이 {@code [출처: …]} 로 실제로 가리킨 것만 {@link Citation#used()} 가 참이다.
 * 둘을 한 목록으로 보여주면 "모델이 이 조문을 보고 답했다"고 오해하게 된다.
 *
 * @param notices      조건부·보류·확인 필요·중복 경고 등 <b>코드가 만든</b> 고지. 모델 문장이 아니다
 * @param overview     이번 턴에 계좌를 조회했다면 그 현황. 조회하지 않았으면 {@code null}
 * @param pendingAction 확인을 기다리는 신청 미리보기. 없으면 {@code null}
 * @param ruleVersion  적용한 규칙의 버전 라벨 (귀속연도 · 법령 시행본 · 대조일)
 */
public record AnswerDto(Route route,
                        String answer,
                        List<Citation> citations,
                        List<ToolCall> toolCalls,
                        TaxCalculation calculation,
                        AccountIncomeTools.IncomeOverview overview,
                        List<String> notices,
                        PendingAction pendingAction,
                        Tokens tokens,
                        long latencyMs,
                        String disclaimer,
                        String ruleVersion) {

    /**
     * 근거 한 조각. 출처가 파일명이 아니라 조문 단위인 것이 practice3 과의 차이다.
     *
     * @param kind       LAW(법령) / INTERNAL(자체 안내)
     * @param lawVersion 법령 시행본. 자체 안내면 빈 문자열
     * @param excerpt    청크 앞부분 발췌. 고객이 "이 조문이 정말 그렇게 말하나"를 바로 볼 수 있게
     * @param used       답변이 실제로 인용했는가
     */
    public record Citation(String doc, String article, double score, String kind, int effectiveYear,
                           String lawVersion, String verifiedOn, String excerpt, boolean used) {
    }

    public record ToolCall(String name, String args, String result) {
    }

    public record Tokens(int prompt, int completion) {
    }

    /** 실행 전 미리보기. 저장소에는 아직 아무것도 없다. */
    public record PendingAction(String product, String productLabel, boolean mock, String howToConfirm) {
    }
}
