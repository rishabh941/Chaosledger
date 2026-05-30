package com.chaosledger.ledger.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record WithdrawRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull UUID idempotencyKey
) {}
