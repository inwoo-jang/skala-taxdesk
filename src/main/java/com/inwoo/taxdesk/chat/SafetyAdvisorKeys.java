package com.inwoo.taxdesk.chat;

/** 어드바이저 컨텍스트 키. 문자열 리터럴이 서비스와 어드바이저 양쪽에 흩어지지 않게 모아 둔다. */
public final class SafetyAdvisorKeys {

    /** 고객이 실제로 입력한 문장. 우리가 조립한 프롬프트와 구분하기 위해 따로 싣는다. */
    public static final String RAW_QUESTION = "rawQuestion";

    private SafetyAdvisorKeys() {
    }
}
