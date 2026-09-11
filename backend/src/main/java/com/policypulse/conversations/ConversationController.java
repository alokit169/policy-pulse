package com.policypulse.conversations;

import com.policypulse.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
@Tag(name = "Conversations")
public class ConversationController {
    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    @Operation(summary = "Conversations the caller may see, newest first")
    public PageResponse<ConversationResponse> search(
            @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return conversationService.search(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One conversation with its transcript")
    public ConversationResponse get(@PathVariable UUID id) {
        return conversationService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Log a new conversation with a customer")
    public ConversationResponse start(@Valid @RequestBody ConversationRequest request) {
        return conversationService.start(request);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a line to the transcript")
    public MessageResponse addMessage(@PathVariable UUID id, @Valid @RequestBody MessageRequest request) {
        return conversationService.addMessage(id, request);
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close a conversation and record how it went")
    public ConversationResponse close(@PathVariable UUID id,
                                      @Valid @RequestBody(required = false) CloseConversationRequest request) {
        return conversationService.close(id,
                request == null ? new CloseConversationRequest(null, null, null, null) : request);
    }
}
