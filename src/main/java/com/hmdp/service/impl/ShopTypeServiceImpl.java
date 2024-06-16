package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryByType() {
        // 首先，查询redis缓存。由于商铺类型一般不变，所以我们默认一个key即可
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().range(SHOP_TYPE_KEY, 0, -1);
        // 查询到了则返回
        if(!shopTypeJsonList.isEmpty()){
            List<ShopType> shopTypeList = new ArrayList<>();
            for (String shopTypeJson : shopTypeJsonList) {
                shopTypeList.add(JSONUtil.toBean(shopTypeJson, ShopType.class));
            }
            return Result.ok(shopTypeList);
        }
        // 否则，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if(shopTypeList==null){
            return Result.fail("商铺不存在!");
        }
        // 记录到redis缓存中
        for (ShopType shopType : shopTypeList) {
            stringRedisTemplate.opsForList().rightPush(SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopType));
        }
        return Result.ok(shopTypeList);
    }
}
