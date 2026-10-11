package com.smartcampus.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.smartcampus.constant.MessageConstant;
import com.smartcampus.constant.StatusConstant;
import com.smartcampus.dto.DishDTO;
import com.smartcampus.dto.DishPageQueryDTO;
import com.smartcampus.entity.Dish;
import com.smartcampus.entity.DishFlavor;
import com.smartcampus.exception.DeletionNotAllowedException;
import com.smartcampus.lock.RedisLock;
import com.smartcampus.mapper.DishFlavorMapper;
import com.smartcampus.mapper.DishMapper;
import com.smartcampus.mapper.SetmealDishMapper;
import com.smartcampus.result.PageResult;
import com.smartcampus.service.DishService;
import com.smartcampus.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 菜品业务实现
 * <p>
 * 缓存策略：用户端按分类查询菜品时优先读取 Redis（key = dish_分类id），
 * 菜品的新增、修改、删除、起停售均清理缓存，保证缓存与数据库一致性。
 */
@Service
@Slf4j
public class DishServiceImpl implements DishService {

    /**
     * 菜品缓存基础 TTL（秒）
     */
    private static final long CACHE_DISH_TTL = 30 * 60L;

    /**
     * 缓存 TTL 随机扰动上限（秒），用于打散过期时间、防止缓存雪崩
     */
    private static final int CACHE_TTL_JITTER = 300;

    /**
     * 空值缓存 TTL（秒），用于防止缓存穿透
     */
    private static final long CACHE_NULL_TTL = 60L;

    /**
     * 菜品缓存 key 前缀
     */
    private static final String DISH_CACHE_KEY_PREFIX = "dish_";

    /**
     * 缓存重建互斥锁 key 前缀
     */
    private static final String LOCK_KEY_PREFIX = "lock:dish:";

    /**
     * 互斥锁自动过期时间（秒），防止持有者宕机导致死锁
     */
    private static final long LOCK_EXPIRE_SECONDS = 10L;

    /**
     * 未抢到锁时的最大自旋次数（20 * 50ms = 1s 上限）
     */
    private static final int LOCK_RETRY_TIMES = 20;

    /**
     * 未抢到锁时的自旋间隔（毫秒）
     */
    private static final long LOCK_RETRY_INTERVAL_MS = 50L;

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RedisLock redisLock;

    /**
     * 新增菜品和口味数据
     *
     * @param dishDTO
     */
    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        // 向菜品表插入1条数据
        dishMapper.insert(dish);

        // 获取菜品id
        Long dishId = dish.getId();

