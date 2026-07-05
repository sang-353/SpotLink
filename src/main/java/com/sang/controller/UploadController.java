package com.sang.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.sang.dto.Result;
import com.sang.utils.SystemConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * 文件上传控制器 — 博客图片上传与删除
 */
@Slf4j
@RestController
@RequestMapping("upload")
@Tag(name = "文件上传", description = "博客图片上传与删除，存储到本地磁盘")
public class UploadController {

    @Operation(summary = "上传博客图片", description = "上传图片文件到服务器本地磁盘，按哈希分目录存储，返回相对路径文件名")
    @PostMapping("blog")
    public Result uploadImage(
            @Parameter(description = "图片文件（支持 jpg/png/gif 等常见格式）", required = true)
            @RequestParam("file") MultipartFile image) {
        try {
            String originalFilename = image.getOriginalFilename();
            String fileName = createNewFileName(originalFilename);
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @Operation(summary = "删除博客图片", description = "根据文件名删除服务器本地磁盘上的图片文件")
    @GetMapping("/blog/delete")
    public Result deleteBlogImg(
            @Parameter(description = "图片文件名（相对路径）", required = true, example = "/blogs/3/a/uuid.jpg")
            @RequestParam("name") String filename) {
        File file = new File(SystemConstants.IMAGE_UPLOAD_DIR, filename);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
