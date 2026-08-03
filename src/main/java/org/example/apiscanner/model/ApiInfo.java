package org.example.apiscanner.model;

import java.util.List;

/**
 * 描述一个 Spring MVC Controller 方法对应的 HTTP 接口。
 *
 * <p>这里使用只读 {@code record}，是因为扫描结果只负责从 Java 服务传给 Go，
 * 不需要在业务代码中继续修改。Jackson 会把 record 组件直接序列化成同名 JSON 字段，
 * Go 客户端因此可以使用固定结构反序列化，而不必理解 Spring 的
 * {@code RequestMappingInfo} 和 Java 反射对象。</p>
 *
 * @param httpMethods 该接口接受的 HTTP 方法，例如 {@code GET}、{@code POST}；
 *                    未限定请求方法时列表为空
 * @param methodName Controller 中的 Java 方法名，用于定位接口实现
 * @param paths Spring 合并类级和方法级映射后得到的完整路径；一个方法可能映射多个路径
 * @param consumes 接口允许接收的媒体类型，例如 {@code application/json}
 * @param produces 接口可能返回的媒体类型，例如 {@code application/json}
 * @param requestParams 调用接口时需要由客户端提供的参数；Servlet 等框架参数已被排除
 * @param returnType 方法的泛型返回类型及其字段树
 */
public record ApiInfo(
        List<String> httpMethods,
        String methodName,
        List<String> paths,
        List<String> consumes,
        List<String> produces,
        List<ParamInfo> requestParams,
        ParamInfo returnType
) {
}
