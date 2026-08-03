package org.example.apiscanner.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 某个 Spring Boot 服务在一次扫描完成后生成的完整接口快照。
 *
 * <p>扫描服务会先在局部变量中构建完整对象，再一次性替换当前快照。因此 Go 调用方
 * 读取到的要么是上一版完整数据，要么是新版完整数据，不会看到只扫描了一半的 Map。</p>
 *
 * @param applicationName 被扫描服务的 {@code spring.application.name}，Go 可用它区分微服务
 * @param scannedAt 本次快照生成完成的 UTC 时间，可用于判断配置是否已经刷新
 * @param controllers Controller 完整类名到接口列表的映射；使用完整类名可避免同名类冲突
 */
public record ApiScanSnapshot(
        String applicationName,
        Instant scannedAt,
        Map<String, List<ApiInfo>> controllers
) {
}
