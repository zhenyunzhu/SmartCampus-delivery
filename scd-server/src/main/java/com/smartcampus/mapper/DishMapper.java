package com.smartcampus.mapper;

import com.github.pagehelper.Page;
import com.smartcampus.annocation.AutoFill;
import com.smartcampus.dto.DishPageQueryDTO;
import com.smartcampus.entity.Dish;
import com.smartcampus.enumeration.OperationType;
import com.smartcampus.vo.DishVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface DishMapper {

    /**
     * 根据分类id查询菜品数量
     * @param categoryId
     * @return
     */
    @Select("select count(id) from dish where category_id = #{categoryId}")
    Integer countByCategoryId(Long categoryId);

    void insert(Dish dish);

    Page<DishVO> pageQuery(DishPageQueryDTO dishPageQueryDTO);


    @Select("select * from dish where id = #{id}")
    Dish getById(Long id);



    @Delete("delete from dish where id = #{id}")
    void deleteById(Long id);

    void deleteByIds(List<Long> ids);

    @AutoFill(value = OperationType.UPDATE)
    void updateById(Dish dish);

    /**
     * 动态条件查询菜品（用户端使用）
     *
     * @param dish
     * @return
     */
    List<Dish> list(Dish dish);

    /**
     * 根据条件统计菜品数量（用于菜品总览）
     *
     * @param map
     * @return
     */
    Integer countByMap(Map<String, Object> map);

    /**
     * 根据套餐id查询套餐内包含的菜品
     *
     * @param setmealId
     * @return
     */
    @Select("select * from dish where id in (select dish_id from setmeal_dish where setmeal_id = #{setmealId})")
    List<Dish> getBySetmealId(Long setmealId);

    /**
     * 乐观并发控制扣减库存。
     * <p>
     * 采用「原子条件更新」：把库存充足性判断下推到 SQL 的 where 条件中，
     * 由数据库在行锁内保证「判断 + 扣减」的原子性，从根本上杜绝超卖。
     *
     * @param id     菜品id
     * @param number 扣减数量
     * @return 影响行数；返回 0 表示库存不足或并发冲突，业务层据此回滚整单
     */
    @Update("update dish set stock = stock - #{number} where id = #{id} and stock >= #{number}")
    int deductStock(@Param("id") Long id, @Param("number") Integer number);

    /**
     * 回滚库存（订单取消 / 拒单时调用）
     *
     * @param id     菜品id
     * @param number 回滚数量
     * @return 影响行数
     */
    @Update("update dish set stock = stock + #{number} where id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("number") Integer number);
}
