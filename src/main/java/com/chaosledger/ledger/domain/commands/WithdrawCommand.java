package com.chaosledger.ledger.domain.commands;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record WithdrawCommand(
        @NotNull UUID accountId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull UUID idempotencyKey
) {}
