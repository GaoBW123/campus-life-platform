package com.campus.life.service.impl;

import com.campus.life.entity.BlogComments;
import com.campus.life.mapper.BlogCommentsMapper;
import com.campus.life.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 服务实现类 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