        // 向口味表插入n条数据
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && !flavors.isEmpty()) {
            flavors.forEach(flavor -> flavor.setDishId(dishId));
            dishFlavorMapper.insertBatch(flavors);
        }

        // 清理缓存：新增菜品影响该分类下的菜品列表
        cleanCache("dish_" + dishDTO.getCategoryId());
    }

    /**
     * 分页查询菜品
     *
     * @param dishPageQueryDTO
     * @return
     */
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 删除菜品
     *
     * @param ids
     * @return
     */
    @Transactional
    public void deleteBatch(List<Long> ids) {
        // 判断当前菜品是否能被删除，--是否存在起售中的菜品？
        for (Long id : ids) {
            Dish dish = dishMapper.getById(id);
            if (dish != null && StatusConstant.ENABLE.equals(dish.getStatus())) {
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }

        // 判断当前菜品是否能被删除，--是否被套餐关联了？
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && !setmealIds.isEmpty()) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }

        // 根据菜品id集合批量删除菜品
        dishMapper.deleteByIds(ids);
        // 根据菜品id集合批量删除关联的口味
        dishFlavorMapper.deleteBatchByDishIds(ids);

        // 删除可能涉及多个分类，统一清理所有菜品缓存
        cleanCache("dish_*");
    }

    /**
     * 根据id查询菜品详情和口味信息
     *
     * @param id
     * @return
     */
    public DishVO getByIdwithFlavor(Long id) {
        // 根据id查询菜品详情
        Dish dish = dishMapper.getById(id);
        // 根据菜品id查询关联的口味信息
        List<DishFlavor> dishFlavors = dishFlavorMapper.getByDishId(id);
        // 将查询信息封装到VO
        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish, dishVO);
        dishVO.setFlavors(dishFlavors);
        return dishVO;
    }

    /**
     * 根据id修改菜品和口味信息
     *
     * @param dishDTO
     */
    @Transactional
    public void updateWithFlavor(DishDTO dishDTO) {
        // 根据菜品id更新菜品信息
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.updateById(dish);

        // 根据菜品id删除关联的口味信息
        dishFlavorMapper.deleteBatchByDishId(dishDTO.getId());

        // 根据菜品id批量插入新的口味信息
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && !flavors.isEmpty()) {
            flavors.forEach(flavor -> flavor.setDishId(dishDTO.getId()));
            dishFlavorMapper.insertBatch(flavors);
        }

        // 清理缓存
        cleanCache("dish_" + dishDTO.getCategoryId());
    }

    /**
     * 菜品起售、停售
     *
     * @param status
     * @param id
     */
    @Transactional
    public void startOrStop(Integer status, Long id) {
        Dish dish = Dish.builder()
                .id(id)
                .status(status)
                .build();
        dishMapper.updateById(dish);

        // 清理缓存
        Dish dishDB = dishMapper.getById(id);
        if (dishDB != null) {
            cleanCache("dish_" + dishDB.getCategoryId());
        }
    }

    /**
     * 条件查询菜品和口味（用户端，优先走 Redis 缓存）
     *
     * @param dish
     * @return
     */
    public List<DishVO> listWithFlavor(Dish dish) {
        // 参数校验：分类 id 为空时直接返回空集合，避免无效请求穿透到数据库
        if (dish.getCategoryId() == null) {
            return new ArrayList<>();
        }

        String key = DISH_CACHE_KEY_PREFIX + dish.getCategoryId();

        // 1、先查询 Redis 缓存（注意：空集合同样会被缓存，用于防穿透）
        List<DishVO> dishVOList = getFromCache(key);
        if (dishVOList != null) {
            log.info("菜品缓存命中，key = {}", key);
            return dishVOList;
        }

        // 2、缓存未命中：加分布式互斥锁后重建。
        //    避免热点 key 失效的瞬间，大量并发请求同时回源数据库（缓存击穿）
        String lockKey = LOCK_KEY_PREFIX + dish.getCategoryId();
        String lockToken = redisLock.tryLock(lockKey, LOCK_EXPIRE_SECONDS);
        if (lockToken == null) {
            // 未抢到锁：说明已有线程正在重建缓存，短暂自旋等待其完成
            for (int i = 0; i < LOCK_RETRY_TIMES; i++) {
                sleepQuietly(LOCK_RETRY_INTERVAL_MS);
                dishVOList = getFromCache(key);
                if (dishVOList != null) {
                    log.info("等待缓存重建完成，命中缓存，key = {}", key);
                    return dishVOList;
                }
            }
            // 自旋超时仍未命中：降级为直接查询数据库，保证接口可用性（不无限阻塞）
            log.warn("等待缓存重建超时，降级直接查询数据库，key = {}", key);
            return loadFromDbAndCache(dish, key);
        }

        try {
            // 3、双重检查：可能在等锁期间已被其他线程重建，避免重复回源
            dishVOList = getFromCache(key);
            if (dishVOList != null) {
                return dishVOList;
            }
            return loadFromDbAndCache(dish, key);
        } finally {
            // 4、释放锁（Lua 脚本校验持有者，保证原子性）
            redisLock.unlock(lockKey, lockToken);
        }
    }

    /**
     * 读取缓存；未命中返回 null（空集合视为命中，用于防穿透）
     */
    @SuppressWarnings("unchecked")
    private List<DishVO> getFromCache(String key) {
        return (List<DishVO>) redisTemplate.opsForValue().get(key);
    }

    /**
     * 回源数据库并写入缓存（含防穿透的空值缓存、防雪崩的 TTL 随机扰动）
     */
    private List<DishVO> loadFromDbAndCache(Dish dish, String key) {
        List<Dish> dishList = dishMapper.list(dish);
        List<DishVO> dishVOList = new ArrayList<>();
        for (Dish d : dishList) {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(d, dishVO);
            // 查询菜品对应的口味数据
            dishVO.setFlavors(dishFlavorMapper.getByDishId(d.getId()));
            dishVOList.add(dishVO);
        }

        if (dishVOList.isEmpty()) {
            // 防穿透：查询结果为空时缓存空集合（短 TTL），
            // 避免同一个非法分类 id 被反复穿透到数据库
            redisTemplate.opsForValue().set(key, dishVOList, CACHE_NULL_TTL, TimeUnit.SECONDS);
            log.info("菜品查询结果为空，已写入空值缓存防穿透，key = {}", key);
        } else {
            // 防雪崩：TTL 叠加随机扰动，避免大批 key 在同一时刻集中过期
            long ttl = CACHE_DISH_TTL + ThreadLocalRandom.current().nextInt(CACHE_TTL_JITTER);
            redisTemplate.opsForValue().set(key, dishVOList, ttl, TimeUnit.SECONDS);
        }
        return dishVOList;
    }

    /**
     * 休眠，忽略中断异常并恢复中断标记
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 条件查询菜品（管理端，用于套餐管理中挑选菜品）
     *
     * @param dish
     * @return
     */
    public List<Dish> list(Dish dish) {
        return dishMapper.list(dish);
    }

    /**
     * 清理缓存数据
     *
     * @param pattern 支持通配符，例如 dish_1 或 dish_*
     */
    private void cleanCache(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
