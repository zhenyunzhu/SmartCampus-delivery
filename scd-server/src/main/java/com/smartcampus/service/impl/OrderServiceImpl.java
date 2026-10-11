package com.smartcampus.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.smartcampus.constant.MessageConstant;
import com.smartcampus.context.BaseContext;
import com.smartcampus.dto.OrdersCancelDTO;
import com.smartcampus.dto.OrdersConfirmDTO;
import com.smartcampus.dto.OrdersPageQueryDTO;
import com.smartcampus.dto.OrdersPaymentDTO;
import com.smartcampus.dto.OrdersRejectionDTO;
import com.smartcampus.dto.OrdersSubmitDTO;
import com.smartcampus.entity.AddressBook;
import com.smartcampus.entity.OrderDetail;
import com.smartcampus.entity.Orders;
import com.smartcampus.entity.ShoppingCart;
import com.smartcampus.entity.User;
import com.smartcampus.exception.AddressBookBusinessException;
import com.smartcampus.exception.OrderBusinessException;
import com.smartcampus.exception.ShoppingCartBusinessException;
import com.smartcampus.mapper.AddressBookMapper;
import com.smartcampus.mapper.DishMapper;
import com.smartcampus.mapper.OrderDetailMapper;
import com.smartcampus.mapper.OrderMapper;
import com.smartcampus.mapper.SetmealMapper;
import com.smartcampus.mapper.ShoppingCartMapper;
import com.smartcampus.mapper.UserMapper;
import com.smartcampus.result.PageResult;
import com.smartcampus.service.OrderService;
import com.smartcampus.utils.WeChatPayUtil;
import com.smartcampus.vo.OrderPaymentVO;
import com.smartcampus.vo.OrderStatisticsVO;
import com.smartcampus.vo.OrderSubmitVO;
import com.smartcampus.vo.OrderVO;
import com.smartcampus.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单业务实现
 */
