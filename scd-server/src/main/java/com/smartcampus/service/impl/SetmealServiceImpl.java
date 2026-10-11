package com.smartcampus.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.smartcampus.constant.MessageConstant;
import com.smartcampus.constant.StatusConstant;
import com.smartcampus.dto.SetmealDTO;
import com.smartcampus.dto.SetmealPageQueryDTO;
import com.smartcampus.entity.Dish;
import com.smartcampus.entity.Setmeal;
import com.smartcampus.entity.SetmealDish;
import com.smartcampus.exception.DeletionNotAllowedException;
import com.smartcampus.exception.SetmealEnableFailedException;
import com.smartcampus.mapper.DishMapper;
import com.smartcampus.mapper.SetmealDishMapper;
import com.smartcampus.mapper.SetmealMapper;
import com.smartcampus.result.PageResult;
import com.smartcampus.service.SetmealService;
import com.smartcampus.vo.DishItemVO;
import com.smartcampus.vo.SetmealVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 套餐业务实现
 * <p>
 * 缓存策略：用户端查询走 Spring Cache（Redis）缓存；
 * 套餐的新增、修改、删除、起停售均清空缓存，保证缓存与数据库一致性。
 */
@Service
@Slf4j
public class SetmealServiceImpl implements SetmealService {

    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private DishMapper dishMapper;

    /**
     * 新增套餐，同时需要保存套餐和菜品的关联关系
     *
     * @param setmealDTO
     */
    @Transactional
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    public void saveWithDish(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);

        // 向套餐表插入1条数据
        setmealMapper.insert(setmeal);

        // 获取生成的套餐id
        Long setmealId = setmeal.getId();

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if (setmealDishes != null && !setmealDishes.isEmpty()) {
            setmealDishes.forEach(sd -> sd.setSetmealId(setmealId));
            // 保存套餐和菜品的关联关系
            setmealDishMapper.insertBatch(setmealDishes);
        }
    }

    /**
     * 分页查询套餐
     *
     * @param setmealPageQueryDTO
     * @return
     */
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        PageHelper.startPage(setmealPageQueryDTO.getPage(), setmealPageQueryDTO.getPageSize());
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 批量删除套餐
     *
     * @param ids
     */
    @Transactional
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    public void deleteBatch(List<Long> ids) {
        for (Long id : ids) {
            Setmeal setmeal = setmealMapper.getById(id);
            if (setmeal == null) {
                continue;
            }
            // 起售中的套餐不能删除
            if (StatusConstant.ENABLE.equals(setmeal.getStatus())) {
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        }

        // 删除套餐表中的数据
        setmealMapper.deleteByIds(ids);

        // 删除套餐和菜品的关联关系
        for (Long id : ids) {
            setmealDishMapper.deleteBySetmealId(id);
        }
    }

    /**
     * 根据id查询套餐和关联的菜品数据
     *
     * @param id
     * @return
     */
    public SetmealVO getByIdWithDish(Long id) {
        Setmeal setmeal = setmealMapper.getById(id);
        List<SetmealDish> setmealDishes = setmealDishMapper.getBySetmealId(id);

        SetmealVO setmealVO = new SetmealVO();
        BeanUtils.copyProperties(setmeal, setmealVO);
        setmealVO.setSetmealDishes(setmealDishes);
        return setmealVO;
    }

    /**
     * 修改套餐
     *
     * @param setmealDTO
     */
    @Transactional
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    public void update(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);

        // 1、修改套餐表，执行 update
        setmealMapper.update(setmeal);

        // 套餐id
        Long setmealId = setmealDTO.getId();

        // 2、删除套餐和菜品的关联关系
        setmealDishMapper.deleteBySetmealId(setmealId);

        // 3、重新插入套餐和菜品的关联关系
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if (setmealDishes != null && !setmealDishes.isEmpty()) {
            setmealDishes.forEach(sd -> sd.setSetmealId(setmealId));
            setmealDishMapper.insertBatch(setmealDishes);
        }
    }

    /**
     * 套餐起售、停售
     *
     * @param status
     * @param id
     */
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    public void startOrStop(Integer status, Long id) {
        // 起售套餐时，判断套餐内是否有停售菜品，有停售菜品则不允许起售
        if (StatusConstant.ENABLE.equals(status)) {
            List<Dish> dishList = dishMapper.getBySetmealId(id);
            if (dishList != null && !dishList.isEmpty()) {
                dishList.forEach(dish -> {
                    if (StatusConstant.DISABLE.equals(dish.getStatus())) {
                        throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                    }
                });
            }
        }

        Setmeal setmeal = Setmeal.builder()
                .id(id)
                .status(status)
                .build();
        setmealMapper.update(setmeal);
    }

    /**
     * 条件查询套餐（用户端，结果写入 Redis 缓存）
     * <p>
     * {@code sync = true} 让 Spring Cache 在回源时对同一个 key 加锁，
     * 避免热点 key 失效瞬间大量并发请求同时回源数据库（缓存击穿）。
     * 注意：该锁为 JVM 进程内锁，集群部署下需配合 Redis 分布式锁使用。
     *
     * @param setmeal
     * @return
     */
    @Cacheable(cacheNames = "setmealCache", key = "#setmeal.categoryId + '_' + #setmeal.status", sync = true)
    public List<Setmeal> list(Setmeal setmeal) {
        return setmealMapper.list(setmeal);
    }

    /**
     * 根据套餐id查询包含的菜品列表
     *
     * @param id
     * @return
     */
    public List<DishItemVO> getDishItemBySetmealId(Long id) {
        return setmealMapper.getDishItemBySetmealId(id);
    }
}
