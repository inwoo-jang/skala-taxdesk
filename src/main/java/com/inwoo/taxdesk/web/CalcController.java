package com.inwoo.taxdesk.web;

import com.inwoo.taxdesk.domain.TaxCalculation;
import com.inwoo.taxdesk.domain.TaxClassification;
import com.inwoo.taxdesk.domain.TaxSimulation;
import com.inwoo.taxdesk.tools.TaxCalcTools;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 4 — 계산 도구를 HTTP 로 직접 호출한다. <b>모델을 거치지 않는 경로다.</b>
 *
 * <p>존재 이유는 둘이다.
 * <ol>
 *   <li><b>도구 단독 검증.</b> {@code /chat} 으로 물으면 라우팅 · RAG · 모델이 모두 끼어들어
 *       세액이 틀렸을 때 어디서 틀렸는지 알 수 없다. 여기로 부르면 계산만 남는다</li>
 *   <li><b>대조군.</b> 같은 입력을 이 경로와 {@code /chat} 양쪽으로 넣어 답이 같은지 본다.
 *       다르면 "도구는 맞았는데 모델이 다른 숫자를 말한" 경우고, 그게 정확히 잡고 싶은 실패다
 *       (실험 E4 의 인용 충실도)</li>
 * </ol>
 *
 * <p>실제 고객에게 열 API 가 아니다. 사용자는 {@code /chat} 만 쓴다.
 */
@RestController
@RequestMapping("/calc")
public class CalcController {

    private final TaxCalcTools tools;

    public CalcController(TaxCalcTools tools) {
        this.tools = tools;
    }

    /** 분리과세 / 종합과세 판정. 세액 계산보다 먼저 호출해야 한다 (소득세법 §62). */
    @PostMapping("/classify")
    public TaxClassification classify(@RequestBody ClassifyRequest r) {
        return tools.classifyTaxation(r.interest(), r.domesticDividend(),
                r.foreignDividend(), r.externalAmount());
    }

    /** 비교과세 세액. 종합과세로 판정된 경우에만 호출된다 — 아니면 400 이 난다. */
    @PostMapping("/tax")
    public TaxCalculation tax(@RequestBody TaxRequest r) {
        return tools.calculateTax(r.financialIncome(), r.grossUpEligibleDividend(),
                r.otherIncome(), r.deduction(), r.prepaidTax());
    }

    /** 연중 플래닝 — 앞으로 얼마까지 더 받아도 되는가. */
    @PostMapping("/simulate")
    public TaxSimulation simulate(@RequestBody SimulateRequest r) {
        return tools.simulateHeadroom(r.financialIncome(), r.grossUpEligibleDividend(),
                r.otherIncome(), r.deduction());
    }

    /** 신고·납부 기한. */
    @GetMapping("/due-date")
    public TaxCalcTools.DueDate dueDate(@RequestParam int year) {
        return tools.estimateDueDate(year);
    }

    public record ClassifyRequest(long interest, long domesticDividend,
                                  long foreignDividend, long externalAmount) {
    }

    public record TaxRequest(long financialIncome, long grossUpEligibleDividend,
                             long otherIncome, long deduction, long prepaidTax) {
    }

    public record SimulateRequest(long financialIncome, long grossUpEligibleDividend,
                                  long otherIncome, long deduction) {
    }
}
