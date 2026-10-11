package com.smartcampus.service.impl;

import com.smartcampus.context.BaseContext;
import com.smartcampus.dto.OrdersConfirmDTO;
import com.smartcampus.dto.OrdersSubmitDTO;
import com.smartcampus.entity.AddressBook;
import com.smartcampus.entity.OrderDetail;
import com.smartcampus.entity.Orders;
import com.smartcampus.entity.ShoppingCart;
import com.smartcampus.exception.OrderBusinessException;
import com.smartcampus.mapper.AddressBookMapper;
import com.smartcampus.mapper.DishMapper;
import com.smartcampus.mapper.OrderDetailMapper;
import com.smartcampus.mapper.OrderMapper;
import com.smartcampus.mapper.SetmealMapper;
import com.smartcampus.mapper.ShoppingCartMapper;
import com.smartcampus.mapper.UserMapper;
import com.smartcampus.support.FakeMapperFactory;
import com.smartcampus.utils.WeChatPayUtil;
import com.smartcampus.vo.OrderSubmitVO;
import com.smartcampus.websocket.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单业务单元测试。
 * <p>
 * 重点覆盖两个并发相关的高风险点：
 * 1）库存不足时必须拒绝下单，且不产生订单（事务回滚语义）；
 * 2）订单状态 CAS 更新失败时必须抛出业务异常，避免状态被并发覆盖。
 */
class OrderServiceImplTest {

    private OrderServiceImpl orderService;

    private final Map<String, Function<Object[], Object>> orderHandlers = FakeMapperFactory.handlers();
    private final Map<String, Function<Object[], Object>> orderDetailHandlers = FakeMapperFactory.handlers();
    private final Map<String, Function<Object[], Object>> addressBookHandlers = FakeMapperFactory.handlers();
    private final Map<String, Function<Object[], Object>> shoppingCartHandlers = FakeMapperFactory.handlers();
    private final Map<String, Function<Object[], Object>> userHandlers = FakeMapperFactory.handlers();
    private final Map<String, Function<Object[], Object>> dishHandlers = FakeMapperFactory.handlers();
    private final Map<String, Function<Object[], Object>> setmealHandlers = FakeMapperFactory.handlers();

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl();

        ReflectionTestUtils.setField(orderService, "orderMapper",
                FakeMapperFactory.create(OrderMapper.class, orderHandlers));
        ReflectionTestUtils.setField(orderService, "orderDetailMapper",
                FakeMapperFactory.create(OrderDetailMapper.class, orderDetailHandlers));
        ReflectionTestUtils.setField(orderService, "addressBookMapper",
                FakeMapperFactory.create(AddressBookMapper.class, addressBookHandlers));
        ReflectionTestUtils.setField(orderService, "shoppingCartMapper",
                FakeMapperFactory.create(ShoppingCartMapper.class, shoppingCartHandlers));
        ReflectionTestUtils.setField(orderService, "userMapper",
                FakeMapperFactory.create(UserMapper.class, userHandlers));
        ReflectionTestUtils.setField(orderService, "dishMapper",
                FakeMapperFactory.create(DishMapper.class, dishHandlers));
        ReflectionTestUtils.setField(orderService, "setmealMapper",
                FakeMapperFactory.create(SetmealMapper.class, setmealHandlers));
        ReflectionTestUtils.setField(orderService, "weChatPayUtil", noOpWeChatPayUtil());
        ReflectionTestUtils.setField(orderService, "webSocketServer", new WebSocketServer());

        // 模拟已登录用户
        BaseContext.setCurrentId(1L);

        // 默认场景：地址存在，购物车中有一件「王老吉 x2」
        addressBookHandlers.put("getById", args -> AddressBook.builder()
                .id(2L).userId(1L).consignee("张三").phone("13800000000")
                .provinceName("北京市").cityName("北京市").districtName("东城区").detail("测试路1号")
                .build());
        shoppingCartHandlers.put("list", args -> Collections.singletonList(
                ShoppingCart.builder().id(1L).userId(1L).dishId(46L).name("王老吉")
                        .number(2).amount(new BigDecimal("6.00")).build()));
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    @DisplayName("库存不足时拒绝下单，并且不产生任何订单（事务回滚语义）")
    void shouldRejectOrderWhenDishStockNotEnough() {
        AtomicInteger insertCount = new AtomicInteger();
        orderHandlers.put("insert", args -> {
            insertCount.incrementAndGet();
            return null;
        });
        // 原子条件更新返回 0，表示库存不足
        dishHandlers.put("deductStock", args -> 0);

        OrderBusinessException ex = assertThrows(OrderBusinessException.class,
                () -> orderService.submitOrder(buildSubmitDTO()));

        assertTrue(ex.getMessage().contains("库存不足"), "异常信息应包含「库存不足」，实际为：" + ex.getMessage());
        assertEquals(0, insertCount.get(), "库存不足时不应写入订单");
    }

