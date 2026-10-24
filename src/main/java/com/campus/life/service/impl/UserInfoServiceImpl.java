package com.campus.life.service.impl;

import com.campus.life.entity.UserInfo;
import com.campus.life.mapper.UserInfoMapper;
import com.campus.life.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 服务实现类 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
