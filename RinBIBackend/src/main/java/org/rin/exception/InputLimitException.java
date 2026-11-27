package org.rin.exception;

/**
 * 智能报表会话的模型输入已达上限。
 *
 * <p>与普通参数错误区分开，前端据此提示用户新建会话并重新上传模板。</p>
 */
public class InputLimitException extends RuntimeException {

    public InputLimitException(String message) {
        super(message);
    }
}
