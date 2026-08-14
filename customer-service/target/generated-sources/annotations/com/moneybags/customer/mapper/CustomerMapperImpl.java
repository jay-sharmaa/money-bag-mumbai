package com.moneybags.customer.mapper;

import com.moneybags.customer.dto.CustomerRequest;
import com.moneybags.customer.dto.CustomerResponse;
import com.moneybags.customer.entity.Customer;
import com.moneybags.customer.enums.CommunicationChannel;
import com.moneybags.customer.enums.CustomerStatus;
import com.moneybags.customer.enums.Gender;
import com.moneybags.customer.enums.KycStatus;
import com.moneybags.customer.enums.RiskClassification;
import java.time.LocalDate;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-08-14T09:35:52+0530",
    comments = "version: 1.5.5.Final, compiler: javac, environment: Java 17.0.12 (Oracle Corporation)"
)
@Component
public class CustomerMapperImpl implements CustomerMapper {

    @Override
    public Customer toEntity(CustomerRequest request) {
        if ( request == null ) {
            return null;
        }

        Customer.CustomerBuilder customer = Customer.builder();

        customer.userId( request.userId() );
        customer.firstName( request.firstName() );
        customer.lastName( request.lastName() );
        customer.dob( request.dob() );
        customer.gender( request.gender() );
        customer.panNo( request.panNo() );
        customer.mobile( request.mobile() );
        customer.email( request.email() );
        customer.status( request.status() );
        customer.kycStatus( request.kycStatus() );

        return customer.build();
    }

    @Override
    public CustomerResponse toResponse(Customer customer) {
        if ( customer == null ) {
            return null;
        }

        String cifNo = null;
        Long userId = null;
        Long relationshipManagerEmpId = null;
        String firstName = null;
        String lastName = null;
        LocalDate dob = null;
        Gender gender = null;
        String mobile = null;
        String email = null;
        String panNo = null;
        CustomerStatus status = null;
        KycStatus kycStatus = null;
        RiskClassification riskClassification = null;
        CommunicationChannel preferredCommunicationChannel = null;
        Boolean emailNotificationsEnabled = null;
        Boolean smsNotificationsEnabled = null;
        Boolean pushNotificationsEnabled = null;
        Integer kycFailureCount = null;

        cifNo = customer.getCifNo();
        userId = customer.getUserId();
        relationshipManagerEmpId = customer.getRelationshipManagerEmpId();
        firstName = customer.getFirstName();
        lastName = customer.getLastName();
        dob = customer.getDob();
        gender = customer.getGender();
        mobile = customer.getMobile();
        email = customer.getEmail();
        panNo = customer.getPanNo();
        status = customer.getStatus();
        kycStatus = customer.getKycStatus();
        riskClassification = customer.getRiskClassification();
        preferredCommunicationChannel = customer.getPreferredCommunicationChannel();
        emailNotificationsEnabled = customer.getEmailNotificationsEnabled();
        smsNotificationsEnabled = customer.getSmsNotificationsEnabled();
        pushNotificationsEnabled = customer.getPushNotificationsEnabled();
        kycFailureCount = customer.getKycFailureCount();

        CustomerResponse customerResponse = new CustomerResponse( cifNo, userId, relationshipManagerEmpId, firstName, lastName, dob, gender, mobile, email, panNo, status, kycStatus, riskClassification, preferredCommunicationChannel, emailNotificationsEnabled, smsNotificationsEnabled, pushNotificationsEnabled, kycFailureCount );

        return customerResponse;
    }
}
