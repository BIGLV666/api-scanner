package org.example.apiscanner.model;

import java.util.List;

/**
 * 描述 Controller 的一个请求参数或方法返回值。
 *
 * <p>类型以字符串保存，而不是直接保存 {@code Class<?>}。这样 JSON 中不会出现 Java
 * 反射对象的内部结构，Go 也不需要识别 JVM 类型。请求参数会携带 Spring MVC 注解文本，
 * 返回值没有参数注解，因此其 {@code annotations} 通常为空。</p>
 *
 * @param parameterName Java 参数名；描述返回值时没有参数名，因此为 {@code null}
 * @param className 原始类型的简单名称，适合页面展示
 * @param fullClassName 原始类型的完整类名，适合 Go 侧做稳定匹配
 * @param annotations 参数上的注解摘要，例如 {@code @PathVariable("id")}
 * @param fields 参数或返回值内部递归展开的业务字段树
 */
public record ParamInfo(
        String parameterName,
        String className,
        String fullClassName,
        List<String> annotations,
        List<FieldInfo> fields
) {
}
