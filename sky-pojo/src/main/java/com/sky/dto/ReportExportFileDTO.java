package com.sky.dto;

/** 只供下载接口使用，content作为原始文件字节返回，不序列化成JSON。 */
public record ReportExportFileDTO(String filename, byte[] content) { }
