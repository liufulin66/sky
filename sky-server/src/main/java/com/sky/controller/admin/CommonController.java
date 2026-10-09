package com.sky.controller.admin;

import com.sky.constant.MessageConstant;
import com.sky.result.Result;
import com.sky.utils.AliOssUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * 通用接口
 */
@RestController
@RequestMapping("/admin/common")
@Slf4j
@Tag(name = "通用接口")
public class CommonController {

    @Autowired
    private AliOssUtil aliOssUtil;

    /**
     * 文件上传
     *
     * @param file Spring MVC 自动把 multipart/form-data 里的文件部分解析成 MultipartFile
     * @return 文件的访问 URL
     */
    @PostMapping("/upload")
    @Operation(summary = "文件上传")
    public Result<String> upload(MultipartFile file) {
        log.info("文件上传：{}", file.getOriginalFilename());

        try {
            // 1. 原始文件名可能重复/带中文/带特殊字符 → 用 UUID 重新生成对象名，只保留扩展名
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.lastIndexOf(".") >= 0) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String objectName = UUID.randomUUID().toString() + extension;

            // 2. 上传字节流，返回可访问的完整 URL
            String filePath = aliOssUtil.upload(file.getBytes(), objectName);
            return Result.success(filePath);
        } catch (IOException e) {
            log.error("文件上传失败：{}", e.getMessage());
        }

        return Result.error(MessageConstant.UPLOAD_FAILED);
    }
}
