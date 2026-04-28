package com.tcleaner.dashboard.web;

import com.tcleaner.bot.BotInputParser;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.dto.CreateSubscriptionRequest;
import com.tcleaner.dashboard.dto.SubscriptionDto;
import com.tcleaner.dashboard.repository.BotUserRepository;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.security.BotUserAccessPolicy;
import com.tcleaner.dashboard.service.ingestion.ChatUpserter;
import com.tcleaner.dashboard.service.subscription.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/dashboard/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final ChatUpserter chatUpserter;
    private final ChatRepository chatRepository;
    private final BotUserRepository botUserRepository;
    private final BotUserAccessPolicy accessPolicy;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  ChatUpserter chatUpserter,
                                  ChatRepository chatRepository,
                                  BotUserRepository botUserRepository,
                                  BotUserAccessPolicy accessPolicy) {
        this.subscriptionService = subscriptionService;
        this.chatUpserter = chatUpserter;
        this.chatRepository = chatRepository;
        this.botUserRepository = botUserRepository;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping
    public List<SubscriptionDto> list(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) Long userId) {
        List<ChatSubscription> subs;
        if (principal.getDashboardRole() == DashboardRole.ADMIN) {
            subs = (userId != null)
                    ? subscriptionService.listForUser(userId)
                    : subscriptionService.listAll();
        } else {
            requireBoundUser(principal);
            subs = subscriptionService.listForUser(principal.getBotUserId());
        }
        return toDtoList(subs);
    }

    @GetMapping("/{id}")
    public SubscriptionDto get(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @PathVariable Long id) {
        ChatSubscription sub = subscriptionService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscription not found"));
        if (!accessPolicy.canSeeUser(
                principal.getDashboardRole(), principal.getBotUserId(), sub.getBotUserId())) {
            // 404 вместо 403 — не раскрываем факт существования чужой подписки (IDOR)
            throw new ResponseStatusException(NOT_FOUND, "Subscription not found");
        }
        String display = chatRepository.findById(sub.getChatRefId())
                .map(this::formatChatDisplay)
                .orElse(String.valueOf(sub.getChatRefId()));
        return SubscriptionDto.fromEntity(sub, display);
    }

    // botUserId берётся из principal — USER не может создать подписку за другого; ADMIN не создаёт вообще.
    @PostMapping
    public ResponseEntity<SubscriptionDto> create(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        if (principal.getDashboardRole() == DashboardRole.ADMIN) {
            throw new AccessDeniedException("ADMIN cannot create subscriptions on behalf of users");
        }
        if (principal.getBotUserId() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Account not bound to a Telegram user");
        }

        String input = request.chatIdentifier().trim();
        String username = BotInputParser.extractUsername(input);
        Integer topicId = BotInputParser.extractTopicId(input);

        if (username == null && !input.matches("^-?\\d+$")) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Invalid chat identifier: use @username, t.me link or numeric ID");
        }

        Chat chat = chatUpserter.upsert(username, input, topicId, null, Instant.now());

        ChatSubscription created;
        try {
            Instant sinceDate = request.sinceDate() != null ? request.sinceDate() : Instant.now();
            created = subscriptionService.create(
                    principal.getBotUserId(),
                    chat.getId(),
                    request.periodHours(),
                    request.desiredTimeMsk(),
                    sinceDate);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(CONFLICT, e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(CONFLICT, "user already has an active subscription");
        }
        String display = formatChatDisplay(chat);
        SubscriptionDto dto = SubscriptionDto.fromEntity(created, display);
        return ResponseEntity
                .created(URI.create("/dashboard/api/subscriptions/" + created.getId()))
                .body(dto);
    }

    @PatchMapping("/{id}/pause")
    public SubscriptionDto pause(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @PathVariable Long id) {
        ChatSubscription sub = requireVisible(principal, id);
        try {
            ChatSubscription paused = subscriptionService.pause(sub.getId());
            return SubscriptionDto.fromEntity(paused, getChatDisplay(paused.getChatRefId()));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    @PatchMapping("/{id}/resume")
    public SubscriptionDto resume(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @PathVariable Long id) {
        ChatSubscription sub = requireVisible(principal, id);
        try {
            ChatSubscription resumed = subscriptionService.resume(sub.getId());
            return SubscriptionDto.fromEntity(resumed, getChatDisplay(resumed.getChatRefId()));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(CONFLICT, e.getMessage());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @PathVariable Long id) {
        ChatSubscription sub = requireVisible(principal, id);
        try {
            subscriptionService.delete(sub.getId());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    private ChatSubscription requireVisible(DashboardUserDetails principal, Long id) {
        ChatSubscription sub = subscriptionService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscription not found"));
        if (!accessPolicy.canSeeUser(
                principal.getDashboardRole(), principal.getBotUserId(), sub.getBotUserId())) {
            // 404 вместо 403 — не раскрываем факт существования чужой подписки (IDOR)
            throw new ResponseStatusException(NOT_FOUND, "Subscription not found");
        }
        return sub;
    }

    private void requireBoundUser(DashboardUserDetails principal) {
        if (principal.getDashboardRole() != DashboardRole.ADMIN
                && principal.getBotUserId() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Account not bound to a Telegram user");
        }
    }

    private List<SubscriptionDto> toDtoList(List<ChatSubscription> subs) {
        if (subs.isEmpty()) {
            return List.of();
        }
        Set<Long> chatIds = subs.stream()
                .map(ChatSubscription::getChatRefId)
                .collect(Collectors.toSet());
        Map<Long, String> chatMap = chatRepository.findAllById(chatIds).stream()
                .collect(Collectors.toMap(Chat::getId, this::formatChatDisplay));

        Set<Long> userIds = subs.stream()
                .map(ChatSubscription::getBotUserId)
                .collect(Collectors.toSet());
        Map<Long, String> userMap = botUserRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(BotUser::getBotUserId, this::formatUserDisplay));

        return subs.stream()
                .map(s -> SubscriptionDto.fromEntity(s,
                        chatMap.getOrDefault(s.getChatRefId(), String.valueOf(s.getChatRefId())),
                        userMap.get(s.getBotUserId())))
                .toList();
    }

    private String getChatDisplay(long chatRefId) {
        return chatRepository.findById(chatRefId)
                .map(this::formatChatDisplay)
                .orElse(String.valueOf(chatRefId));
    }

    private String formatChatDisplay(Chat chat) {
        if (chat.getChatTitle() != null && !chat.getChatTitle().isBlank()) {
            return chat.getChatTitle() + " (" + chat.getChatIdRaw() + ")";
        }
        return chat.getChatIdRaw();
    }

    private String formatUserDisplay(BotUser user) {
        if (user.getUsername() != null) return "@" + user.getUsername();
        if (user.getDisplayName() != null) return user.getDisplayName();
        return String.valueOf(user.getBotUserId());
    }
}
