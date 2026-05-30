package com.chaosledger.ledger.domain;

import com.chaosledger.ledger.domain.commands.*;
import com.chaosledger.ledger.domain.events.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountCommandHandler {

    private final EventStore eventStore;
    private final IdempotencyStore idempotencyStore;

    @Transactional
    public UUID handle(OpenAccountCommand cmd) {
        UUID accountId = UUID.randomUUID();

        AccountOpened event = new AccountOpened(
                UUID.randomUUID(),
                accountId,
                cmd.ownerId(),
                cmd.currency(),
                Instant.now()
        );

        eventStore.append(accountId, 0L, List.of(event));
        log.info("Account opened: {} for owner {} in {}", accountId, cmd.ownerId(), cmd.currency());
        return accountId;
    }

    @Transactional
    public void handle(DepositCommand cmd) {
        checkIdempotency(cmd.idempotencyKey());

        Account account = loadAccount(cmd.accountId());
        account.validateDeposit(cmd.amount());

        MoneyDeposited event = new MoneyDeposited(
                UUID.randomUUID(),
                cmd.accountId(),
                cmd.amount(),
                account.getCurrency(),
                cmd.idempotencyKey(),
                Instant.now()
        );

        eventStore.append(cmd.accountId(), account.getVersion(), List.of(event));
        idempotencyStore.record(cmd.idempotencyKey(), "Deposit", cmd.accountId());
        log.info("Deposited {} {} to account {}", cmd.amount(), account.getCurrency(), cmd.accountId());
    }

    @Transactional
    public void handle(WithdrawCommand cmd) {
        checkIdempotency(cmd.idempotencyKey());

        Account account = loadAccount(cmd.accountId());
        account.validateWithdrawal(cmd.amount());

        MoneyWithdrawn event = new MoneyWithdrawn(
                UUID.randomUUID(),
                cmd.accountId(),
                cmd.amount(),
                account.getCurrency(),
                cmd.idempotencyKey(),
                Instant.now()
        );

        eventStore.append(cmd.accountId(), account.getVersion(), List.of(event));
        idempotencyStore.record(cmd.idempotencyKey(), "Withdraw", cmd.accountId());
        log.info("Withdrew {} {} from account {}", cmd.amount(), account.getCurrency(), cmd.accountId());
    }

    @Transactional
    public UUID handle(TransferCommand cmd) {
        checkIdempotency(cmd.idempotencyKey());

        if (cmd.fromAccountId().equals(cmd.toAccountId())) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        Account sender = loadAccount(cmd.fromAccountId());
        Account receiver = loadAccount(cmd.toAccountId());

        if (!sender.getCurrency().equals(receiver.getCurrency())) {
            throw new IllegalArgumentException("Cross-currency transfers not supported");
        }

        sender.validateTransferOut(cmd.amount());

        UUID transferId = UUID.randomUUID();
        Instant now = Instant.now();

        MoneyTransferred debitEvent = new MoneyTransferred(
                UUID.randomUUID(),
                cmd.fromAccountId(),
                cmd.toAccountId(),
                cmd.amount(),
                sender.getCurrency(),
                cmd.idempotencyKey(),
                now
        );

        TransferReceived creditEvent = new TransferReceived(
                UUID.randomUUID(),
                cmd.toAccountId(),
                cmd.fromAccountId(),
                cmd.amount(),
                sender.getCurrency(),
                cmd.idempotencyKey(),
                now
        );

        eventStore.append(cmd.fromAccountId(), sender.getVersion(), List.of(debitEvent));
        eventStore.append(cmd.toAccountId(), receiver.getVersion(), List.of(creditEvent));
        idempotencyStore.record(cmd.idempotencyKey(), "Transfer", cmd.fromAccountId());

        log.info("Transferred {} {} from {} to {}",
                cmd.amount(), sender.getCurrency(), cmd.fromAccountId(), cmd.toAccountId());
        return transferId;
    }

    public Account loadAccount(UUID accountId) {
        List<Event> events = eventStore.loadEvents(accountId);
        if (events.isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
        return Account.reconstitute(events);
    }

    private void checkIdempotency(UUID idempotencyKey) {
        if (idempotencyStore.exists(idempotencyKey)) {
            throw new DuplicateCommandException(idempotencyKey);
        }
    }
}
