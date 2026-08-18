package com.moneybags.account.service;

import com.moneybags.account.client.ProductClient;
import com.moneybags.account.dto.OwnedProductProjectionRequest;
import com.moneybags.account.dto.OwnedProductView;
import com.moneybags.account.entity.*;
import com.moneybags.account.repository.AccountProductOwnershipRepository;
import com.moneybags.account.repository.AccountRepository;
import com.moneybags.account.security.RequestActor;
import com.moneybags.account.support.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountProductOwnershipService {

    private static final Set<String> INTEGRATED_PRODUCTS = Set.of(
            "SAV-REG", "SAV-SENIOR", "CUR-BASIC", "FD-12M");

    private final AccountProductOwnershipRepository ownerships;
    private final AccountRepository accounts;
    private final FdSettlementService fdSettlements;

    @Transactional(readOnly = true)
    public List<OwnedProductView> list(RequestActor actor, String accountId) {
        actor.require(RequestActor.PERMISSION_VIEW);
        Account account = requireAccount(accountId);
        actor.requireBranchAccess(account.getBranchCode());
        return ownerships.findByOwnerAccountIdOrderByAcquiredOnDescCreatedAtDesc(accountId)
                .stream().map(this::view).toList();
    }

    @Transactional
    public OwnedProductView breakFixedDeposit(RequestActor actor, String accountId,
                                              String ownershipId) {
        Account account = requireAccount(accountId);
        actor.requireBranchAccess(account.getBranchCode());
        AccountProductOwnership ownership = ownerships.findById(ownershipId)
                .orElseThrow(() -> ApiException.notFound("OWNED_PRODUCT_NOT_FOUND",
                        "No owned product exists with id " + ownershipId));
        if (!ownership.getOwnerAccountId().equals(accountId)) {
            throw ApiException.conflict("OWNERSHIP_ACCOUNT_MISMATCH",
                    "The fixed deposit belongs to another account");
        }
        return view(fdSettlements.requestPrematureBreak(actor, ownershipId));
    }

    @Transactional
    public void recordAccountOpening(Account account, ProductClient.EffectiveProduct product,
                                     BigDecimal openingAmount) {
        if (!INTEGRATED_PRODUCTS.contains(product.productCode())) return;
        if (ownerships.findByOwnerAccountIdAndAcquisitionType(
                account.getAccountId(), ProductAcquisitionType.ACCOUNT_OPENING).isPresent()) return;

        ownerships.save(AccountProductOwnership.builder()
                .ownershipId(UUID.randomUUID().toString())
                .ownerAccountId(account.getAccountId())
                .productCode(product.productCode())
                .productName(product.productName())
                .productType(product.productType())
                .productVersionId(product.productVersionId())
                .productVersionNumber(product.versionNumber())
                .acquisitionType(ProductAcquisitionType.ACCOUNT_OPENING)
                .principalAmount(openingAmount)
                .currency(product.currency())
                .interestRate(product.interestRate())
                .tenureMonths(product.tenureMonths())
                .acquiredOn(account.getOpenedOn())
                .maturityDate(account.getMaturityDate())
                .status(statusFor(account.getStatus()))
                .build());
    }

    @Transactional
    public OwnedProductView projectPurchase(OwnedProductProjectionRequest request) {
        return switch (request.action()) {
            case "ACTIVATE" -> activate(request);
            case "REVERSE" -> reverse(request);
            default -> throw ApiException.invalid("INVALID_OWNERSHIP_ACTION",
                    "Ownership action must be ACTIVATE or REVERSE");
        };
    }

    @Transactional
    public void syncPrimaryStatus(Account account) {
        ownerships.findByOwnerAccountIdAndAcquisitionType(
                        account.getAccountId(), ProductAcquisitionType.ACCOUNT_OPENING)
                .ifPresent(ownership -> ownership.setStatus(statusFor(account.getStatus())));
    }

    private OwnedProductView activate(OwnedProductProjectionRequest request) {
        if (!"FD-12M".equals(request.productCode()) || !"TERM_DEPOSIT".equals(request.productType())) {
            throw ApiException.unprocessable("PRODUCT_NOT_PURCHASABLE",
                    "Only the integrated 12-month fixed deposit can be purchased by transaction");
        }
        Account account = requireAccount(request.ownerAccountId());
        if (!account.getCurrency().equals(request.currency())) {
            throw ApiException.invalid("CURRENCY_MISMATCH", "Ownership currency differs from account currency");
        }

        AccountProductOwnership existing = ownerships.findById(request.ownershipId())
                .or(() -> ownerships.findByPurchaseTransactionId(request.purchaseTransactionId()))
                .orElse(null);
        if (existing != null) {
            if (!existing.getOwnerAccountId().equals(request.ownerAccountId())
                    || !existing.getProductCode().equals(request.productCode())) {
                throw ApiException.conflict("OWNERSHIP_IDEMPOTENCY_CONFLICT",
                        "The purchase transaction is already linked to different ownership data");
            }
            return view(existing);
        }

        AccountProductOwnership ownership = ownerships.save(AccountProductOwnership.builder()
                .ownershipId(request.ownershipId())
                .ownerAccountId(request.ownerAccountId())
                .productCode(request.productCode())
                .productName(request.productName())
                .productType(request.productType())
                .productVersionId(request.productVersionId())
                .productVersionNumber(request.productVersionNumber())
                .acquisitionType(ProductAcquisitionType.TRANSACTION_PURCHASE)
                .principalAmount(request.principalAmount())
                .currency(request.currency())
                .interestRate(request.interestRate())
                .tenureMonths(request.tenureMonths())
                .acquiredOn(request.acquiredOn())
                .maturityDate(request.maturityDate())
                .status(ProductOwnershipStatus.ACTIVE)
                .purchaseTransactionId(request.purchaseTransactionId())
                .build());
        return view(ownership);
    }

    private OwnedProductView reverse(OwnedProductProjectionRequest request) {
        if (request.reversalTransactionId() == null || request.reversalTransactionId().isBlank()) {
            throw ApiException.invalid("REVERSAL_TRANSACTION_REQUIRED",
                    "A reversal transaction ID is required");
        }
        AccountProductOwnership ownership = ownerships.findById(request.ownershipId())
                .or(() -> ownerships.findByPurchaseTransactionId(request.purchaseTransactionId()))
                .orElseThrow(() -> ApiException.notFound("OWNED_PRODUCT_NOT_FOUND",
                        "No owned product exists for purchase " + request.purchaseTransactionId()));
        if (!ownership.getOwnerAccountId().equals(request.ownerAccountId())) {
            throw ApiException.conflict("OWNERSHIP_ACCOUNT_MISMATCH",
                    "The owned product belongs to another account");
        }
        if (ownership.getStatus() == ProductOwnershipStatus.REVERSED) {
            if (!request.reversalTransactionId().equals(ownership.getReversalTransactionId())) {
                throw ApiException.conflict("OWNERSHIP_ALREADY_REVERSED",
                        "The owned product was reversed by another transaction");
            }
            return view(ownership);
        }
        if (ownership.getStatus() != ProductOwnershipStatus.ACTIVE) {
            throw ApiException.conflict("OWNERSHIP_NOT_REVERSIBLE",
                    "Only an active purchased product can be reversed");
        }
        ownership.setStatus(ProductOwnershipStatus.REVERSED);
        ownership.setReversalTransactionId(request.reversalTransactionId());
        return view(ownership);
    }

    private Account requireAccount(String accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND",
                        "No account with id " + accountId));
    }

    private ProductOwnershipStatus statusFor(AccountStatus status) {
        return switch (status) {
            case PENDING_ACTIVATION -> ProductOwnershipStatus.PENDING;
            case MATURED -> ProductOwnershipStatus.MATURED;
            case CLOSED -> ProductOwnershipStatus.CLOSED;
            default -> ProductOwnershipStatus.ACTIVE;
        };
    }

    private OwnedProductView view(AccountProductOwnership ownership) {
        return new OwnedProductView(
                ownership.getOwnershipId(), ownership.getOwnerAccountId(),
                ownership.getProductCode(), ownership.getProductName(), ownership.getProductType(),
                ownership.getProductVersionId(), ownership.getProductVersionNumber(),
                ownership.getAcquisitionType().name(), ownership.getPrincipalAmount(),
                ownership.getCurrency(), ownership.getInterestRate(), ownership.getTenureMonths(),
                ownership.getAcquiredOn(), ownership.getMaturityDate(), ownership.getStatus().name(),
                ownership.getPurchaseTransactionId(), ownership.getReversalTransactionId(),
                ownership.getSettlementStatus().name(),
                ownership.getSettlementType() == null ? null : ownership.getSettlementType().name(),
                ownership.getSettlementDestinationAccountId(),
                ownership.getSettlementInterestAmount(), ownership.getSettlementTransactionId(),
                ownership.getSettledAt(),
                ownership.getCreatedAt(), ownership.getUpdatedAt());
    }
}
