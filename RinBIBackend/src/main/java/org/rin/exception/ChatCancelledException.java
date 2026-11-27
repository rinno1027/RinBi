package org.rin.exception;

/**
 * 数据问答 / 智能报表任务被主动取消。
 *
 * <p>取消来源包括：用户点击“停止”、关闭页面导致 SSE 断开、SSE 推送失败或连接超时。
 * 取消不是业务错误：收到该异常时应立即停止后续的模型调用与 SQL 执行，
 * 并且不写入对话记忆与历史记录。</p>
 */
public class ChatCancelledException extends RuntimeException {

    public ChatCancelledException() {
        super("任务已停止");
    }

    public ChatCancelledException(String message) {
        super(message);
    }
}
