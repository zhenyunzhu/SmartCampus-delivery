package com.smartcampus.mapper;

import com.github.pagehelper.Page;
import com.smartcampus.annocation.AutoFill;
import com.smartcampus.dto.SetmealPageQueryDTO;
import com.smartcampus.entity.Setmeal;
import com.smartcampus.enumeration.OperationType;
import com.smartcampus.vo.DishItemVO;
import com.smartcampus.vo.SetmealVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface SetmealMapper {

    /**
     * 根据分类id查询套餐的数量
     *
     * @param categoryId
     * @return
     */
    @Select("select count(id) from setmeal where category_id = #{categoryId}")
    Integer countByCategoryId(Long categoryId);

    /**
     * 新增套餐
     *
     * @param setmeal
     */
    @AutoFill(value = OperationType.INSERT)
    void insert(Setmeal setmeal);

    /**
     * 分页查询套餐（关联分类表返回分类名称）
     *
     * @param setmealPageQueryDTO
     * @return
     */
    Page<SetmealVO> pageQuery(SetmealPageQueryDTO setmealPageQueryDTO);

    /**
     * 根据id批量删除套餐
     *
     * @param ids
     */
    void deleteByIds(List<Long> ids);

    /**
     * 根据id查询套餐
     *
     * @param id
     * @return
     */
    @Select("select * from setmeal where id = #{id}")
    Setmeal getById(Long id);

    /**
     * 根据id动态修改套餐
     *
     * @param setmeal
     */
    @AutoFill(value = OperationType.UPDATE)
    void update(Setmeal setmeal);

    /**
     * 动态条件查询套餐（用户端使用，配合 @Cacheable 缓存）
     *
     * @param setmeal
     * @return
     */
    List<Setmeal> list(Setmeal setmeal);

    /**
     * 根据套餐id查询包含的菜品列表
     *
     * @param setmealId
     * @return
     */
    List<DishItemVO> getDishItemBySetmealId(Long setmealId);

    /**
     * 根据条件统计套餐数量（用于套餐总览）
     *
     * @param map
     * @return
     */
    Integer countByMap(Map<String, Object> map);

    /**
     * 乐观并发控制扣减库存（原子条件更新，杜绝超卖）
     *
     * @param id     套餐id
     * @param number 扣减数量
     * @return 影响行数；返回 0 表示库存不足或并发冲突
     */
    @Update("update setmeal set stock = stock - #{number} where id = #{id} and stock >= #{number}")
    int deductStock(@Param("id") Long id, @Param("number") Integer number);

    /**
     * 回滚库存（订单取消 / 拒单时调用）
     *
     * @param id     套餐id
     * @param number 回滚数量
     * @return 影响行数
     */
    @Update("update setmeal set stock = stock + #{number} where id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("number") Integer number);
}
