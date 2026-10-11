package com.smartcampus.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ThreadLocal 登录上下文单元测试
 */
class BaseContextTest {

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    @DisplayName("同一线程内可以存取当前登录人 id")
    void shouldStoreAndReadCurrentId() {
        BaseContext.setCurrentId(100L);

        assertEquals(100L, BaseContext.getCurrentId());
    }

    @Test
    @DisplayName("remove 之后当前线程读不到 id，避免线程复用导致的内存泄漏与数据串号")
    void shouldReturnNullAfterRemove() {
        BaseContext.setCurrentId(100L);

        BaseContext.removeCurrentId();

        assertNull(BaseContext.getCurrentId());
    }

    @Test
    @DisplayName("不同线程之间的 ThreadLocal 相互隔离")
    void shouldIsolateBetweenThreads() throws InterruptedException {
        BaseContext.setCurrentId(1L);

        AtomicReference<Long> valueSeenInOtherThread = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread other = new Thread(() -> {
            // 新线程不应读到主线程写入的值
            valueSeenInOtherThread.set(BaseContext.getCurrentId());
            // 子线程写入不应影响主线程
            BaseContext.setCurrentId(2L);
            latch.countDown();
        });
        other.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        other.join();

        assertNull(valueSeenInOtherThread.get(), "新线程不应读到主线程写入的 id");
        assertEquals(1L, BaseContext.getCurrentId(), "子线程写入不应影响主线程");
    }
}
