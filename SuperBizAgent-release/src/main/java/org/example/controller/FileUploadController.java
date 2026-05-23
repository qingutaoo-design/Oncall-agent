package org.example.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.example.config.FileUploadConfig;
import org.example.dto.FileUploadRes;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @PostMapping("/api/upload")
    public ResponseEntity<?> upload(HttpServletRequest request) {
        String contentType = request.getContentType();
        logger.info("收到上传请求, Content-Type: {}", contentType);

        // 1. 检查 Content-Type
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/")) {
            String msg = "请求的 Content-Type 必须是 multipart/form-data，当前为: " + contentType + 
                         "。请检查 Apifox 中 Body 类型是否选择了 form-data（不是 JSON 也不是 x-www-form-urlencoded）";
            logger.warn(msg);
            return ResponseEntity.badRequest().body(errorResponse(msg));
        }

        // 2. 直接通过 Servlet API 获取 file 部件
        Part filePart;
        try {
            filePart = request.getPart("file");
        } catch (IOException | ServletException e) {
            String msg = "解析 multipart 请求失败: " + e.getMessage();
            logger.error(msg, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse(msg));
        }

        if (filePart == null) {
            // 列出所有可用的 part 名字帮助排查
            StringBuilder partsInfo = new StringBuilder();
            try {
                Collection<Part> parts = request.getParts();
                for (Part p : parts) {
                    partsInfo.append("[").append(p.getName()).append("] ");
                }
            } catch (Exception e) {
                partsInfo.append("(无法枚举)");
            }
            String msg = "没有找到名为 'file' 的文件部件。请求中的部件: " + partsInfo;
            logger.warn(msg);
            return ResponseEntity.badRequest().body(errorResponse(msg));
        }

        // 3. 检查文件名
        String originalFilename = filePart.getSubmittedFileName();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse("文件名为空"));
        }

        logger.info("收到文件: {}, 大小: {} bytes", originalFilename, filePart.getSize());

        // 4. 检查文件扩展名
        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorResponse("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions()));
        }

        // 5. 保存文件
        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            Path filePath = uploadDir.resolve(originalFilename).normalize();

            if (Files.exists(filePath)) {
                logger.info("文件已存在，将覆盖: {}", filePath);
                Files.delete(filePath);
            }

            try (InputStream inputStream = filePart.getInputStream()) {
                Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
            }

            long fileSize = Files.size(filePath);
            logger.info("文件上传成功: {} ({} bytes)", filePath, fileSize);

            // 6. 异步索引
            try {
                logger.info("开始为上传文件创建向量索引: {}", filePath);
                vectorIndexService.indexSingleFile(filePath.toString());
                logger.info("向量索引创建成功: {}", filePath);
            } catch (Exception e) {
                logger.error("向量索引创建失败: {}, 错误: {}", filePath, e.getMessage(), e);
            }

            ApiResponse<FileUploadRes> apiResponse = new ApiResponse<>();
            apiResponse.setCode(200);
            apiResponse.setMessage("success");
            apiResponse.setData(new FileUploadRes(originalFilename, filePath.toString(), fileSize));

            return ResponseEntity.ok(apiResponse);

        } catch (IOException e) {
            logger.error("文件保存失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse("文件保存失败: " + e.getMessage()));
        }
    }

    private ApiResponse<String> errorResponse(String message) {
        ApiResponse<String> response = new ApiResponse<>();
        response.setCode(400);
        response.setMessage(message);
        return response;
    }

    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public T getData() { return data; }
        public void setData(T data) { this.data = data; }
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) return "";
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) return false;
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }
}
