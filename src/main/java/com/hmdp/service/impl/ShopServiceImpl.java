package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        //Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        // 互斥锁 缓存击穿
        //Shop shop = queryWithMutex(id);
        // 逻辑过期 缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if(shop ==null){
            return Result.fail("商铺不存在!");
        }
        return Result.ok(shop);
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        // 1.获取key
        String key = CACHE_SHOP_KEY+id;
        // 2从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            // 3查询到了则返回
            return null;
        }
        // 4 存在，则根据数据中的有效期字段判断是否过期
        // 4.1 将Json反序列化位对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonObject, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4.2 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            // 4.3未过期，则直接返回店铺信息
            return shop;
        }
        // 4.4已过期，则需要缓存重建
        // 5 缓存重建
        // 5.1 获取互斥锁
        // 5.1 获取互斥锁
        boolean isLock = tryLock(LOCK_SHOP_KEY+id);
        // 5.2 判断是否成功
        if(isLock){
            // 5.2 成功，则开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 缓存重建
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(LOCK_SHOP_KEY+id);
                }
            });
        }
        // 5.3 失败，则直接返回过期的结果
        return shop;
    }
    public Shop queryWithMutex(Long id){
        // 1.获取key
        String key = CACHE_SHOP_KEY+id;
        // 2从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            // 3查询到了则返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 4.如果为空字符串，则返回错误信息
        if(shopJson != null){
            return  null;
        }
        // 5 实现缓存重建

        Shop shop = null;
        try {
            // 5.1 获取互斥锁
            boolean isLock = tryLock(LOCK_SHOP_KEY+id);
            // 5.2 判断是否成功
            if(!isLock){
                // 5.3 失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 5.4 成功，则根据id查询数据库
            shop = getById(id);
            if (shop == null) {
                // 6不仅返回错误信息，还要将null加入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 7查询到了则写入到redis缓存中，并返回，设置缓存有效时间
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 8 释放互斥锁
            unLock(LOCK_SHOP_KEY+id);
        }
        //返回
        return shop;
    }
    public Shop queryWithPassThrough(Long id){
        // 1.获取key
        String key = CACHE_SHOP_KEY+id;
        // 2从redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            // 3查询到了则返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 4.如果为空字符串，则返回错误信息
        if(shopJson != null){
            return  null;
        }
        // 5没有查到则区数据库中查询
        Shop shop = getById(id);
        if (shop == null) {
            // 6不仅返回错误信息，还要将null加入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        // 7查询到了则写入到redis缓存中，并返回
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        // 7设置缓存有效时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 8数据库中还没有，则报错404
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        Boolean flag = stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSecond) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 2.封装逻辑国企时间，通过redisData实现
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        // 检查shop的id
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺ID不能为空!!!");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
