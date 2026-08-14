package com.moneybags.transaction.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI transactionServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("MoneyBags Transaction Service API")
                .description("Employee-operated APIs for deposits, withdrawals, transfers, transaction queries, approvals, reversals, and reconciliation. Use Authorize to provide the employee ID, branch code, and comma-separated permissions.")
                .version("v1"))
                .addServersItem(new Server().url("http://localhost:8090").description("API Gateway"))
                .addServersItem(new Server().url("http://localhost:8084").description("Transaction Service directly"))
                .components(new Components()
                        .addSecuritySchemes("employeeId", header("X-Employee-Id", "Authenticated bank employee ID"))
                        .addSecuritySchemes("branchCode", header("X-Branch-Code", "Employee branch code"))
                        .addSecuritySchemes("permissions", header("X-Permissions", "Comma-separated employee permissions")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("employeeId")
                        .addList("branchCode")
                        .addList("permissions"));
    }

    private SecurityScheme header(String name,String description) {
        return new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(name).description(description);
    }
}
