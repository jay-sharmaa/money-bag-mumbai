package com.moneybags.statement;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI statementApi() {
        return new OpenAPI().info(new Info()
                .title("Moneybags Statement & Reporting Service")
                .version("v1")
                .description("Swagger test order: project an account, project transactions, then call statements and reports."))
                .addServersItem(new Server().url("http://localhost:8090").description("API Gateway"))
                .addServersItem(new Server().url("http://localhost:8086").description("Statement Reporting Service directly"));
    }

    @Bean
    OperationCustomizer swaggerIdentityHeaders() {
        return (operation, method) -> {
            if (method.getBeanType() == ProjectionController.class) return operation;
            header(operation,"X-User-Id",true,"Authenticated user ID","user-001");
            header(operation,"X-Customer-Id",false,"Customer CIF. Use for customer tests.","cif-001");
            header(operation,"X-Employee-Id",false,"Staff ID. Leave empty for customer tests.","employee-001");
            header(operation,"X-Branch-Id",false,"Staff branch. Leave empty for customer tests.","branch-001");
            header(operation,"X-Permissions",true,"Comma-separated permissions","STATEMENT_VIEW,REPORT_VIEW");
            header(operation,"X-Correlation-Id",false,"Request trace ID","swagger-test-001");
            return operation;
        };
    }

    private void header(Operation operation,String name,boolean required,String description,String example) {
        operation.addParametersItem(new Parameter().in("header").name(name).required(required)
                .description(description).schema(new StringSchema().example(example)));
    }
}
