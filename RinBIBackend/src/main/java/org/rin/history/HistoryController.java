package org.rin.history;

import org.rin.agent.ConversationMemory;
import org.rin.auth.AuthService;
import org.rin.auth.AuthUser;
import org.rin.exception.BadRequestException;
import org.rin.history.dto.ConversationDetail;
import org.rin.history.dto.ConversationSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 历史对话接口：列表、详情（用于恢复）、删除。
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ConversationMemory conversationMemory;

    @GetMapping("/conversations")
    public List<ConversationSummary> list(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return historyService.list(user.id(), limit);
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationDetail detail(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @PathVariable String conversationId) {
        validateConversationId(conversationId);
        return historyService.detail(user.id(), conversationId);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> delete(
            @RequestAttribute(AuthService.AUTH_USER_ATTRIBUTE) AuthUser user,
            @PathVariable String conversationId) {
        validateConversationId(conversationId);
        historyService.delete(user.id(), conversationId);
        conversationMemory.clear(ConversationMemory.memoryKey(user.id(), conversationId));
        return ResponseEntity.noContent().build();
    }

    private void validateConversationId(String conversationId) {
        if (conversationId == null || conversationId.length() > 64) {
            throw new BadRequestException("conversationId 不合法");
        }
    }
}