    @Test
    @DisplayName("库存充足时下单成功：扣减库存、写入明细、清空购物车")
    void shouldSubmitOrderSuccessfullyAndDeductStock() {
        AtomicReference<Long> deductedDishId = new AtomicReference<>();
        AtomicInteger deductedNumber = new AtomicInteger();
        dishHandlers.put("deductStock", args -> {
            deductedDishId.set((Long) args[0]);
            deductedNumber.set((Integer) args[1]);
            return 1;
        });

        AtomicInteger detailInsertCount = new AtomicInteger();
        orderDetailHandlers.put("insertBatch", args -> {
            detailInsertCount.incrementAndGet();
            return null;
        });

        AtomicInteger cartClearCount = new AtomicInteger();
        shoppingCartHandlers.put("deleteByUserId", args -> {
            cartClearCount.incrementAndGet();
            return null;
        });

        // 模拟 useGeneratedKeys 回填主键
        orderHandlers.put("insert", args -> {
            ((Orders) args[0]).setId(99L);
            return null;
        });

        OrderSubmitVO vo = orderService.submitOrder(buildSubmitDTO());

        assertNotNull(vo.getOrderNumber());
        assertEquals(99L, vo.getId());
        assertEquals(46L, deductedDishId.get(), "应按菜品维度扣减库存");
        assertEquals(2, deductedNumber.get(), "扣减数量应等于购物车数量");
        assertEquals(1, detailInsertCount.get(), "应写入订单明细");
        assertEquals(1, cartClearCount.get(), "下单成功后应清空购物车");
    }

    @Test
    @DisplayName("购物车中包含套餐时，应按套餐维度扣减库存")
    void shouldDeductSetmealStockWhenCartContainsSetmeal() {
        shoppingCartHandlers.put("list", args -> Collections.singletonList(
                ShoppingCart.builder().id(2L).userId(1L).setmealId(30L).name("人气套餐")
                        .number(1).amount(new BigDecimal("48.00")).build()));

        AtomicReference<Long> deductedSetmealId = new AtomicReference<>();
        setmealHandlers.put("deductStock", args -> {
            deductedSetmealId.set((Long) args[0]);
            return 1;
        });
        orderHandlers.put("insert", args -> {
            ((Orders) args[0]).setId(100L);
            return null;
        });

        orderService.submitOrder(buildSubmitDTO());

        assertEquals(30L, deductedSetmealId.get(), "套餐商品应走套餐库存扣减分支");
    }

    @Test
    @DisplayName("接单时 CAS 影响行数为 0，应抛出订单状态错误")
    void shouldThrowWhenConfirmCasFails() {
        // 影响行数为 0，表示订单状态已被其他请求改变
        orderHandlers.put("update", args -> 0);

        OrdersConfirmDTO dto = new OrdersConfirmDTO();
        dto.setId(1L);

        OrderBusinessException ex = assertThrows(OrderBusinessException.class,
                () -> orderService.confirm(dto));

        assertEquals("订单状态错误", ex.getMessage());
    }

    @Test
    @DisplayName("用户取消已支付订单：CAS 成功后发起退款并回滚库存")
    void shouldRefundAndRestoreStockWhenUserCancelsPaidOrder() throws Exception {
        orderHandlers.put("getById", args -> Orders.builder()
                .id(7L).number("N7").status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID).amount(new BigDecimal("12.00"))
                .build());
        orderHandlers.put("update", args -> 1);

