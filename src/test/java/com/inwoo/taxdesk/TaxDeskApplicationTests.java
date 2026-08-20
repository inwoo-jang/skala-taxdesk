package com.inwoo.taxdesk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Phase 1 검증 — 컨텍스트가 뜨는가.
 *
 * <p>Advisor 체인 조립이 잘못되면(빈 중복 · 순환 참조 · order 충돌) 여기서 먼저 터진다.
 */
@SpringBootTest
class TaxDeskApplicationTests {

    @Test
    void contextLoads() {
    }
}
