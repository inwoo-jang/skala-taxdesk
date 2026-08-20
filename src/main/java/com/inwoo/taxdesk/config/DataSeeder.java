package com.inwoo.taxdesk.config;

import com.inwoo.taxdesk.domain.AccountIncome;
import com.inwoo.taxdesk.domain.IncomeSource;
import com.inwoo.taxdesk.domain.IncomeType;
import com.inwoo.taxdesk.domain.TaxTreatment;
import com.inwoo.taxdesk.repository.AccountIncomeRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 4 — 금융소득 시드. 자사 계좌 + 마이데이터 연동분.
 *
 * <p>시드 값은 골든셋의 기대 세액과 짝을 이루므로 아무 값이나 넣으면 안 된다.
 * <b>계정 하나가 분기 하나를 담당한다</b> — 한 사람이 두 케이스를 겸하면 실패 원인을 분리할 수 없다.
 *
 * <table>
 *   <tr><th>계정</th><th>과세대상</th><th>담당 분기</th></tr>
 *   <tr><td>antman</td><td>15,259,710</td><td>분리과세 종결 — calculateTax 미호출 (M7)</td></tr>
 *   <tr><td>isabu</td><td>21,528,440</td><td>ISA 제외 실패 시 179만원 오차 (M1)</td></tr>
 *   <tr><td>jonber</td><td>25,427,830</td><td>비교과세 ①&lt;② → ② 선택 · 추가납부 0원 (M5)</td></tr>
 *   <tr><td>richman</td><td>102,504,600</td><td>①&gt;② · Gross-up 가능배당 쪽에서 잘림 (M2·M6)</td></tr>
 *   <tr><td>americano</td><td>30,356,350</td><td>해외배당 → Gross-up 대상 0 (M3)</td></tr>
 * </table>
 *
 * <p>금액을 딱 떨어지게 두지 않았다. 실제 이자는 8,347,210원 같은 숫자로 들어오고,
 * 그래야 원 단위 절사 처리까지 테스트에 걸린다. <b>경계값(정확히 2,000만원)은 시드가 아니라
 * 합성 질문의 몫</b>이다 — 시드는 현실성을, 질문은 경계를 담당한다.
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final int YEAR = 2026;

    @Bean
    ApplicationRunner seedAccountIncome(AccountIncomeRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            List<AccountIncome> rows = new ArrayList<>();

            // antman — 분리과세 종결 (합계 15,259,710)
            rows.add(linked("antman", "국민은행", "정기예금 이자", 8_347_210L, 1_168_600L));
            rows.add(own("antman", "국내주식 배당", IncomeType.DOMESTIC_DIVIDEND, 6_912_500L, 967_750L));

            // isabu — 종합과세 (과세대상 21,528,440) + ISA 9,120,000 은 판정에서 제외
            rows.add(linked("isabu", "하나은행", "적금 이자", 9_128_440L, 1_277_980L));
            rows.add(own("isabu", "국내주식 배당", IncomeType.DOMESTIC_DIVIDEND, 12_400_000L, 1_736_000L));
            rows.add(isa("isabu", "ISA 비과세 한도분", 4_000_000L, 0L, TaxTreatment.TAX_FREE));
            rows.add(isa("isabu", "ISA 한도 초과분(9% 분리과세)", 5_120_000L, 460_800L, TaxTreatment.SEPARATE));

            // jonber — 비교과세 ①<② (합계 25,427,830 · 다른 종합소득 0)
            rows.add(linked("jonber", "우리은행", "수시입출금 이자", 1_247_830L, 174_690L));
            rows.add(own("jonber", "고배당주 배당", IncomeType.DOMESTIC_DIVIDEND, 24_180_000L, 3_385_200L));

            // richman — ①>② · Gross-up 전액 대상 (합계 102,504,600)
            rows.add(linked("richman", "신한은행", "정기예금 이자", 21_384_600L, 2_993_840L));
            rows.add(own("richman", "국내주식 배당", IncomeType.DOMESTIC_DIVIDEND, 61_250_000L, 8_575_000L));
            rows.add(own("richman", "해외ETF 분배금", IncomeType.FOREIGN_DIVIDEND, 19_870_000L, 2_781_800L));

            // americano — Gross-up 대상 0 (합계 30,356,350)
            rows.add(linked("americano", "카카오뱅크", "세이프박스 이자", 2_916_350L, 408_280L));
            rows.add(own("americano", "미국주식 배당", IncomeType.FOREIGN_DIVIDEND, 27_440_000L, 3_841_600L));

            repository.saveAll(rows);
            log.info("시드 완료 — 계좌 내역 {}건 / {} 귀속", rows.size(), YEAR);
        };
    }

    /** 자사 계좌. 우리가 직접 지급하고 원천징수한 것이라 가장 확실하다. */
    private static AccountIncome own(String owner, String product, IncomeType type,
                                     long amount, long withheld) {
        return new AccountIncome(id(), owner, "1234" + owner.hashCode() % 10000 + "01", YEAR,
                "SKALA증권", IncomeSource.OWN_ACCOUNT, product, type, amount, withheld,
                TaxTreatment.TAXABLE);
    }

    /** 마이데이터 연동 기관. 확인된 금액이지만 "연동한 기관에 한한다"는 단서가 붙는다. */
    private static AccountIncome linked(String owner, String bank, String product,
                                        long amount, long withheld) {
        return new AccountIncome(id(), owner, "110" + Math.abs(bank.hashCode() % 1000000), YEAR,
                bank, IncomeSource.LINKED, product, IncomeType.INTEREST, amount, withheld,
                TaxTreatment.TAXABLE);
    }

    /** ISA. 비과세 한도분과 초과분(9% 분리과세)은 과세방식이 다르므로 별개 행이다. */
    private static AccountIncome isa(String owner, String product, long amount, long withheld,
                                     TaxTreatment treatment) {
        return new AccountIncome(id(), owner, "9988" + owner.hashCode() % 1000 + "77", YEAR,
                "SKALA증권", IncomeSource.OWN_ACCOUNT, product, IncomeType.INTEREST,
                amount, withheld, treatment);
    }

    private static String id() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