        AtomicInteger refundCount = new AtomicInteger();
        ReflectionTestUtils.setField(orderService, "weChatPayUtil", new WeChatPayUtil() {
            @Override
            public String refund(String outTradeNo, String outRefundNo,
                                 BigDecimal refund, BigDecimal total) {
                refundCount.incrementAndGet();
                return "ok";
            }
        });

        AtomicReference<Long> restoredDishId = new AtomicReference<>();
        AtomicInteger restoredNumber = new AtomicInteger();
        dishHandlers.put("restoreStock", args -> {
            restoredDishId.set((Long) args[0]);
            restoredNumber.set((Integer) args[1]);
            return 1;
        });
        orderDetailHandlers.put("getByOrderId", args -> Collections.singletonList(
                OrderDetail.builder().id(1L).orderId(7L).dishId(46L).number(2).build()));

        orderService.userCancelById(7L);

        assertEquals(1, refundCount.get(), "已支付订单取消应发起退款");
        assertEquals(46L, restoredDishId.get(), "应回滚对应菜品的库存");
        assertEquals(2, restoredNumber.get(), "回滚数量应等于下单数量");
    }

    @Test
    @DisplayName("用户取消未支付订单：不退款，但仍需回滚库存")
    void shouldRestoreStockWithoutRefundWhenUserCancelsUnpaidOrder() throws Exception {
        orderHandlers.put("getById", args -> Orders.builder()
                .id(8L).number("N8").status(Orders.PENDING_PAYMENT)
                .payStatus(Orders.UN_PAID).amount(new BigDecimal("12.00"))
                .build());
        orderHandlers.put("update", args -> 1);

        AtomicInteger refundCount = new AtomicInteger();
        ReflectionTestUtils.setField(orderService, "weChatPayUtil", new WeChatPayUtil() {
            @Override
            public String refund(String outTradeNo, String outRefundNo,
                                 BigDecimal refund, BigDecimal total) {
                refundCount.incrementAndGet();
                return "ok";
            }
        });

        AtomicInteger restoreCount = new AtomicInteger();
        dishHandlers.put("restoreStock", args -> {
            restoreCount.incrementAndGet();
            return 1;
        });
        orderDetailHandlers.put("getByOrderId", args -> Collections.singletonList(
                OrderDetail.builder().id(1L).orderId(8L).dishId(46L).number(1).build()));

        orderService.userCancelById(8L);

        assertEquals(0, refundCount.get(), "未支付订单取消不应发起退款");
        assertEquals(1, restoreCount.get(), "未支付订单取消仍应回滚库存");
    }

    @Test
    @DisplayName("取消订单时若 CAS 失败（状态已被并发修改），应抛出订单状态错误")
    void shouldThrowWhenCancelCasFails() {
        orderHandlers.put("getById", args -> Orders.builder()
                .id(9L).number("N9").status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID).amount(new BigDecimal("12.00"))
                .build());
        // CAS 失败
        orderHandlers.put("update", args -> 0);

        List<OrderDetail> details = Collections.singletonList(
                OrderDetail.builder().id(1L).orderId(9L).dishId(46L).number(1).build());
        orderDetailHandlers.put("getByOrderId", args -> details);

        AtomicInteger restoreCount = new AtomicInteger();
        dishHandlers.put("restoreStock", args -> {
            restoreCount.incrementAndGet();
            return 1;
        });

        OrderBusinessException ex = assertThrows(OrderBusinessException.class,
                () -> orderService.userCancelById(9L));

        assertEquals("订单状态错误", ex.getMessage());
        assertEquals(0, restoreCount.get(), "CAS 失败时不应回滚库存");
    }

    private OrdersSubmitDTO buildSubmitDTO() {
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setAddressBookId(2L);
        dto.setPayMethod(1);
        dto.setAmount(new BigDecimal("12.00"));
        dto.setPackAmount(1);
        dto.setTablewareNumber(1);
        dto.setTablewareStatus(1);
        dto.setDeliveryStatus(1);
        return dto;
    }

    /**
     * 构造一个不会真正调用微信支付接口的替身
     */
    private static WeChatPayUtil noOpWeChatPayUtil() {
        return new WeChatPayUtil() {
            @Override
            public String refund(String outTradeNo, String outRefundNo,
                                 BigDecimal refund, BigDecimal total) {
                return "ok";
            }
        };
    }
}
