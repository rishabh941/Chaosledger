package com.chaosledger.ledger.api;

import com.chaosledger.ledger.api.dto.*;
import com.chaosledger.ledger.domain.Account;
import com.chaosledger.ledger.domain.AccountCommandHandler;
import com.chaosledger.ledger.domain.AccountNotFoundException;
import com.chaosledger.ledger.domain.commands.DepositCommand;
import com.chaosledger.ledger.domain.commands.OpenAccountCommand;
import com.chaosledger.ledger.domain.commands.WithdrawCommand;
import com.chaosledger.ledger.domain.events.Event;
import com.chaosledger.ledger.domain.events.EventStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountCommandHandler commandHandler;
    private final EventStore eventStore;

    @PostMapping
    public ResponseEntity<Map<String, UUID>> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        UUID accountId = commandHandler.handle(
                new OpenAccountCommand(request.ownerId(), request.currency()));
        return ResponseEntity
                .created(URI.create("/api/accounts/" + accountId))
                .body(Map.of("accountId", accountId));
    }

    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@PathVariable UUID accountId) {
        Account account = commandHandler.loadAccount(accountId);
        return AccountResponse.from(account);
    }

    @GetMapping("/{accountId}/events")
    public List<EventResponse> getAccountEvents(@PathVariable UUID accountId) {
        List<Event> events = eventStore.loadEvents(accountId);
        if (events.isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
        return events.stream().map(EventResponse::from).toList();
    }

    @PostMapping("/{accountId}/deposit")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody DepositRequest request) {
        commandHandler.handle(
                new DepositCommand(accountId, request.amount(), request.idempotencyKey()));
        return Map.of("status", "deposited");
    }

    @PostMapping("/{accountId}/withdraw")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody WithdrawRequest request) {
        commandHandler.handle(
                new WithdrawCommand(accountId, request.amount(), request.idempotencyKey()));
        return Map.of("status", "withdrawn");
    }
}
