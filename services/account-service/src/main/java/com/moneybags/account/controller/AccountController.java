package com.moneybags.account.controller;

import com.moneybags.account.api.ApiModels.*;
import com.moneybags.account.dto.OwnedProductView;
import com.moneybags.account.entity.AccountStatus;
import com.moneybags.account.entity.ApplicationStatus;
import com.moneybags.account.security.RequestActorResolver;
import com.moneybags.account.service.AccountApplicationService;
import com.moneybags.account.service.AccountServicingService;
import com.moneybags.account.service.AccountProductOwnershipService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Staff-facing account API. Every caller is an authenticated employee acting on behalf
 * of a customer; the actor headers are injected by the gateway.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountApplicationService applicationService;
    private final AccountServicingService servicingService;
    private final AccountProductOwnershipService productOwnershipService;
    private final RequestActorResolver actors;

    // --- Applications ------------------------------------------------------

    @Operation(summary = "Open an account application on behalf of a customer")
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDetail createApplication(@Valid @RequestBody CreateApplicationRequest request,
                                               HttpServletRequest http) {
        return applicationService.create(actors.resolve(http), request);
    }

    @Operation(summary = "Search account applications within scope")
    @GetMapping("/applications")
    public PageResponse<ApplicationDetail> searchApplications(
            @RequestParam(required = false) String cifNo,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest http) {
        return applicationService.search(actors.resolve(http), cifNo, status, page, size);
    }

    @GetMapping("/applications/{applicationId}")
    public ApplicationDetail application(@PathVariable String applicationId, HttpServletRequest http) {
        return applicationService.detail(actors.resolve(http), applicationId);
    }

    @Operation(summary = "Approve an application; the maker may not approve their own work")
    @PostMapping("/applications/{applicationId}/approve")
    public ApplicationDetail approve(@PathVariable String applicationId,
                                     @RequestBody(required = false) DecisionRequest request,
                                     HttpServletRequest http) {
        return applicationService.approve(actors.resolve(http), applicationId, request);
    }

    @PostMapping("/applications/{applicationId}/reject")
    public ApplicationDetail reject(@PathVariable String applicationId,
                                    @Valid @RequestBody RejectionRequest request,
                                    HttpServletRequest http) {
        return applicationService.reject(actors.resolve(http), applicationId, request);
    }

    @PostMapping("/applications/{applicationId}/cancel")
    public ApplicationDetail cancel(@PathVariable String applicationId, HttpServletRequest http) {
        return applicationService.cancel(actors.resolve(http), applicationId);
    }

    // --- Accounts ----------------------------------------------------------

    @Operation(summary = "Search accounts within scope")
    @GetMapping
    public PageResponse<AccountDetail> search(@RequestParam(required = false) String cifNo,
                                              @RequestParam(required = false) String productCode,
                                              @RequestParam(required = false) AccountStatus status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "25") int size,
                                              HttpServletRequest http) {
        return servicingService.search(actors.resolve(http), cifNo, productCode, status, page, size);
    }

    @GetMapping("/by-number/{accountNumber}")
    public AccountDetail byNumber(@PathVariable String accountNumber, HttpServletRequest http) {
        return servicingService.byNumber(actors.resolve(http), accountNumber);
    }

    @GetMapping("/{accountId}")
    public AccountDetail detail(@PathVariable String accountId, HttpServletRequest http) {
        return servicingService.detail(actors.resolve(http), accountId);
    }

    @Operation(summary = "Ledger, held and available balance")
    @GetMapping("/{accountId}/balance")
    public BalanceView balance(@PathVariable String accountId, HttpServletRequest http) {
        return servicingService.balance(actors.resolve(http), accountId);
    }

    @GetMapping("/{accountId}/balance-history")
    public PageResponse<BalanceHistoryEntry> balanceHistory(@PathVariable String accountId,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "25") int size,
                                                            HttpServletRequest http) {
        return servicingService.balanceHistory(actors.resolve(http), accountId, page, size);
    }

    @GetMapping("/{accountId}/status-history")
    public List<StatusHistoryEntry> statusHistory(@PathVariable String accountId, HttpServletRequest http) {
        return servicingService.statusHistory(actors.resolve(http), accountId);
    }

    @Operation(summary = "List the catalogue products owned by an account")
    @GetMapping("/{accountId}/products")
    public List<OwnedProductView> ownedProducts(@PathVariable String accountId,
                                                HttpServletRequest http) {
        return productOwnershipService.list(actors.resolve(http), accountId);
    }

    @Operation(summary = "Break an active fixed deposit and return its full principal")
    @PostMapping("/{accountId}/products/{ownershipId}/break")
    public OwnedProductView breakFixedDeposit(@PathVariable String accountId,
                                              @PathVariable String ownershipId,
                                              HttpServletRequest http) {
        return productOwnershipService.breakFixedDeposit(
                actors.resolve(http), accountId, ownershipId);
    }

    // --- Lifecycle ---------------------------------------------------------

    @PostMapping("/{accountId}/freeze")
    public AccountDetail freeze(@PathVariable String accountId,
                                @RequestBody(required = false) StatusChangeRequest request,
                                HttpServletRequest http) {
        return servicingService.changeStatus(actors.resolve(http), accountId,
                AccountStatus.FROZEN, reasonOf(request, "Frozen by staff"));
    }

    @PostMapping("/{accountId}/unfreeze")
    public AccountDetail unfreeze(@PathVariable String accountId,
                                  @RequestBody(required = false) StatusChangeRequest request,
                                  HttpServletRequest http) {
        return servicingService.changeStatus(actors.resolve(http), accountId,
                AccountStatus.ACTIVE, reasonOf(request, "Unfrozen by staff"));
    }

    @PostMapping("/{accountId}/block")
    public AccountDetail block(@PathVariable String accountId,
                               @RequestBody(required = false) StatusChangeRequest request,
                               HttpServletRequest http) {
        return servicingService.changeStatus(actors.resolve(http), accountId,
                AccountStatus.BLOCKED, reasonOf(request, "Blocked by staff"));
    }

    @PostMapping("/{accountId}/unblock")
    public AccountDetail unblock(@PathVariable String accountId,
                                 @RequestBody(required = false) StatusChangeRequest request,
                                 HttpServletRequest http) {
        return servicingService.changeStatus(actors.resolve(http), accountId,
                AccountStatus.ACTIVE, reasonOf(request, "Unblocked by staff"));
    }

    @PostMapping("/{accountId}/mark-dormant")
    public AccountDetail markDormant(@PathVariable String accountId,
                                     @RequestBody(required = false) StatusChangeRequest request,
                                     HttpServletRequest http) {
        return servicingService.changeStatus(actors.resolve(http), accountId,
                AccountStatus.DORMANT, reasonOf(request, "Marked dormant"));
    }

    @PostMapping("/{accountId}/reactivate")
    public AccountDetail reactivate(@PathVariable String accountId,
                                    @RequestBody(required = false) StatusChangeRequest request,
                                    HttpServletRequest http) {
        return servicingService.changeStatus(actors.resolve(http), accountId,
                AccountStatus.ACTIVE, reasonOf(request, "Reactivated by staff"));
    }

    @Operation(summary = "Close a settled account")
    @PostMapping("/{accountId}/close")
    public AccountDetail close(@PathVariable String accountId,
                               @RequestBody(required = false) StatusChangeRequest request,
                               HttpServletRequest http) {
        return servicingService.changeStatus(actors.resolve(http), accountId,
                AccountStatus.CLOSED, reasonOf(request, "Closed by staff"));
    }

    // --- Holders, holds and limits -----------------------------------------

    @GetMapping("/{accountId}/holders")
    public List<HolderDetail> holders(@PathVariable String accountId, HttpServletRequest http) {
        return servicingService.holders(actors.resolve(http), accountId);
    }

    @PostMapping("/{accountId}/holders")
    @ResponseStatus(HttpStatus.CREATED)
    public HolderDetail addHolder(@PathVariable String accountId,
                                  @Valid @RequestBody AddHolderRequest request,
                                  HttpServletRequest http) {
        return servicingService.addHolder(actors.resolve(http), accountId, request);
    }

    @GetMapping("/{accountId}/holds")
    public List<HoldDetail> holds(@PathVariable String accountId, HttpServletRequest http) {
        return servicingService.holds(actors.resolve(http), accountId);
    }

    @Operation(summary = "Place a staff lien or operational hold")
    @PostMapping("/{accountId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldDetail placeHold(@PathVariable String accountId,
                                @Valid @RequestBody ManualHoldRequest request,
                                HttpServletRequest http) {
        return servicingService.placeManualHold(actors.resolve(http), accountId, request);
    }

    @GetMapping("/{accountId}/limits")
    public LimitsView limits(@PathVariable String accountId, HttpServletRequest http) {
        return servicingService.limits(actors.resolve(http), accountId);
    }

    @PutMapping("/{accountId}/limits")
    public LimitsView setLimits(@PathVariable String accountId,
                                @Valid @RequestBody LimitsRequest request,
                                HttpServletRequest http) {
        return servicingService.setLimits(actors.resolve(http), accountId, request);
    }

    private String reasonOf(StatusChangeRequest request, String fallback) {
        return (request == null || request.reason() == null) ? fallback : request.reason();
    }
}
