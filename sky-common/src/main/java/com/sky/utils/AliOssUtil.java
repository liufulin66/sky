package com.sky.utils;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.sky.constant.MessageConstant;
import com.sky.exception.BaseException;
//import com.sky.constant.MessageConstant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 阿里云 OSS 工具类(基于 OSS Java SDK V2)
 */
@Data
@AllArgsConstructor
@Slf4j
public class AliOssUtil {

    private String endpoint;
    private String region; // ← 挪到这里
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;

    /**
     * 文件上传
     *
     * @param bytes
     * @param objectName
     * @return
     */
    public String upload(byte[] bytes, String objectName) {

        // V2 的 endpoint 需要带协议头; 这里兼容 yml 中"带/不带 https://"两种写法
        String normalizedEndpoint = endpoint.startsWith("http") ? endpoint : "https://" + endpoint;

        // 创建 OSSClient 实例(V2 客户端实现了 AutoCloseable, 用 try-with-resources 自动释放)

        try (OSSClient ossClient = OSSClient.newBuilder()
                .credentialsProvider(new StaticCredentialsProvider(accessKeyId, accessKeySecret))
                .region(region)
                .endpoint(normalizedEndpoint)
                .build()) {

            // 创建 PutObject 请求
            ossClient.putObject(PutObjectRequest.newBuilder()
                    .bucket(bucketName)
                    .key(objectName)
                    .body(BinaryData.fromBytes(bytes))
                    .build());
        } catch (Exception e) {
            log.error("OSS 文件上传失败", e);
            // throw new BaseException(MessageConstant.UPLOAD_FAILED);
        }

        // 文件访问路径规则 https://BucketName.Endpoint/ObjectName
        String host = endpoint.replaceFirst("^https?://", "");
        StringBuilder stringBuilder = new StringBuilder("https://");
        stringBuilder
                .append(bucketName)
                .append(".")
                .append(host)
                .append("/")
                .append(objectName);

        log.info("文件上传到:{}", stringBuilder.toString());

        return stringBuilder.toString();
    }
}
