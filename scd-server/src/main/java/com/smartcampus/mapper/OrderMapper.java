package com.smartcampus.mapper;

import com.github.pagehelper.Page;
import com.smartcampus.dto.GoodsSalesDTO;
import com.smartcampus.dto.OrdersPageQueryDTO;
import com.smartcampus.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    /**
     * 插入订单数据
     *
     * @param orders
     */
    void insert(Orders orders);

    /**
     * 根据订单号和用户id查询订单
     *
     * @param orderNumber
     * @param userId
     * @return
     */
    @Select("select * from orders where number = #{orderNumber} and user_id = #{userId}")
    Orders getByNumberAndUserId(@Param("orderNumber") String orderNumber, @Param("userId") Long userId);

    /**
     * 根据订单号查询订单
     *
     * @param orderNumber
     * @return
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(@Param("orderNumber") String orderNumber);

    /**
     * 修改订单信息。
     * <p>
     * 支持 CAS 乐观更新：当 {@code oldStatus} 不为 null 时，SQL 会追加
     * {@code and status = #{oldStatus}} 条件，调用方通过返回的影响行数判断本次更新是否生效，
     * 从而避免并发请求互相覆盖订单状态。
     *
     * @param orders
     * @return 影响行数；返回 0 表示状态已被其他请求改变
     */
    int update(Orders orders);

    /**
     * 分页条件查询并按下单时间排序
     *
     * @param ordersPageQueryDTO
     * @return
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 根据id查询订单
     *
     * @param id
     * @return
     */
    @Select("select * from orders where id = #{id}")
    Orders getById(Long id);

    /**
     * 根据状态和下单时间查询订单（用于定时任务）
     *
     * @param status
     * @param orderTime
     * @return
     */
    @Select("select * from orders where status = #{status} and order_time &lt; #{orderTime}")
    List<Orders> getByStatusAndOrderTimeLT(@Param("status") Integer status,
                                           @Param("orderTime") LocalDateTime orderTime);

    /**
     * 根据动态条件统计营业额
     *
     * @param map
     * @return
     */
    Double sumByMap(Map<String, Object> map);

    /**
     * 根据动态条件统计订单数量
     *
     * @param map
     * @return
     */
    Integer countByMap(Map<String, Object> map);

    /**
     * 查询销量排名 Top10
     *
     * @param map
     * @return
     */
    List<GoodsSalesDTO> getSalesTop10(Map<String, Object> map);
}
