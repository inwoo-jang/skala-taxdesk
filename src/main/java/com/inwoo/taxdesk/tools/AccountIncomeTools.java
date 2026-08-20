package com.inwoo.taxdesk.tools;

import com.inwoo.taxdesk.advisor.Masker;
import com.inwoo.taxdesk.domain.AccountIncome;
import com.inwoo.taxdesk.domain.IncomeType;
import com.inwoo.taxdesk.domain.TaxTreatment;
import com.inwoo.taxdesk.repository.AccountIncomeRepository;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Phase 4 — 내 금융소득 조회. 자사 계좌 + 마이데이터 연동분.
 *
 * <p>{@code lookupMyIncome(year)} — "내" 가 어디서 오는지가 전부다.
 * 사용자 입력이나 모델이 지어낸 ID 가 아니라 {@link ToolUser} 가 세션에서 꺼낸 값만 쓰고,
 * 조회는 {@code findByOwnerIdAndYear} 로 소유자 조건을 쿼리에 박는다.
 *
 * <p><b>파라미터에 기관명이나 고객 ID 를 두지 않는다.</b> 프롬프트로 "타인 것은 조회하지 마"라고
 * 부탁하면 우회할 여지가 남지만, 넣을 자리가 없으면 우회할 것도 없다.
 *
 * <p>비과세·분리과세 상품은 <b>합계에서 빼되 목록에는 남긴다.</b> 빼고 안 보여 주면
 * 고객이 "내 ISA 는 왜 빠졌냐"고 묻게 되고, 그 답을 모델이 지어내게 된다.
 */
@Component
public class AccountIncomeTools {

    private final AccountIncomeRepository repository;

    public AccountIncomeTools(AccountIncomeRepository repository) {
        this.repository = repository;
    }

    @Tool(description = """
            로그인한 고객 본인의 해당 연도 금융소득(이자·배당)을 조회한다.
            다른 사람이나 다른 금융기관의 계좌는 조회할 수 없다.
            비과세·분리과세 상품은 종합과세 판정 대상에서 제외된 금액으로 따로 표시된다.""")
    public IncomeSummary lookupMyIncome(
            @ToolParam(description = "귀속연도. 예: 2026") int year,
            ToolContext context) {

        String ownerId = ToolUser.of(context);
        List<AccountIncome> rows = repository.findByOwnerIdAndYear(ownerId, year);

        long interest = sum(rows, IncomeType.INTEREST, true);
        long domestic = sum(rows, IncomeType.DOMESTIC_DIVIDEND, true);
        long foreign = sum(rows, IncomeType.FOREIGN_DIVIDEND, true);
        long withheld = rows.stream().mapToLong(AccountIncome::getWithheldTax).sum();
        long excluded = rows.stream()
                .filter(r -> !r.countsTowardThreshold())
                .mapToLong(AccountIncome::getAmount).sum();

        List<Item> items = rows.stream()
                .map(r -> new Item(
                        r.getInstitution(),
                        Masker.maskAccountNo(r.getAccountNo()),
                        r.getProductName(),
                        r.getType(),
                        r.getTreatment(),
                        r.getSource().name(),
                        r.getAmount(),
                        r.getWithheldTax()))
                .toList();

        return new IncomeSummary(year, interest, domestic, foreign,
                interest + domestic + foreign, excluded, withheld, items);
    }

    private static long sum(List<AccountIncome> rows, IncomeType type, boolean taxableOnly) {
        return rows.stream()
                .filter(r -> r.getType() == type)
                .filter(r -> !taxableOnly || r.countsTowardThreshold())
                .mapToLong(AccountIncome::getAmount)
                .sum();
    }

    /**
     * @param taxableTotal 종합과세 판정에 들어가는 합계. 비과세·분리과세는 빠져 있다
     * @param excludedTotal 판정에서 빠진 금액 (비과세 + 분리과세). 빠졌다는 사실을 숨기지 않는다
     */
    public record IncomeSummary(int year,
                                long interest,
                                long domesticDividend,
                                long foreignDividend,
                                long taxableTotal,
                                long excludedTotal,
                                long withheldTax,
                                List<Item> items) {
    }

    public record Item(String institution,
                       String accountNo,
                       String productName,
                       IncomeType type,
                       TaxTreatment treatment,
                       String source,
                       long amount,
                       long withheldTax) {
    }
}
