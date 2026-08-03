package org.example.apiscanner.model;

import java.util.List;

/**
 * 描述 DTO 中的一个字段，以及该字段继续向下展开后的结构。
 *
 * <p>{@code nestedFields} 让结果形成树。例如 {@code UserDTO.address.city} 会表示为
 * UserDTO 的 address 字段中再包含 city 字段。基础类型、JDK 类型或被递归检测截断的类型
 * 没有可继续展开的业务字段，此时 {@code nestedFields} 为空列表。</p>
 *
 * @param fieldName Java 字段名，也是 Jackson 默认情况下使用的 JSON 属性名
 * @param fieldType 便于页面展示的简单类型名，例如 {@code List}、{@code UserDTO}
 * @param fullFieldType 保留泛型信息的完整类型名，例如
 *                      {@code java.util.List<com.example.UserDTO>}
 * @param nestedFields 字段内部的业务字段树；不可展开时为空列表而不是 {@code null}
 */
public record FieldInfo(
        String fieldName,
        String fieldType,
        String fullFieldType,
        List<FieldInfo> nestedFields
) {
}
