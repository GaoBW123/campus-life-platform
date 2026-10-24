package com.campus.life.service;

import com.campus.life.dto.Result;
import com.campus.life.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/** 服务类 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);
}
