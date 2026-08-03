package org.example.apiscanner.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code api-scanner} 前缀下的外部配置。
 *
 * <p>该类由 Spring Boot 的配置属性机制创建，业务服务只需要在 YAML 中填写对应字段，
 * 不需要自己声明 Bean。保留普通 getter/setter 是为了让 Spring Boot 可以完成属性绑定。</p>
 */
@ConfigurationProperties("api-scanner")
public class ApiScannerProperties {

    /**
     * 是否启用整个扫描组件。
     *
     * <p>默认启用。设置为 {@code false} 后，扫描服务和内部 Controller 都不会注册，
     * 适合某个服务暂时不希望暴露接口元数据时使用。</p>
     */
    private boolean enabled = true;

    /**
     * 内部查询接口的统一路径前缀。
     *
     * <p>Controller 的 {@code @RequestMapping} 会直接读取这个值，因此修改后
     * {@code /apis}、{@code /controllers} 等接口会整体移动到新前缀下。</p>
     */
    private String basePath = "/internal/api-scanner";

    /**
     * Go 调用内部扫描接口时使用的共享密钥。
     *
     * <p>配置非空时，请求必须通过 {@code X-CodeWise-Internal-Token} 传入完全相同的值；
     * 配置为空时跳过校验，便于纯本地开发。部署环境不应留空，应由环境变量提供，
     * 避免把密钥直接提交到仓库。</p>
     */
    private String internalToken = "";

    /**
     * 返回组件是否启用。
     *
     * @return {@code true} 表示自动配置可以生效
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置组件是否启用，由 Spring Boot 配置绑定调用。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回内部接口路径前缀。
     *
     * @return 路径前缀
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * 设置内部接口路径前缀，由 Spring Boot 配置绑定调用。
     *
     * @param basePath 新的统一路径前缀
     */
    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    /**
     * 返回内部调用共享密钥。
     *
     * @return 共享密钥；空字符串表示不校验
     */
    public String getInternalToken() {
        return internalToken;
    }

    /**
     * 设置内部调用共享密钥，由 Spring Boot 配置绑定调用。
     *
     * @param internalToken 共享密钥
     */
    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }
}
