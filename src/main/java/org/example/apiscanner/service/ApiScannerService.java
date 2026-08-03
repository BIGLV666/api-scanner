package org.example.apiscanner.service;

import org.example.apiscanner.model.ApiInfo;
import org.example.apiscanner.model.ApiScanSnapshot;
import org.example.apiscanner.model.FieldInfo;
import org.example.apiscanner.model.ParamInfo;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 从 Spring MVC 已注册的 RequestMapping 中提取接口元数据。
 *
 * <p>扫描对象不是 classpath 下的全部类，而是
 * {@link RequestMappingHandlerMapping} 最终确认过的 HandlerMethod。这样可以保证：
 * 被条件配置排除的 Controller 不会出现在结果中，继承、组合注解以及 Spring
 * 实际合并后的路径和 HTTP Method 也都与运行时路由保持一致。</p>
 *
 * <p>扫描结果使用一次性快照缓存。刷新时先在局部变量中完成全部解析，最后再替换
 * volatile 引用，因此 Go 查询接口不会读到刷新了一半的数据，也不需要在每次请求时
 * 重新做反射扫描。</p>
 */
public class ApiScannerService implements SmartInitializingSingleton {

    /**
     * 扫描器自身 Controller 的完整类名。
     *
     * <p>它也是一个 {@code @RestController}，如果不显式排除，就会把查询扫描结果的接口
     * 再写进扫描结果，形成没有业务意义的自描述数据。</p>
     */
    private static final String SCANNER_CONTROLLER =
            "org.example.apiscanner.controller.ApiScannerController";

    /** Spring MVC 在应用启动时整理完成的“路由 -> Java 方法”注册表。 */
    private final RequestMappingHandlerMapping handlerMapping;

    /** 当前被扫描微服务的名称，会原样写入快照。 */
    private final String applicationName;

    /**
     * 当前可供查询的完整快照。
     *
     * <p>{@code volatile} 保证刷新线程替换引用后，其他请求线程立刻看到新对象。
     * 快照内部的 Map 和 List 都不可修改，因此读取线程不需要再加锁。</p>
     */
    private volatile ApiScanSnapshot snapshot;

    /**
     * 创建扫描服务，并先放入一个合法的空快照。
     *
     * <p>正式扫描会在全部单例创建完成后执行。提前初始化空快照可以保证即使某些特殊
     * 生命周期场景在首次扫描前发生读取，调用方得到的也是结构完整对象而不是 null。</p>
     *
     * @param handlerMapping Spring MVC 主路由注册表
     * @param applicationName 当前应用名称
     */
    public ApiScannerService(
            RequestMappingHandlerMapping handlerMapping,
            String applicationName
    ) {
        this.handlerMapping = handlerMapping;
        this.applicationName = applicationName;
        this.snapshot = new ApiScanSnapshot(
                applicationName,
                Instant.EPOCH,
                Collections.emptyMap()
        );
    }

    /**
     * 等所有单例 Bean 创建完成后执行首次扫描。
     *
     * <p>此时 MVC HandlerMapping 已经收集完业务 Controller。相比在构造器或
     * {@code @PostConstruct} 中扫描，这个时机不会遗漏后创建的 Controller，也不需要
     * 给生命周期方法错误地声明事件参数。</p>
     */
    @Override
    public void afterSingletonsInstantiated() {
        refresh();
    }

    /**
     * 重新生成完整接口快照。
     *
     * <p>该方法加 synchronized 只是为了防止多个手动刷新请求同时执行昂贵的反射工作；
     * 普通读取不加锁，只读取已经完成的 immutable snapshot。</p>
     */
    public synchronized ApiScanSnapshot refresh() {
        // 先在方法局部构建，避免把半成品暴露给同时读取 snapshot 的请求线程。
        Map<String, List<ApiInfo>> discovered = new LinkedHashMap<>();

        // 固定排序不是路由运行所必需，但能让 JSON、测试结果和前端展示保持稳定。
        handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> isBusinessController(entry.getValue()))
                .sorted(mappingComparator())
                .forEach(entry -> {
                    HandlerMethod handlerMethod = entry.getValue();
                    String controllerClass = handlerMethod.getBeanType().getName();
                    discovered.computeIfAbsent(controllerClass, ignored -> new ArrayList<>())
                            .add(parseApi(entry.getKey(), handlerMethod.getMethod()));
                });

