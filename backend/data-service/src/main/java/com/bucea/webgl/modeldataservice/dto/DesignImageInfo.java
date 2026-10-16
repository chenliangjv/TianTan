package com.bucea.webgl.modeldataservice.dto;

import com.bucea.webgl.modeldataservice.entity.DesignImage;
import java.time.LocalDateTime;

public record DesignImageInfo(Long id, String name, String originalName, String format,
                              int width, int height, long fileSize, LocalDateTime createTime,
                              String imageUrl, String thumbnailUrl) {
    public static DesignImageInfo from(DesignImage image) {
        String base = "/api/data/design-images/" + image.getId();
        return new DesignImageInfo(image.getId(), image.getName(), image.getOriginalName(), image.getFormat(),
                image.getWidth(), image.getHeight(), image.getFileSize(), image.getCreateTime(),
                base + "/content", base + "/thumbnail");
    }
}
