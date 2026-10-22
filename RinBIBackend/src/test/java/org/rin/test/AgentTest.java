package org.rin.test;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.rin.AppStart;
import org.rin.agent.ReasoningStreamClient;
import org.rin.agent.dto.ReasoningStreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.util.StringUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 真实模型联调用例，默认被 integration 标签排除，需要配置好 agent.llm.* 后手动执行。
 */
@Tag("integration")
@SpringBootTest(classes = {AppStart.class}, properties = "spring.profiles.active=dev")
@WebAppConfiguration
@ExtendWith(SpringExtension.class)
public class AgentTest {

    private static final long WAIT_SECONDS = 180L;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private ReasoningStreamClient reasoningStreamClient;

    @Autowired
    private OllamaStreamingChatModel ollamaStreamModel;

    private final Logger log = LoggerFactory.getLogger(AgentTest.class);

    @Test
    public void testLlm() throws Exception {
        StringBuilder answer = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        CountDownLatch completed = new CountDownLatch(1);

        streamingChatModel.chat("langchain4j框架有什么特点", new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                String chunk = partialThinking == null ? null : partialThinking.text();
                if (!StringUtils.hasText(chunk)) {
                    return;
                }
                reasoning.append(chunk);
            }

            @Override
            public void onPartialResponse(String partialResponse) {
                answer.append(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                log.info("思考内容：{}",reasoning);
                log.info("完整回答：{}", answer);
                completed.countDown();
            }

            @Override
            public void onError(Throwable error) {
                log.error("调用模型失败", error);
                completed.countDown();
            }
        });

        if (!completed.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待模型响应超时");
        }
    }

    @Test
    public void testLlmStream() throws Exception {
        // 直接验证新客户端：思考分片和答案分片都会实时回调，最终文本在流结束后返回。
        ReasoningStreamResult result = reasoningStreamClient.stream(
                "你是严谨的助手，请用中文回答。",
                "java对接大模型的优势",
                chunk -> log.info("思考分片: {}", chunk),
                chunk -> log.info("答案分片: {}", chunk));
        log.info("思考全文: {}", result.getReasoning());
        log.info("最终答案: {}", result.getContent());
    }

    @Test
    public void testOllamaStream() throws Exception {
        StringBuilder answer = new StringBuilder();
        CountDownLatch completed = new CountDownLatch(1);

        ollamaStreamModel.chat("java对接大模型的优势", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                answer.append(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                log.info("ollama 输出完毕：{}", answer);
                completed.countDown();
            }

            @Override
            public void onError(Throwable error) {
                log.error("ollama 输出异常", error);
                completed.countDown();
            }
        });

        if (!completed.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待 ollama 响应超时");
        }
    }
}