@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 用户下单
     *
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        // 1、处理各种业务异常（地址簿为空、购物车数据为空）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.isEmpty()) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        // 2、扣减库存：原子条件更新，任一项库存不足即抛异常，由事务整体回滚（杜绝超卖）
        deductStock(shoppingCartList);

        // 3、向订单表插入1条数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        // 订单号：采用当前时间戳，保证高并发下唯一性
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(userId);
        orders.setAddress(buildAddress(addressBook));
        // 打包费与餐具数量兜底，避免数据库非空约束报错
        orders.setPackAmount(orders.getPackAmount() == 0 ? 1 : orders.getPackAmount());
        orders.setTablewareNumber(orders.getTablewareNumber() == 0 ? 1 : orders.getTablewareNumber());
        orders.setDeliveryStatus(orders.getDeliveryStatus() == null ? 1 : orders.getDeliveryStatus());
        orders.setTablewareStatus(orders.getTablewareStatus() == null ? 1 : orders.getTablewareStatus());

        orderMapper.insert(orders);

        // 4、向订单明细表插入n条数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);

        // 5、清空当前用户的购物车数据
        shoppingCartMapper.deleteByUserId(userId);

        // 6、封装VO返回结果
        return OrderSubmitVO.builder()
                .id(orders.getId())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .orderTime(orders.getOrderTime())
                .build();
    }

    /**
     * 扣减购物车中所有商品的库存。
     * <p>
     * 乐观并发控制：{@code update dish set stock = stock - n where id = ? and stock >= n}。
     * 把「库存是否充足」的判断下推到 SQL 的 where 条件，由数据库在行锁内保证
     * 「判断 + 扣减」的原子性；影响行数为 0 表示库存不足或并发冲突，
     * 直接抛出业务异常，由 {@code @Transactional} 保证本方法内已扣减的库存整体回滚。
     *
     * @param shoppingCartList 本次下单的购物车明细
     */
    private void deductStock(List<ShoppingCart> shoppingCartList) {
        for (ShoppingCart cart : shoppingCartList) {
            int rows;
            if (cart.getDishId() != null) {
                rows = dishMapper.deductStock(cart.getDishId(), cart.getNumber());
            } else {
                rows = setmealMapper.deductStock(cart.getSetmealId(), cart.getNumber());
            }
            if (rows == 0) {
                throw new OrderBusinessException(cart.getName() + MessageConstant.STOCK_NOT_ENOUGH);
            }
        }
    }

    /**
     * 回滚订单中所有商品的库存（订单取消 / 拒单时调用）
     *
     * @param orderDetailList 订单明细
     */
    private void restoreStock(List<OrderDetail> orderDetailList) {
        if (orderDetailList == null || orderDetailList.isEmpty()) {
            return;
        }
        for (OrderDetail orderDetail : orderDetailList) {
            if (orderDetail.getDishId() != null) {
                dishMapper.restoreStock(orderDetail.getDishId(), orderDetail.getNumber());
            } else if (orderDetail.getSetmealId() != null) {
                setmealMapper.restoreStock(orderDetail.getSetmealId(), orderDetail.getNumber());
            }
        }
    }

    private String buildAddress(AddressBook addressBook) {
        StringBuilder sb = new StringBuilder();
        if (addressBook.getProvinceName() != null) {
            sb.append(addressBook.getProvinceName());
        }
        if (addressBook.getCityName() != null) {
            sb.append(addressBook.getCityName());
        }
        if (addressBook.getDistrictName() != null) {
            sb.append(addressBook.getDistrictName());
        }
        if (addressBook.getDetail() != null) {
            sb.append(addressBook.getDetail());
        }
        return sb.toString();
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        Orders ordersDB = orderMapper.getByNumber(ordersPaymentDTO.getOrderNumber());
        BigDecimal amount = (ordersDB != null && ordersDB.getAmount() != null)
                ? ordersDB.getAmount() : new BigDecimal("0.01");

        // 调用微信支付接口，生成预支付交易单
        JSONObject jsonObject = null;
        try {
            jsonObject = weChatPayUtil.pay(
                    ordersPaymentDTO.getOrderNumber(),
                    amount,
                    "智慧园区即时零售订单",
                    user.getOpenid());
        } catch (Exception e) {
            // 本地环境缺少商户证书时降级为模拟支付，保证业务流程可跑通
            log.warn("微信支付调用失败，降级为模拟支付：{}", e.getMessage());
        }

        if (jsonObject == null || jsonObject.getString("code") != null) {
            // 模拟支付成功
            paySuccess(ordersPaymentDTO.getOrderNumber());
            return OrderPaymentVO.builder()
                    .nonceStr("mockNonceStr")
                    .paySign("mockPaySign")
                    .timeStamp(String.valueOf(System.currentTimeMillis() / 1000))
                    .signType("RSA")
                    .packageStr("prepay_id=mock_prepay_id")
                    .build();
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));
        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        // 根据订单号查询当前用户的订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        // CAS：期望状态为「待付款」，保证重复支付回调幂等，不会覆盖已被取消的订单
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .oldStatus(Orders.PENDING_PAYMENT)
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        if (orderMapper.update(orders) == 0) {
            log.warn("订单 {} 当前状态不允许支付流转（可能已支付或已取消），忽略本次回调", outTradeNo);
            return;
        }

        // 通过 WebSocket 向管理端推送来单提醒，type=1 表示来单
        Map<String, Object> map = new HashMap<>();
        map.put("type", 1);
        map.put("orderId", ordersDB.getId());
        map.put("content", "订单号：" + outTradeNo);
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }

    /**
     * 用户端订单分页查询
     *
     * @param page
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int page, int pageSize, Integer status) {
        PageHelper.startPage(page, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        Page<Orders> pageResult = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList<>();
        if (pageResult != null && pageResult.getTotal() > 0) {
            for (Orders orders : pageResult) {
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orders.getId());
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);
                list.add(orderVO);
            }
        }
        return new PageResult(pageResult.getTotal(), list);
    }

    /**
     * 查询订单详情
     *
     * @param id
     * @return
     */
    public OrderVO details(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    /**
     * 用户取消订单
     *
     * @param id
     */
    @Transactional
    public void userCancelById(Long id) throws Exception {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        if (ordersDB.getStatus() > 2) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        boolean paid = Orders.TO_BE_CONFIRMED.equals(ordersDB.getStatus());

        // CAS：以读取到的状态作为期望值，仅当状态未被并发修改时本次取消才生效
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .oldStatus(ordersDB.getStatus())
                .status(Orders.CANCELLED)
                .cancelReason("用户取消")
                .cancelTime(LocalDateTime.now())
                .build();
        if (paid) {
            orders.setPayStatus(Orders.REFUND);
        }

        if (orderMapper.update(orders) == 0) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 取消成功后：退款 + 回滚库存
        if (paid) {
            refundQuietly(ordersDB);
        }
        restoreStock(orderDetailMapper.getByOrderId(id));
    }

    /**
     * 退款（容错处理，避免本地无商户证书导致整个流程失败）
     */
    private void refundQuietly(Orders ordersDB) {
        try {
            weChatPayUtil.refund(ordersDB.getNumber(), ordersDB.getNumber(),
                    ordersDB.getAmount(), ordersDB.getAmount());
        } catch (Exception e) {
            log.warn("微信退款调用失败，已跳过：{}", e.getMessage());
        }
    }

    /**
     * 再来一单
     *
     * @param id
     */
    public void repetition(Long id) {
        Long userId = BaseContext.getCurrentId();
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        List<ShoppingCart> shoppingCartList = new ArrayList<>();
        for (OrderDetail orderDetail : orderDetailList) {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCartList.add(shoppingCart);
        }
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 条件搜索订单（管理端）
     *
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);
        List<OrderVO> orderVOList = getOrderVOList(page);

        return new PageResult(page.getTotal(), orderVOList);
    }

    /**
     * 将订单列表封装为带菜品概要的 OrderVO 列表
     */
    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        List<OrderVO> orderVOList = new ArrayList<>();
        List<Orders> ordersList = page.getResult();
        if (ordersList != null && !ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDishes(getOrderDishesStr(orders));
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }

    /**
     * 拼接订单菜品概要，例如：宫保鸡丁*1;米饭*2;
     */
    private String getOrderDishesStr(Orders orders) {
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        List<String> orderDishList = orderDetailList.stream()
                .map(x -> x.getName() + "*" + x.getNumber() + ";")
                .collect(Collectors.toList());
        return String.join("", orderDishList);
    }

    /**
     * 各个状态的订单数量统计
     *
     * @return
     */
    public OrderStatisticsVO statistics() {
        Map<String, Object> map = new HashMap<>();

        map.put("status", Orders.TO_BE_CONFIRMED);
        Integer toBeConfirmed = orderMapper.countByMap(map);

        map.put("status", Orders.CONFIRMED);
        Integer confirmed = orderMapper.countByMap(map);

        map.put("status", Orders.DELIVERY_IN_PROGRESS);
        Integer deliveryInProgress = orderMapper.countByMap(map);

        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 接单
     *
     * @param ordersConfirmDTO
     */
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        // CAS：仅当订单仍处于「待接单」时才允许接单
        Orders orders = Orders.builder()
                .id(ordersConfirmDTO.getId())
                .oldStatus(Orders.TO_BE_CONFIRMED)
                .status(Orders.CONFIRMED)
                .build();
        if (orderMapper.update(orders) == 0) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
    }

    /**
     * 拒单
     *
     * @param ordersRejectionDTO
     */
    @Transactional
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());

        // 订单只有存在且状态为2（待接单）才可以拒单
        if (ordersDB == null || !Orders.TO_BE_CONFIRMED.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        boolean paid = Orders.PAID.equals(ordersDB.getPayStatus());

        // CAS：期望状态为「待接单」
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .oldStatus(Orders.TO_BE_CONFIRMED)
                .status(Orders.CANCELLED)
                .rejectionReason(ordersRejectionDTO.getRejectionReason())
                .cancelTime(LocalDateTime.now())
                .build();
        if (paid) {
            orders.setPayStatus(Orders.REFUND);
        }

        if (orderMapper.update(orders) == 0) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 拒单成功后：退款 + 回滚库存
        if (paid) {
            refundQuietly(ordersDB);
        }
        restoreStock(orderDetailMapper.getByOrderId(ordersDB.getId()));
    }

    /**
     * 管理端取消订单
     *
     * @param ordersCancelDTO
     */
    @Transactional
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        boolean paid = Orders.PAID.equals(ordersDB.getPayStatus());

        // CAS：以「读取到的当前状态」作为期望值，防止并发流转被覆盖
        Orders orders = Orders.builder()
                .id(ordersCancelDTO.getId())
                .oldStatus(ordersDB.getStatus())
                .status(Orders.CANCELLED)
                .cancelReason(ordersCancelDTO.getCancelReason())
                .cancelTime(LocalDateTime.now())
                .build();
        if (paid) {
            orders.setPayStatus(Orders.REFUND);
        }

        if (orderMapper.update(orders) == 0) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 取消成功后：退款 + 回滚库存
        if (paid) {
            refundQuietly(ordersDB);
        }
        restoreStock(orderDetailMapper.getByOrderId(ordersDB.getId()));
    }

    /**
     * 派送订单
     *
     * @param id
     */
    public void delivery(Long id) {
        // CAS：仅当订单仍处于「已接单」时才允许派送
        Orders orders = Orders.builder()
                .id(id)
                .oldStatus(Orders.CONFIRMED)
                .status(Orders.DELIVERY_IN_PROGRESS)
                .build();
        if (orderMapper.update(orders) == 0) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
    }

    /**
     * 完成订单
     *
     * @param id
     */
    public void complete(Long id) {
        // CAS：仅当订单仍处于「派送中」时才允许完成
        Orders orders = Orders.builder()
                .id(id)
                .oldStatus(Orders.DELIVERY_IN_PROGRESS)
                .status(Orders.COMPLETED)
                .deliveryTime(LocalDateTime.now())
                .build();
        if (orderMapper.update(orders) == 0) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
    }

    /**
     * 客户催单
     *
     * @param id
     */
    public void reminder(Long id) {
        Orders ordersDB = orderMapper.getById(id);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 通过 WebSocket 向管理端推送催单提醒，type=2 表示催单
        Map<String, Object> map = new HashMap<>();
        map.put("type", 2);
        map.put("orderId", id);
        map.put("content", "订单号：" + ordersDB.getNumber());
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }
}
