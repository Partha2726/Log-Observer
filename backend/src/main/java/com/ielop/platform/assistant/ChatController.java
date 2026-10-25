package com.ielop.platform.assistant;

import com.ielop.platform.common.security.Actor;
import com.ielop.platform.trace.api.ApiParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Developer assistant endpoints (section 8.8); a conversation is visible to its owner only. */
@RestController
@Tag(name = "AI")
@RequestMapping("/api/assistant/conversations")
public class ChatController {

    private final ChatService chat;
    private final ApiParams params;

    public ChatController(ChatService chat, ApiParams params) {
        this.chat = chat;
        this.params = params;
    }

    public record NewConversation(String flowRef) {
    }

    public record Created(UUID conversationId) {
    }

    public record NewMessage(String message) {
    }

    @Operation(summary = "Start a conversation, about one trace (flowRef) or global")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@RequestBody(required = false) NewConversation body, Authentication auth) {
        String ref = body == null ? null : body.flowRef();
        ApiParams.Check check = params.check();
        String flowRef = check.flowRef("flowRef", ref, false);
        check.done();
        return new Created(chat.create(flowRef, Actor.username(auth)));
    }

    @Operation(summary = "Ask a question; the answer cites log lines as [ev:N]")
    @PostMapping("/{id}/messages")
    public ChatService.Answer message(@PathVariable UUID id, @RequestBody(required = false) NewMessage body, Authentication auth) {
        return chat.message(id, Actor.username(auth), body == null ? null : body.message());
    }

    @Operation(summary = "The conversation's messages (owner only)")
    @GetMapping("/{id}")
    public ChatService.Conversation get(@PathVariable UUID id, Authentication auth) {
        return chat.get(id, Actor.username(auth));
    }
}
