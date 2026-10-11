package com.smartcampus.support;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 轻量级测试替身工厂。
 * <p>
 * 基于 JDK 动态代理为 Mapper 接口生成 Fake 实现：只需为测试用到的方法注册行为，
 * 其余方法自动返回返回类型的默认值。
 * <p>
 * 相比 Mockito 的优势：零第三方依赖、不依赖字节码增强，因此在任意 JDK 版本下都能稳定运行。
 */
public final class FakeMapperFactory {

    private FakeMapperFactory() {
    }

    /**
     * 创建一个接口的 Fake 实现
     *
     * @param type     接口类型（如 OrderMapper.class）
     * @param handlers 方法名 -> 行为，参数为方法入参数组，返回值为方法返回值
     */
    public static <T> T create(Class<T> type, Map<String, Function<Object[], Object>> handlers) {
        Map<String, Function<Object[], Object>> safeHandlers =
                handlers == null ? new HashMap<>() : handlers;

        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    Function<Object[], Object> handler = safeHandlers.get(method.getName());
                    if (handler != null) {
                        return handler.apply(args);
                    }
                    return defaultValueOf(method.getReturnType());
                }));
    }

    /**
     * 便捷方法：创建一个空的 handler 容器
     */
    public static Map<String, Function<Object[], Object>> handlers() {
        return new HashMap<>();
    }

    /**
     * 返回基本类型的默认值，避免 Fake 方法返回 null 导致拆箱空指针
     */
    private static Object defaultValueOf(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        return null;
    }
}
