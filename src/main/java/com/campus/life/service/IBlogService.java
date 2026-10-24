package com.campus.life.service;

import com.campus.life.dto.Result;
import com.campus.life.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/** 服务类 */
public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    Result queryHotBlog(Integer current);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);
}
