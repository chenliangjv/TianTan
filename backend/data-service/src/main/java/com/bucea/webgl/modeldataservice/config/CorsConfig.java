package com.bucea.webgl.modeldataservice.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.util.Collections;

/**
 * CORS 配置：允许前端直连 data-service（绕过 Gateway）下载倾斜模型文件。
 * <p>
 * 关键：检查请求是否来自 Gateway（带 X-Forwarded-Host 头）。
 * - 来自 Gateway 的请求：跳过 CORS（Gateway 已处理），避免响应头重复
 * - 直连请求：由本 Filter 处理 CORS
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<Filter> corsFilterRegistration() {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new SimpleCorsFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }

    static class SimpleCorsFilter implements Filter {
        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) res;

            // Potree 2.0 marks byte-range GETs as multipart/byteranges. Spring
            // otherwise routes the request through its multipart form parser,
            // which rejects it before the file streaming controller is reached.
            if (isPotreeByteRangeRequest(request)) {
                request = new PotreeByteRangeRequestWrapper(request);
            }

            // 来自 Gateway 的请求带 X-Forwarded-Host，跳过 CORS（Gateway 会处理），避免头重复
            if (request.getHeader("X-Forwarded-Host") == null) {
                String origin = request.getHeader("Origin");
                if (origin != null) {
                    response.setHeader("Access-Control-Allow-Origin", origin);
                    response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD");
                    response.setHeader("Access-Control-Allow-Headers", "Content-Type, Accept, Range, X-Requested-With");
                    response.setHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Content-Disposition, Accept-Ranges, ETag");
                    response.setHeader("Access-Control-Max-Age", "3600");
                    response.setHeader("Access-Control-Allow-Credentials", "true");
                }
                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
            }
            chain.doFilter(request, res);
        }

        private boolean isPotreeByteRangeRequest(HttpServletRequest request) {
            String contentType = request.getContentType();
            return "GET".equalsIgnoreCase(request.getMethod())
                    && contentType != null
                    && contentType.regionMatches(true, 0, "multipart/byteranges", 0,
                    "multipart/byteranges".length());
        }
    }

    private static class PotreeByteRangeRequestWrapper extends HttpServletRequestWrapper {
        private PotreeByteRangeRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public String getHeader(String name) {
            return "Content-Type".equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            return "Content-Type".equalsIgnoreCase(name)
                    ? Collections.emptyEnumeration()
                    : super.getHeaders(name);
        }
    }
}
