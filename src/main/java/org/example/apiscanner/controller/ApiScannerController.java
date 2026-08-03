package org.example.apiscanner.controller;

import org.example.apiscanner.autoconfigure.ApiScannerProperties;
import org.example.apiscanner.model.ApiInfo;
import org.example.apiscanner.model.ApiScanSnapshot;
import org.example.apiscanner.service.ApiScannerService;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 向 Go 工具提供接口扫描结果的内部只读 Controller。
 *
 * <p>该 Controller 只暴露查询接口，不允许远程触发扫描或修改业务路由。统一前缀从
 * {@code api-scanner.base-path} 读取，未配置时使用
 * {@code /internal/api-scanner}。每个入口都会先调用 {@link #authorize(String)}，
 * 确保启用共享 Token 后没有接口遗漏鉴权。</p>
 */
@RestController
@RequestMapping("${api-scanner.base-path:/internal/api-scanner}")
public class ApiScannerController {

    /** Go 调用内部接口时携带共享密钥的请求头名称。 */
    public static final String INTERNAL_TOKEN_HEADER = "X-CodeWise-Internal-Token";

    /** 保存并查询启动阶段生成的不可变接口快照。 */
    private final ApiScannerService scannerService;

    /** 提供内部 Token 等外部配置。 */
    private final ApiScannerProperties properties;

    /**
     * 通过构造器注入依赖，保证 Controller 创建后依赖始终完整且不可替换。
     *
     * @param scannerService 接口扫描服务
     * @param properties API Scanner 配置
     */
    public ApiScannerController(
            ApiScannerService scannerService,
            ApiScannerProperties properties
    ) {
        this.scannerService = scannerService;
        this.properties = properties;
    }

    /**
     * 一次返回当前服务的完整接口快照。
     *
     * <p>这是 Go 聚合服务通常使用的主入口，响应同时包含应用名、扫描时间、
     * Controller 全类名、路由信息以及请求和响应字段树。</p>
     *
     * @param token 调用方通过内部请求头提交的共享密钥
     * @return 当前完整快照；读取过程不会重新执行反射扫描
     * @throws ResponseStatusException 配置了 Token 但请求未携带或不匹配时返回 401
     */
    @GetMapping("/apis")
    public ApiScanSnapshot getApis(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token
    ) {
        authorize(token);
        return scannerService.getSnapshot();
    }

    /**
     * 返回当前快照中的全部 Controller 完整类名。
     *
     * <p>适合前端先展示 Controller 列表，再按需加载某个 Controller 的接口，
     * 从而避免列表页面每次都传输完整字段树。</p>
     *
     * @param token 内部共享密钥
     * @return 按扫描结果顺序排列的 Controller 完整类名
     */
    @GetMapping("/controllers")
    public List<String> getControllerNames(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token
    ) {
        authorize(token);
        return scannerService.getAllControllerNames();
    }

    /**
     * 查询指定 Controller 下的接口。
     *
     * <p>{@code controllerName} 可以是完整类名；简单类名只有在当前服务中唯一时才会匹配。
     * 如果两个包存在同名 Controller，调用方必须传完整类名，避免返回错误接口。</p>
     *
     * @param controllerName Controller 完整类名或唯一的简单类名
     * @param token 内部共享密钥
     * @return 对应接口列表；找不到或简单类名冲突时返回空列表
     */
    @GetMapping("/controllers/{controllerName:.+}")
    public List<ApiInfo> getControllerApis(
            @PathVariable String controllerName,
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token
    ) {
        authorize(token);
        return scannerService.getControllerApis(controllerName);
    }

    /**
     * 校验内部调用共享密钥。
     *
     * <p>配置中的期望 Token 为空时直接放行，用于本地开发。生产环境配置 Token 后，
     * 缺少请求头也会按空字节数组处理并统一返回 401，不向调用方区分“未传”和“传错”。
     * {@link MessageDigest#isEqual(byte[], byte[])} 用于比较字节内容，避免普通字符串比较
     * 在首个不同字符处立即结束。</p>
     *
     * @param actualToken 请求头中的实际 Token，未携带时为 {@code null}
     * @throws ResponseStatusException 实际 Token 与配置不一致时抛出 401
     */
    private void authorize(String actualToken) {
        String expectedToken = properties.getInternalToken();

        // 空配置代表显式选择本地免鉴权，而不是要求客户端传一个空字符串。
        if (!StringUtils.hasText(expectedToken)) {
            return;
        }

        // 请求头缺失时转换为空数组，使缺失与错误 Token 走同一失败分支。
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = actualToken == null
                ? new byte[0]
                : actualToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal token");
        }
    }
}
