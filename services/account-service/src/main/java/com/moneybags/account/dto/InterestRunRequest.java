package com.moneybags.account.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record InterestRunRequest(@NotNull LocalDate periodEndDate) {
}
