package com.inwoo.taxdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SKALA증권 고객센터 세무상담 데스크 — 금융소득 종합과세 예상 안내.
 *
 * <p>설계 원칙 한 줄: <b>LLM 에게 산수를 시키지 않는다. 대신 근거와 계산을 둘 다 증명한다.</b>
 * 근거는 RAG(조문 인용), 계산은 Tool(전개 과정 동봉)이 담당하고 모델은 둘을 엮어 문장으로만 만든다.
 *
 * <p>운영 주체가 증권사인 것이 설계 제약이다. 세무사법 §2 상 세무대리를 할 수 없으므로
 * 이 서비스가 낼 수 있는 것은 "예상 안내"지 "신고 대리"가 아니고,
 * 아는 것은 자사 계좌뿐이라 타사 금액은 고객에게 물어봐야 한다.
 * 못 하는 것을 못 한다고 말하는 것도 응대다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TaxDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaxDeskApplication.class, args);
    }
}
