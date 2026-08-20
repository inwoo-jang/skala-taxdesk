package com.inwoo.taxdesk.common.web;

/** 모델 호출이 실패했다. 우리 잘못이 아니므로 503 으로 내보내고 재시도를 안내한다. */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
