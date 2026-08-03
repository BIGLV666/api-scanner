package org.example.apiscanner.autoconfigure;

import org.example.apiscanner.controller.ApiScannerController;
import org.example.apiscanner.service.ApiScannerService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * API Scanner 的 Spring Boot 自动配置入口。
 *
 * <p>业务服务引入 Jar 后，Spring Boot 会通过
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 找到本类，因此业务项目不需要增加 {@code @ComponentScan} 或手动 {@code @Import}。</p>
 *
 * <p>类上的条件注解共同限制自动配置的生效范围：</p>
 * <ul>
 *     <li>{@code after = WebMvcAutoConfiguration.class}：先让 Spring MVC 建好路由基础设施；</li>
 *     <li>{@code @ConditionalOnWebApplication}：只在 Servlet Web 应用中注册；</li>
 *     <li>{@code @ConditionalOnClass}：类路径确实存在 Spring MVC 时才注册；</li>
 *     <li>{@code @ConditionalOnProperty}：允许业务服务通过配置整体关闭扫描器；</li>
 *     <li>{@code @EnableConfigurationProperties}：把 YAML 绑定为
 *     {@link ApiScannerProperties}。</li>
 * </ul>
 */
@AutoConfiguration(after = WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RequestMappingHandlerMapping.class)
@ConditionalOnProperty(
        prefix = "api-scanner",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(ApiScannerProperties.class)
public class ApiScannerAutoConfiguration {

    /**
     * 创建负责读取 Spring MVC 路由并生成快照的核心服务。
     *
     * <p>{@code RequestMappingHandlerMapping} 是 Spring MVC 已经完成路由合并后的注册表，
     * 从它扫描比直接遍历 classpath 更接近应用真实可访问的接口。这里显式使用限定名，
     * 是因为部分应用可能同时存在多个 HandlerMapping Bean。</p>
     *
     * <p>{@code @ConditionalOnMissingBean} 表示业务服务如果声明了自己的
     * {@link ApiScannerService}，自动配置会退让，不会出现重复 Bean。</p>
     *
     * @param handlerMapping Spring MVC 主请求映射注册表
     * @param environment 当前业务服务的配置环境
     * @return 完成依赖注入的扫描服务
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiScannerService apiScannerService(
            @Qualifier("requestMappingHandlerMapping")
            RequestMappingHandlerMapping handlerMapping,
            Environment environment
    ) {
        // applicationName 会进入快照，Go 聚合多个服务时可据此识别数据来源。
        String applicationName = environment.getProperty(
                "spring.application.name",
                "application"
        );
        return new ApiScannerService(handlerMapping, applicationName);
    }

    /**
     * 创建供 Go 服务读取扫描结果的内部 HTTP Controller。
     *
     * <p>Controller 本身不重复保存数据，只负责鉴权并委托给扫描服务。它同样允许业务方
     * 使用自定义 Bean 覆盖，例如需要接入统一内部鉴权时可以替换默认实现。</p>
     *
     * @param scannerService 核心扫描服务
     * @param properties 路径和内部 Token 配置
     * @return 内部查询 Controller
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiScannerController apiScannerController(
            ApiScannerService scannerService,
            ApiScannerProperties properties
    ) {
        return new ApiScannerController(scannerService, properties);
    }
}
