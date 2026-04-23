package com.tcleaner.dashboard.web;

import com.tcleaner.bot.BotInputParser;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.dto.CreateSubscriptionRequest;
import com.tcleaner.dashboard.dto.SubscriptionDto;
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

/**
 * REST API управления подписками: {@code /dashboard/api/subscriptions}.
 *
 * <p>RBAC централизован через {@link BotUserAccessPolicy}:
 * USER видит и управляет только своими подписками;
 * ADMIN может читать любые и удалять/приостанавливать, но не создавать.
 */
@RestController
@RequestMapping("/dashboard/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final ChatUpserter chatUpserter;
    private final ChatRepository chatRepository;
    private final BotUserAccessPolicy accessPolicy;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  ChatUpserter chatUpserter,
                                  ChatRepository chatRepository,
                                  BotUserAccessPolicy accessPolicy) {
        this.subscriptionService = subscriptionService;
        this.chatUpserter = chatUpserter;
        this.chatRepository = chatRepository;
        this.accessPolicy = accessPolicy;
    }

    /**
     * {@code GET /dashboard/api/subscriptions[?userId=...]}
     *
     * <p>USER: возвращает только свои подписки (параметр {@code userId} игнорируется).
     * ADMIN: если {@code userId} указан — подписки этого пользователя; иначе все подписки.
     */
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

    /**
     * {@code GET /dashboard/api/subscriptions/{id}}
     *
     * <p>RBAC: USER может видеть только свои подписки.
     */
    @GetMapping("/{id}")
    public SubscriptionDto get(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @PathVariable Long id) {
        ChatSubscription sub = subscriptionService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscription not found"));
        if (!accessPolicy.canSeeUser(
                principal.getDashboardRole(), principal.getBotUserId(), sub.getBotUserId())) {
            throw new AccessDeniedException("Доступ запрещён: нельзя просматривать подписку другого пользователя");
        }
        String display = chatRepository.findById(sub.getChatRefId())
                .map(this::formatChatDisplay)
                .orElse(String.valueOf(sub.getChatRefId()));
        return SubscriptionDto.fromEntity(sub, display);
    }

    /**
     * {@code POST /dashboard/api/subscriptions}
     *
     * <p>Только USER с привязанным {@code botUserId}. ADMIN не может создавать подписки.
     * {@code botUserId} берётся из principal — пользователь не может создать подписку за другого.
     *
     * @return 201 CREATED + созданный DTO
     */
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
        String chatIdRaw = input;

        if (username == null && !input.matches("^-?\\d+$")) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Invalid chat identifier: use @username, t.me link or numeric ID");
        }

        Chat chat = chatUpserter.upsert(username, chatIdRaw, topicId, null, Instant.now());

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

    /**
     * {@code PATCH /dashboard/api/subscriptions/{id}/pause}
     *
     * <p>RBAC: USER может паузить только свои подписки; ADMIN — любые.
     */
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

    /**
     * {@code PATCH /dashboard/api/subscriptions/{id}/resume}
     *
     * <p>RBAC: USER может возобновлять только свои подписки; ADMIN — любые.
     */
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

    /**
     * {@code DELETE /dashboard/api/subscriptions/{id}}
     *
     * <p>RBAC: USER может удалять только свои подписки; ADMIN — любые.
     *
     * @return 204 No Content
     */
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

    /**
     * Проверяет существование подписки и право доступа текущего пользователя.
     *
     * @param principal текущий пользователь
     * @param id        идентификатор подписки
     * @return найденная подписка
     * @throws ResponseStatusException 404 если не найдена
     * @throws AccessDeniedException   если USER пытается получить чужую подписку
     */
    private ChatSubscription requireVisible(DashboardUserDetails principal, Long id) {
        ChatSubscription sub = subscriptionService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Subscription not found"));
        if (!accessPolicy.canSeeUser(
                principal.getDashboardRole(), principal.getBotUserId(), sub.getBotUserId())) {
            throw new AccessDeniedException("Доступ запрещён: нельзя управлять подпиской другого пользователя");
        }
        return sub;
    }

    /**
     * Защита для USER-роли: без привязки к Telegram-пользователю операции невозможны.
     * ADMIN может быть без botUserId, поэтому проверка пропускается.
     */
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
        
        return subs.stream()
                .map(s -> SubscriptionDto.fromEntity(s, chatMap.getOrDefault(s.getChatRefId(),
                        String.valueOf(s.getChatRefId()))))
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
}
