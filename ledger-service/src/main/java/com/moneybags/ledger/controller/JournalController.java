package com.moneybags.ledger.controller;

import com.moneybags.ledger.dto.*;
import com.moneybags.ledger.service.JournalPostingService;
import com.moneybags.ledger.service.JournalQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@org.springframework.web.bind.annotation.RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class JournalController {
    private final JournalPostingService postingService;
    private final JournalQueryService queryService;

    @PostMapping("/journals")
    @Operation(summary = "Post a balanced journal",
            description = "Atomically validates and posts all lines, updates GL balances, and returns the existing journal for an identical idempotent retry.",
            requestBody = @RequestBody(content = @Content(mediaType = "application/json", examples = @ExampleObject(
                    name = "Internal transfer payer journal",
                    value = """
                            {
                              "journalReference": "JE-503-PAYER",
                              "transactionId": 503,
                              "description": "Payer side of internal transfer",
                              "currencyCode": "INR",
                              "lines": [
                                {"ledgerCode":"210000","customerAccountId":10001,"side":"DEBIT","amount":252.00,"description":"Reduce customer deposit liability"},
                                {"ledgerCode":"220100","side":"CREDIT","amount":250.00,"description":"Move principal into internal clearing"},
                                {"ledgerCode":"410100","side":"CREDIT","amount":2.00,"description":"Recognise transfer fee income"}
                              ]
                            }
                            """))))
    @ApiResponse(responseCode = "200", description = "Journal posted or identical retry returned")
    @ApiResponse(responseCode = "422", description = "Journal violates accounting rules")
    public JournalResponse post(@Valid @org.springframework.web.bind.annotation.RequestBody JournalPostRequest request) {
        return postingService.post(request);
    }

    @PostMapping("/journals/{journalId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reverse a posted journal",
            description = "Creates and posts a new journal with opposite sides. The original lines remain unchanged.")
    @ApiResponse(responseCode = "409", description = "Journal was already reversed")
    public JournalResponse reverse(
            @PathVariable Long journalId,
            @Valid @org.springframework.web.bind.annotation.RequestBody(required = false) ReversalRequest request
    ) {
        return postingService.reverse(journalId, request);
    }

    @GetMapping("/journals/{id}")
    @Operation(summary = "Get a journal by ID")
    public JournalResponse findById(@PathVariable Long id) {
        return queryService.findById(id);
    }

    @GetMapping("/journals/reference/{reference}")
    @Operation(summary = "Get a journal by its idempotency reference")
    public JournalResponse findByReference(@PathVariable String reference) {
        return queryService.findByReference(reference);
    }

    @GetMapping("/journals")
    @Operation(summary = "Query journal history",
            description = "Filter by transactionId or customerAccountId. With no filter, returns all journals newest first.")
    public List<JournalResponse> search(
            @RequestParam(required = false) String transactionId,
            @RequestParam(required = false) String customerAccountId
    ) {
        return queryService.search(transactionId, customerAccountId);
    }

    @GetMapping("/customer-accounts/{accountId}/entries")
    @Operation(summary = "Get journal-line audit history for a customer account",
            description = "This is an accounting association only; it is not the customer's live or available balance.")
    public List<CustomerLedgerEntryResponse> customerEntries(@PathVariable String accountId) {
        return queryService.customerEntries(accountId);
    }
}
