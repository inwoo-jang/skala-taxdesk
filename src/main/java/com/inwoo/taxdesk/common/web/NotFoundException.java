package com.inwoo.taxdesk.common.web;

/** 못 찾았다. "없다"와 "볼 권한이 없다"를 구분해 알려 주지 않는다 — 구분 자체가 정보다. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
