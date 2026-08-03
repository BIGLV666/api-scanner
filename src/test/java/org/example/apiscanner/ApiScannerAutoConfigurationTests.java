package org.example.apiscanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.apiscanner.controller.ApiScannerController;
import org.example.apiscanner.model.ApiInfo;
import org.example.apiscanner.model.ApiScanSnapshot;
import org.example.apiscanner.model.FieldInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用一个最小 Spring Boot Web 应用验证 api-scanner 作为依赖被引入时的完整行为。
 *
 * <p>测试没有手动导入 {@code ApiScannerAutoConfiguration}，目的是确认 Spring Boot
 * 确实能通过 Jar 中的 {@code AutoConfiguration.imports} 自动发现它。</p>
 */
@SpringBootTest(
        classes = ApiScannerAutoConfigurationTests.TestApplication.class,
        properties = {
                "spring.application.name=test-service",
                "api-scanner.internal-token=test-token"
        }
)
@AutoConfigureMockMvc
class ApiScannerAutoConfigurationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 验证只要配置了内部 Token，缺少请求头的调用就无法读取服务接口结构。
     *
     * <p>这里不只是在测 Controller 返回码，也能防止以后新增入口时意外绕过鉴权。</p>
     */
    @Test
    void shouldRejectRequestWithoutInternalToken() throws Exception {
        mockMvc.perform(get("/internal/api-scanner/apis"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 验证自动配置、MVC 路由读取、参数名保留、注解过滤和泛型字段树的整条链路。
     *
     * <p>测试接口故意组合 PathVariable、RequestBody、ResponseEntity 和自定义泛型响应，
     * 因为这些正是只使用 {@code Class<?>} 时最容易丢失信息的场景。</p>
     */
    @Test
    void shouldExposeStableApiMetadataForGoClient() throws Exception {
        String responseBody = mockMvc.perform(
                        get("/internal/api-scanner/apis")
                                .header(
                                        ApiScannerController.INTERNAL_TOKEN_HEADER,
                                        "test-token"
                                )
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        ApiScanSnapshot snapshot = objectMapper.readValue(
                responseBody,
                ApiScanSnapshot.class
        );

        assertThat(snapshot.applicationName()).isEqualTo("test-service");
        assertThat(snapshot.controllers()).hasSize(1);

        List<ApiInfo> controllerApis = snapshot.controllers().values()
                .iterator()
                .next();
        assertThat(controllerApis).hasSize(1);

        ApiInfo api = controllerApis.get(0);
        assertThat(api.httpMethods()).containsExactly("POST");
        assertThat(api.paths()).containsExactly("/demo/{id}");
        assertThat(api.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(api.requestParams())
                .extracting(param -> param.parameterName())
                .containsExactly("id", "request");
        assertThat(api.requestParams().get(1).fields())
                .extracting(FieldInfo::fieldName)
                .containsExactly("label", "count");

        FieldInfo responseData = api.returnType().fields().get(0);
        assertThat(responseData.fieldName()).isEqualTo("data");
        assertThat(responseData.nestedFields())
                .extracting(FieldInfo::fieldName)
                .containsExactly("name", "active");
    }

    /**
     * 测试专用的最小应用入口。
     *
     * <p>{@code @EnableAutoConfiguration} 负责加载待测自动配置，{@code @Import} 只引入下方
     * DemoController，避免扫描测试包中的其他类型干扰快照数量断言。</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(DemoController.class)
    static class TestApplication {
    }

    /** 提供一条结构足够完整但不包含业务依赖的测试路由。 */
    @RestController
    static class DemoController {

        @PostMapping(
                value = "/demo/{id}",
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE
        )
        ResponseEntity<ApiEnvelope<DemoResponse>> create(
                @PathVariable Long id,
                @RequestBody DemoRequest request
        ) {
            return ResponseEntity.ok(
                    new ApiEnvelope<>(
                            new DemoResponse(request.label(), true)
                    )
            );
        }
    }

    /** 用于验证请求 DTO 的普通字段能够被展开。 */
    record DemoRequest(String label, int count) {
    }

    /** 用于验证泛型响应内部的业务 DTO 能够继续展开。 */
    record DemoResponse(String name, boolean active) {
    }

    /** 用于模拟 CodeWise 常见的统一响应包装，并验证泛型 T 能被替换为实际类型。 */
    record ApiEnvelope<T>(T data) {
    }
}
