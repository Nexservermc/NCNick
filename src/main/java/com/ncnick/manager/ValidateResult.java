package com.ncnick.manager;

import java.util.HashMap;
import java.util.Map;

/**
 * 昵称校验结果。
 *
 * <p>校验失败时携带语言键与占位参数，便于本地化消息生成。
 */
public final class ValidateResult {

    /** 校验是否通过 */
    private final boolean ok;
    /** 失败时的语言键，如 "validate.invalid-length" */
    private final String messageKey;
    /** 消息占位参数 */
    private final Map<String, String> params;

    private ValidateResult(boolean ok, String messageKey, Map<String, String> params) {
        this.ok = ok;
        this.messageKey = messageKey;
        this.params = params;
    }

    /** 通过 */
    public static ValidateResult ok() {
        return new ValidateResult(true, null, null);
    }

    /** 失败 */
    public static ValidateResult fail(String messageKey) {
        return new ValidateResult(false, messageKey, new HashMap<String, String>());
    }

    /** 失败，带占位参数 */
    public static ValidateResult fail(String messageKey, Map<String, String> params) {
        return new ValidateResult(false, messageKey,
                params == null ? new HashMap<String, String>() : params);
    }

    public boolean isOk() {
        return ok;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Map<String, String> getParams() {
        return params == null ? new HashMap<String, String>() : params;
    }
}
