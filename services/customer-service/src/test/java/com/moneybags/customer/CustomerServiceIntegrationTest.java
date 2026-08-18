package com.moneybags.customer;

import com.moneybags.customer.client.SecurityClient;
import com.moneybags.customer.dto.CustomerOperations.*;
import com.moneybags.customer.dto.CustomerRequest;
import com.moneybags.customer.entity.*;
import com.moneybags.customer.enums.*;
import com.moneybags.customer.exception.ConflictException;
import com.moneybags.customer.repository.*;
import com.moneybags.customer.service.CustomerOperationsService;
import com.moneybags.customer.service.CustomerService;
import com.moneybags.customer.service.KycDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CustomerServiceIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired CustomerService customerService;
    @Autowired CustomerOperationsService operations;
    @Autowired CustomerRepository customers;
    @Autowired CustomerAddressRepository addresses;
    @Autowired KycDocumentRepository documents;
    @Autowired KycRejectionHistoryRepository rejectionHistory;
    @Autowired CustomerDomainEventRepository domainEvents;
    @Autowired KycDocumentService kycDocumentService;
    @MockBean SecurityClient securityClient;

    @BeforeEach
    void seedH2Only() {
        domainEvents.deleteAll();
        rejectionHistory.deleteAll();
        documents.deleteAll();
        addresses.deleteAll();
        customers.deleteAll();
        when(securityClient.findUser(101L)).thenReturn(new SecurityClient.UserSummary(101L, "demo.customer", "ACTIVE"));
    }

    private String createCustomer() {
        return customerService.create(new CustomerRequest(101L, "Asha", "Sharma", LocalDate.of(1995, 5, 10),
                Gender.FEMALE, "9876543210", "asha@example.com", "ABCDE1234F", CustomerStatus.ACTIVE, KycStatus.PENDING)).cifNo();
    }

    @Test
    void openApiAdvertisesGatewaySessionAuthorization() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.sessionId.type").value("apiKey"))
                .andExpect(jsonPath("$.components.securitySchemes.sessionId.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.sessionId.name").value("X-Session-Id"))
                .andExpect(jsonPath("$.security[0].sessionId").isArray());
    }

    @Test
    void createsCustomerWithGeneratedCifAndRejectsDuplicatePanOrUser() {
        String cif = createCustomer();
        assertThat(cif).startsWith("CIF");
        assertThat(customers.findById(cif)).isPresent();
        assertThat(customers.findById(cif).orElseThrow().getRiskClassification()).isEqualTo(RiskClassification.LOW);

        assertThatThrownBy(() -> customerService.create(new CustomerRequest(101L, "Another", null, LocalDate.of(1990, 1, 1),
                Gender.MALE, "9123456789", "another@example.com", "ABCDE1234F", CustomerStatus.ACTIVE, KycStatus.PENDING)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void managesProfileManagerAndMultipleCurrentAddresses() {
        String cif = createCustomer();
        operations.update(cif, new Update("Asha", "Verma", LocalDate.of(1995, 5, 10), Gender.FEMALE, "9876543210", "asha.verma@example.com"));
        operations.assignManager(cif, 77L);
        operations.addAddress(cif, new Address(AddressType.RESIDENTIAL, "10 Park Road", "Mumbai", "Maharashtra", "400001", "India", true));
        operations.addAddress(cif, new Address(AddressType.OFFICE, "22 Business Bay", "Mumbai", "Maharashtra", "400051", "India", true));

        Customer customer = customers.findById(cif).orElseThrow();
        assertThat(customer.getLastName()).isEqualTo("Verma");
        assertThat(customer.getRelationshipManagerEmpId()).isEqualTo(77L);
        assertThat(operations.addresses(cif)).hasSize(2);
        assertThat(operations.byManager(77L)).hasSize(1);
        assertThat(((Map<?, ?>) operations.completeness(cif)).get("percentage")).isEqualTo(100);

        operations.removeManager(cif);
        assertThat(customers.findById(cif).orElseThrow().getRelationshipManagerEmpId()).isNull();
    }

    @Test
    void replacesTheCurrentAddressOfTheSameTypeButKeepsAddressHistory() {
        String cif = createCustomer();

        CustomerAddress initial = operations.addAddress(cif,
                new Address(AddressType.RESIDENTIAL, "10 Park Road", "Mumbai", "Maharashtra", "400001", "India", true));
        CustomerAddress replacement = operations.addAddress(cif,
                new Address(AddressType.RESIDENTIAL, "20 Lake Road", "Mumbai", "Maharashtra", "400002", "India", true));
        CustomerAddress historical = operations.addAddress(cif,
                new Address(AddressType.RESIDENTIAL, "1 Old Road", "Pune", "Maharashtra", "411001", "India", false));

        assertThat(replacement.getAddressId()).isEqualTo(initial.getAddressId());
        assertThat(addresses.findById(initial.getAddressId()).orElseThrow().getLine1()).isEqualTo("20 Lake Road");
        assertThat(historical.getAddressId()).isNotEqualTo(initial.getAddressId());
        assertThat(operations.addresses(cif)).hasSize(2);
    }

    @Test
    void recordsKycRejectionAsNewAttemptThenAllowsApproval() {
        String cif = createCustomer();
        KycDocument first = (KycDocument) operations.submitKyc(cif, new KycSubmit("PAN", "ABCDE1234F", null, "/kyc/pan-v1.pdf"));
        assertThat(first.getDocNumber()).doesNotContain("ABCDE1234F");
        assertThat(first.getDocumentNumberHash()).hasSize(64);
        operations.assignKyc(cif, first.getDocId(), 88L);
        operations.decideKyc(cif, first.getDocId(), new KycDecision(DocumentVerifyStatus.REJECTED, 88L, "Image is unreadable"));

        Customer customer = customers.findById(cif).orElseThrow();
        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(customer.getKycFailureCount()).isEqualTo(1);
        assertThat(operations.kycHistory(cif)).hasSize(1);
        assertThat(documents.findById(first.getDocId()).orElseThrow().getVerifyStatus()).isEqualTo(DocumentVerifyStatus.REJECTED);
        assertThat(domainEvents.findAll()).extracting(CustomerDomainEvent::getEventType).contains("KycRejected");

        KycDocument replacement = (KycDocument) operations.submitKyc(cif, new KycSubmit("PAN", "ABCDE1234F", null, "/kyc/pan-v2.pdf"));
        operations.decideKyc(cif, replacement.getDocId(), new KycDecision(DocumentVerifyStatus.VERIFIED, 88L, null));
        assertThat(customers.findById(cif).orElseThrow().getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(domainEvents.findAll()).extracting(CustomerDomainEvent::getEventType).contains("KycVerified");
    }

    @Test
    void acceptsAnIdempotentRetryOfTheSameKycDecision() {
        String cif = createCustomer();
        KycDocument document = kycDocumentService.submit(cif,
                new KycSubmit("PAN", "ABCDE1234F", null, "/kyc/pan.pdf"));
        KycDecision decision = new KycDecision(DocumentVerifyStatus.VERIFIED, 88L, null);

        kycDocumentService.decide(cif, document.getDocId(), decision);
        KycDocument retriedDecision = kycDocumentService.decide(cif, document.getDocId(), decision);

        assertThat(retriedDecision.getVerifyStatus()).isEqualTo(DocumentVerifyStatus.VERIFIED);
        assertThat(customers.findById(cif).orElseThrow().getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(domainEvents.findAll()).extracting(CustomerDomainEvent::getEventType)
                .containsOnly("KycVerified");
    }

    @Test
    void managesRiskCommunicationPreferencesAndAccountEligibility() {
        String cif = createCustomer();
        operations.addAddress(cif, new Address(AddressType.PERMANENT, "1 Demo Street", "Mumbai", "Maharashtra", "400001", "India", true));
        customerService.classifyRisk(cif, new RiskUpdate(RiskClassification.HIGH));
        customerService.updateCommunicationPreferences(cif,
                new CommunicationPreferences(CommunicationChannel.SMS, false, true, true));

        Customer customer = customers.findById(cif).orElseThrow();
        customer.setKycStatus(KycStatus.VERIFIED);
        customers.save(customer);

        assertThat(customerService.eligibility(cif).eligible()).isTrue();
        assertThat(customerService.eligibility(cif).riskClassification()).isEqualTo(RiskClassification.HIGH);
        assertThat(customerService.communicationPreferences(cif))
                .containsEntry("preferredChannel", CommunicationChannel.SMS)
                .containsEntry("emailEnabled", false)
                .containsEntry("pushEnabled", true);
    }

    @Test
    void createsOneOutboxExpiryAlertPerKycDocument() {
        String cif = createCustomer();
        KycDocument document = kycDocumentService.submit(cif,
                new KycSubmit("PASSPORT", "P1234567", LocalDate.now().plusDays(10), "demo://kyc/passport"));

        assertThat(kycDocumentService.processExpiryAlerts(LocalDate.now())).isEqualTo(1);
        assertThat(kycDocumentService.processExpiryAlerts(LocalDate.now())).isZero();
        assertThat(documents.findById(document.getDocId()).orElseThrow().getExpiryAlertedAt()).isNotNull();
        assertThat(domainEvents.findAll()).extracting(CustomerDomainEvent::getEventType)
                .containsExactly("KycDocumentExpiring");
    }

    @Test
    void exposesGeneratedSwaggerAndTheIdeOpenApiFile() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/v1/customers']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/customers/{cif}/eligibility']").exists());

        mockMvc.perform(get("/openapi.yml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Moneybags Customer Service API")));
    }
}
