package com.chaosledger.ledger.api;

import com.chaosledger.ledger.api.dto.TransferRequest;
import com.chaosledger.ledger.domain.AccountCommandHandler;
import com.chaosledger.ledger.domain.commands.TransferCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final AccountCommandHandler commandHandler;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> transfer(@Valid @RequestBody TransferRequest request) {
        UUID transferId = commandHandler.handle(
                new TransferCommand(
                        request.fromAccountId(),
                        request.toAccountId(),
                        request.amount(),
                        request.idempotencyKey()));
        return Map.of("transferId", transferId);
    }
}
