package com.sang.controller;


import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 博客评论控制器（待实现）
 */
@RestController
@RequestMapping("/blog-comments")
@Tag(name = "博客评论", description = "博客评论的增删查改（二级评论树结构，parentId=0 为一级评论）")
public class BlogCommentsController {

}