        Map<String, List<ApiInfo>> immutableControllers = new LinkedHashMap<>();
        discovered.forEach((controller, apis) ->
                immutableControllers.put(controller, List.copyOf(apis))
        );

        ApiScanSnapshot newSnapshot = new ApiScanSnapshot(
                applicationName,
                Instant.now(),
                Collections.unmodifiableMap(immutableControllers)
        );
        snapshot = newSnapshot;
        return newSnapshot;
    }

    /**
     * 返回当前已经生成的完整快照，不触发反射扫描。
     *
     * @return 当前不可变快照
     */
    public ApiScanSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * 返回快照中所有 Controller 的完整类名。
     *
     * @return 当前 Controller 名称的只读副本
     */
    public List<String> getAllControllerNames() {
        return List.copyOf(snapshot.controllers().keySet());
    }

    /**
     * 支持按完整类名查询，也支持在不重名时按简单类名查询。
     */
    public List<ApiInfo> getControllerApis(String controllerName) {
        List<ApiInfo> exactMatch = snapshot.controllers().get(controllerName);
        if (exactMatch != null) {
            return exactMatch;
        }

        List<List<ApiInfo>> simpleNameMatches = snapshot.controllers().entrySet().stream()
                .filter(entry -> simpleClassName(entry.getKey()).equals(controllerName))
                .map(Map.Entry::getValue)
                .limit(2)
                .toList();

        // 简称发生冲突时要求调用方改用完整类名，不能静默返回其中任意一个。
        return simpleNameMatches.size() == 1
                ? simpleNameMatches.get(0)
                : Collections.emptyList();
    }

    /**
     * 定义扫描结果的稳定顺序：先按 Controller 完整类名，再按路径，最后按 Java 方法名。
     *
     * <p>{@code RequestMappingHandlerMapping} 底层 Map 的遍历顺序不属于公开约定。如果直接
     * 使用该顺序，同一份代码不同次启动可能返回不同 JSON，给前端 diff 和自动化测试
     * 带来噪声。</p>
     *
     * @return 用于路由条目排序的比较器
     */
    private Comparator<Map.Entry<RequestMappingInfo, HandlerMethod>> mappingComparator() {
        return Comparator
                .comparing((Map.Entry<RequestMappingInfo, HandlerMethod> entry) ->
                        entry.getValue().getBeanType().getName())
                .thenComparing(entry -> String.join(",", extractPaths(entry.getKey())))
                .thenComparing(entry -> entry.getValue().getMethod().getName());
    }

    /**
     * 只导出业务 RestController。
     *
     * <p>BasicErrorController 等框架端点不属于业务 API；扫描器自己的查询端点也不应该
     * 出现在结果中，否则每个服务都会多出一组与业务无关的元数据。</p>
     */
    private boolean isBusinessController(HandlerMethod handlerMethod) {
        Class<?> beanType = handlerMethod.getBeanType();
        return AnnotatedElementUtils.hasAnnotation(beanType, RestController.class)
                && !beanType.getName().equals(SCANNER_CONTROLLER)
                && !beanType.getPackageName().startsWith("org.springframework");
    }

    /**
     * 将 Spring 的单个路由描述和对应 Java 方法转换为稳定的传输模型。
     *
     * @param mappingInfo Spring 合并完成的路径、请求方法和媒体类型条件
     * @param method 实际执行的 Controller 方法
     * @return Go 可直接反序列化的接口描述
     */
    private ApiInfo parseApi(RequestMappingInfo mappingInfo, Method method) {
        List<String> methods = mappingInfo.getMethodsCondition().getMethods().stream()
                .map(Enum::name)
                .sorted()
                .toList();

        List<String> consumes = mappingInfo.getConsumesCondition()
                .getConsumableMediaTypes().stream()
                .map(Object::toString)
                .sorted()
                .toList();

        List<String> produces = mappingInfo.getProducesCondition()
                .getProducibleMediaTypes().stream()
                .map(Object::toString)
                .sorted()
                .toList();

        return new ApiInfo(
                methods,
                method.getName(),
                extractPaths(mappingInfo),
                consumes,
                produces,
                parseRequestParameters(method),
                parseReturnType(method)
        );
    }

    /**
     * Spring Boot 3 默认使用 PathPattern。保留 PatternsCondition 分支是为了兼容
     * 显式切换回旧 AntPathMatcher 的应用。
     */
    @SuppressWarnings("deprecation")
    private List<String> extractPaths(RequestMappingInfo mappingInfo) {
        Set<String> paths;
        if (mappingInfo.getPathPatternsCondition() != null) {
            paths = mappingInfo.getPathPatternsCondition().getPatternValues();
        } else if (mappingInfo.getPatternsCondition() != null) {
            paths = mappingInfo.getPatternsCondition().getPatterns();
        } else {
            paths = Collections.emptySet();
        }
        return paths.stream().sorted().toList();
    }

    /**
     * 解析调用方真正需要提供的 Controller 参数。
     *
     * <p>使用 {@link Parameter#getParameterizedType()} 而不是 {@code getType()}，否则
     * {@code List<UserDTO>} 只能看到 List，内部的 UserDTO 字段会丢失。</p>
     *
     * @param method Controller 方法
     * @return 排除框架注入参数后的只读参数列表
     */
    private List<ParamInfo> parseRequestParameters(Method method) {
        List<ParamInfo> parameters = new ArrayList<>();

        for (Parameter parameter : method.getParameters()) {
            if (isInfrastructureParameter(parameter.getType())) {
                continue;
            }

            Type parameterType = parameter.getParameterizedType();
            parameters.add(buildParamInfo(
                    parameter.getName(),
                    parameterType,
                    formatAnnotations(parameter.getAnnotations())
            ));
        }

        return List.copyOf(parameters);
    }

    /**
     * 解析方法的泛型返回类型。
     *
     * <p>返回值没有 Java 参数名和参数注解，因此这两项分别使用 {@code null} 和空列表，
     * 但仍复用同一套类型字段树构建逻辑。</p>
     *
     * @param method Controller 方法
     * @return 返回值描述
     */
    private ParamInfo parseReturnType(Method method) {
        return buildParamInfo(
                null,
                method.getGenericReturnType(),
                Collections.emptyList()
        );
    }

    /**
     * 组装统一的参数模型，并从任意 {@link Type} 开始递归构建字段树。
     *
     * @param parameterName 请求参数名；返回值传 {@code null}
     * @param type 保留泛型信息的 Java 类型
     * @param annotations 已格式化的参数注解
     * @return 完整参数或返回值描述
     */
    private ParamInfo buildParamInfo(
            String parameterName,
            Type type,
            List<String> annotations
    ) {
        Class<?> rawClass = getRawClass(type);
        String className = rawClass == null
                ? type.getTypeName()
                : rawClass.getSimpleName();
        String fullClassName = rawClass == null
                ? type.getTypeName()
                : rawClass.getName();

        return new ParamInfo(
                parameterName,
                className,
                fullClassName,
                annotations,
                buildTypeFields(type, new HashMap<>(), new HashSet<>())
        );
    }

    /**
     * 将任意 Java Type 递归展开为 Go 容易消费的字段树。
     *
     * <ul>
     *     <li>普通 Class：展开其非静态字段。</li>
     *     <li>数组：展开元素类型。</li>
     *     <li>List、Optional、ResponseEntity：跳过容器本身，展开内部类型。</li>
     *     <li>Map：展开 value 类型，即最后一个泛型参数。</li>
     *     <li>Result&lt;UserDTO&gt;：建立 T -> UserDTO 的绑定，再解析 Result 字段。</li>
     *     <li>递归对象：通过 visiting 集合截断 User.parent.user 这类无限循环。</li>
     * </ul>
     */
    private List<FieldInfo> buildTypeFields(
            Type originalType,
            Map<TypeVariable<?>, Type> typeBindings,
            Set<String> visiting
    ) {
        // 先把 Result<T> 字段中的 T 替换成调用处提供的具体类型，例如 UserDTO。
        Type type = resolveType(originalType, typeBindings);

        // 找不到实际绑定的裸类型变量没有可可靠展开的字段，直接停止当前分支。
        if (type instanceof TypeVariable<?>) {
            return Collections.emptyList();
        }

        // 对 ? extends UserDTO 采用上界 UserDTO；无上界时 Java 实际上等价于 Object。
        if (type instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            return upperBounds.length == 0
                    ? Collections.emptyList()
                    : buildTypeFields(upperBounds[0], typeBindings, visiting);
        }

        if (type instanceof GenericArrayType genericArrayType) {
            return buildTypeFields(
                    genericArrayType.getGenericComponentType(),
                    typeBindings,
                    visiting
            );
        }

        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                return buildTypeFields(clazz.getComponentType(), typeBindings, visiting);
            }
            if (!isCustomObject(clazz)) {
                return Collections.emptyList();
            }
            return buildClassFields(clazz, typeBindings, visiting);
        }

        if (!(type instanceof ParameterizedType parameterizedType)) {
            return Collections.emptyList();
        }

        Class<?> rawClass = getRawClass(parameterizedType);
        if (rawClass == null) {
            return Collections.emptyList();
        }

        Type[] actualArguments = parameterizedType.getActualTypeArguments();
        if (isGenericContainer(rawClass)) {
            if (actualArguments.length == 0) {
                return Collections.emptyList();
            }
            // Collection/Optional/ResponseEntity 只有内容类型；Map 的最后一项正好是 value。
            Type contentType = actualArguments[actualArguments.length - 1];
            return buildTypeFields(contentType, typeBindings, visiting);
        }

        // 对 ApiEnvelope<UserDTO> 建立 T -> UserDTO，供解析 ApiEnvelope.data 字段时替换 T。
        Map<TypeVariable<?>, Type> currentBindings = new HashMap<>(typeBindings);
        TypeVariable<?>[] variables = rawClass.getTypeParameters();
        for (int index = 0;
             index < variables.length && index < actualArguments.length;
             index++) {
            currentBindings.put(
                    variables[index],
                    resolveType(actualArguments[index], typeBindings)
            );
        }

        if (!isCustomObject(rawClass)) {
            return Collections.emptyList();
        }
        return buildClassFields(rawClass, currentBindings, visiting);
    }

    /**
     * 展开一个业务类自身及其父类中声明的实例字段。
     *
     * <p>{@code visiting} 记录当前递归路径，而不是全局“解析过的类”。因此
     * {@code Order.buyer} 与 {@code Order.seller} 都能完整展开 UserDTO；只有
     * {@code UserDTO.parent.parent...} 这种在同一路径重复出现的类型才会被截断。</p>
     *
     * @param clazz 要展开的业务类
     * @param typeBindings 当前泛型变量到实际类型的映射
     * @param visiting 当前递归路径中正在解析的类型标识
     * @return 该类型的只读字段列表
     */
    private List<FieldInfo> buildClassFields(
            Class<?> clazz,
            Map<TypeVariable<?>, Type> typeBindings,
            Set<String> visiting
    ) {
        // 同一个泛型类在不同实际类型下结构可能不同，绑定关系也必须参与递归标识。
        String visitKey = clazz.getName() + "|" + typeBindings;
        if (!visiting.add(visitKey)) {
            return Collections.emptyList();
        }

        try {
            List<FieldInfo> fields = new ArrayList<>();
            Class<?> current = clazz;

            // 同时读取父类字段，兼容 BaseDTO、分页结果基类等常见结构。
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    // 常量、类级缓存和编译器生成字段都不是 DTO 的请求/响应数据。
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }

                    Type resolvedType = resolveType(field.getGenericType(), typeBindings);
                    Class<?> fieldClass = getRawClass(resolvedType);
                    String fieldType = fieldClass == null
                            ? resolvedType.getTypeName()
                            : fieldClass.getSimpleName();
                    String fullFieldType = resolvedType.getTypeName();
                    List<FieldInfo> nestedFields = buildTypeFields(
                            resolvedType,
                            typeBindings,
                            visiting
                    );

                    fields.add(new FieldInfo(
                            field.getName(),
                            fieldType,
                            fullFieldType,
                            nestedFields
                    ));
                }
                current = current.getSuperclass();
            }

            return List.copyOf(fields);
        } finally {
            // 当前分支完成后移除，允许同一种 DTO 出现在另一个平级字段中。
            visiting.remove(visitKey);
        }
    }

    /**
     * 按当前泛型绑定关系解析类型变量。
     *
     * <p>绑定可能是链式的，例如 T 映射到 U、U 再映射到 UserDTO，所以这里循环解析。
     * {@code visited} 防止异常泛型声明形成 T -> U -> T 后无限循环。</p>
     *
     * @param type 原始类型或类型变量
     * @param typeBindings 当前可用的泛型绑定
     * @return 能解析到的最具体类型；没有绑定时返回原值
     */
    private Type resolveType(Type type, Map<TypeVariable<?>, Type> typeBindings) {
        Type resolved = type;
        Set<Type> visited = new HashSet<>();

        while (resolved instanceof TypeVariable<?> variable && visited.add(resolved)) {
            Type actualType = typeBindings.get(variable);
            if (actualType == null || actualType == resolved) {
                break;
            }
            resolved = actualType;
        }
        return resolved;
    }

    /**
     * 从不同的 {@link Type} 实现中提取 JVM 原始 Class。
     *
     * <p>Java 反射用 Class、ParameterizedType、GenericArrayType、WildcardType 等不同对象
     * 表示类型。后续判断容器和业务对象需要统一的 Class，因此在这里集中兼容这些分支。</p>
     *
     * @param type 要检查的反射类型
     * @return 对应原始 Class；无法确定时返回 {@code null}
     */
    private Class<?> getRawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof GenericArrayType genericArrayType) {
            Class<?> componentClass = getRawClass(genericArrayType.getGenericComponentType());
            return componentClass == null
                    ? null
                    : Array.newInstance(componentClass, 0).getClass();
        }
        if (type instanceof WildcardType wildcardType
                && wildcardType.getUpperBounds().length > 0) {
            return getRawClass(wildcardType.getUpperBounds()[0]);
        }
        return null;
    }

    /**
     * 判断类型是否只是包装实际业务数据的通用容器。
     *
     * <p>扫描结果关注容器内部 DTO 的结构，不展开 List、Map、Optional 或 ResponseEntity
     * 自身的实现字段。Map 只展开 value，因为 HTTP JSON 对象的 key 通常是字符串属性名。</p>
     *
     * @param clazz 原始类型
     * @return 是否应跳过容器自身并继续解析泛型内容
     */
    private boolean isGenericContainer(Class<?> clazz) {
        return Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz)
                || Optional.class.isAssignableFrom(clazz)
                || ResponseEntity.class.isAssignableFrom(clazz);
    }

    /**
     * 判断一个类是否属于需要反射展开的业务对象。
     *
     * <p>基础类型、枚举、接口和常见框架/JDK 类型要么没有业务字段，要么内部字段数量很大
     * 且与 HTTP 数据无关，因此直接排除。剩余通常是项目自己的 DTO、VO 或统一响应对象。</p>
     *
     * @param clazz 候选原始类型
     * @return {@code true} 表示应该读取它的实例字段
     */
    private boolean isCustomObject(Class<?> clazz) {
        if (clazz == null
                || clazz.isArray()
                || clazz.isInterface()
                || clazz.isEnum()
                || clazz.isPrimitive()) {
            return false;
        }

        String name = clazz.getName();
        return !name.startsWith("java.")
                && !name.startsWith("javax.")
                && !name.startsWith("jakarta.")
                && !name.startsWith("org.springframework.")
                && !name.startsWith("org.apache.")
                && !name.startsWith("org.hibernate.")
                && !name.startsWith("com.fasterxml.")
                && !name.startsWith("sun.")
                && !name.startsWith("jdk.");
    }

    /**
     * Servlet、校验结果和登录 Principal 是框架在调用 Controller 时注入的对象，
     * Go 构造 HTTP 请求时不需要提供，因此从请求参数列表中排除。
     */
    private boolean isInfrastructureParameter(Class<?> type) {
        String className = type.getName();
        return className.equals("jakarta.servlet.http.HttpServletRequest")
                || className.equals("jakarta.servlet.http.HttpServletResponse")
                || className.equals("jakarta.servlet.http.HttpSession")
                || className.equals("javax.servlet.http.HttpServletRequest")
                || className.equals("javax.servlet.http.HttpServletResponse")
                || className.equals("javax.servlet.http.HttpSession")
                || className.equals("org.springframework.validation.BindingResult")
                || className.equals("org.springframework.ui.Model")
                || className.equals("org.springframework.ui.ModelMap")
                || java.security.Principal.class.isAssignableFrom(type);
    }

    /**
     * 把参数上的反射注解数组转换为可传输的短字符串列表。
     *
     * <p>Go 只需要知道参数来自 Path、Query 还是 Body，不需要接收 Java 的 Annotation
     * 代理对象，因此在 Java 侧先完成稳定格式化。</p>
     *
     * @param annotations 参数上声明的注解
     * @return 按反射返回顺序排列的注解文本
     */
    private List<String> formatAnnotations(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .map(this::formatAnnotation)
                .toList();
    }

    /**
     * 只输出显式填写的注解属性。例如 RequestParam 的巨大默认占位值不会进入 JSON，
     * 而 {@code @PathVariable("id")} 仍会保留为可读字符串。
     */
    private String formatAnnotation(Annotation annotation) {
        String attributes = Arrays.stream(annotation.annotationType().getDeclaredMethods())
                .sorted(Comparator.comparing(Method::getName))
                .map(attribute -> formatAnnotationAttribute(annotation, attribute))
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");

        String name = annotation.annotationType().getSimpleName();
        return attributes.isEmpty()
                ? "@" + name
                : "@" + name + "(" + attributes + ")";
    }

    /**
     * 格式化注解的一个属性，并省略未被显式修改的默认值。
     *
     * @param annotation 当前注解实例
     * @param attribute 注解类型中表示属性的无参方法
     * @return 格式化后的属性；值等于默认值或反射读取失败时返回 {@code null}
     */
    private String formatAnnotationAttribute(Annotation annotation, Method attribute) {
        try {
            Object value = attribute.invoke(annotation);
            Object defaultValue = attribute.getDefaultValue();
            if (defaultValue != null && Objects.deepEquals(value, defaultValue)) {
                return null;
            }

            String formattedValue = formatAnnotationValue(value);
            return "value".equals(attribute.getName())
                    ? formattedValue
                    : attribute.getName() + "=" + formattedValue;
        } catch (ReflectiveOperationException ignored) {
            // 单个注解属性不可读不应导致整个服务启动失败，忽略它并保留其余元数据。
            return null;
        }
    }

    /**
     * 把注解属性可能出现的标量、Class、枚举、嵌套注解或数组统一转换为 Java 风格文本。
     *
     * @param value 反射读取到的注解属性值
     * @return 可直接放入 JSON 字符串字段的可读表示
     */
    private String formatAnnotationValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return "\"" + stringValue + "\"";
        }
        if (value instanceof Character characterValue) {
            return "'" + characterValue + "'";
        }
        if (value instanceof Class<?> classValue) {
            return classValue.getSimpleName() + ".class";
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.getDeclaringClass().getSimpleName() + "." + enumValue.name();
        }
        if (value instanceof Annotation annotation) {
            return formatAnnotation(annotation);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<String> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(formatAnnotationValue(Array.get(value, index)));
            }
            return "{" + String.join(", ", values) + "}";
        }
        return String.valueOf(value);
    }

    /**
     * 从完整类名中截取最后一段简单类名。
     *
     * @param className 完整类名或已经是简单类名的字符串
     * @return 不包含包路径的类名
     */
    private String simpleClassName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }
}
