package com.llm.indexer.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Explicitly serves static/llm-indexer/** (style.css) at WebController.BASE + "/**"
 * (e.g. /llm-indexer/style.css). Spring Boot's default classpath:/static/ -> "/**"
 * mapping would already cover this since the file is namespaced under static/llm-indexer/,
 * but that default depends on the host app's spring.mvc.static-path-pattern staying at
 * its default; this registration doesn't. Scoped to exactly the llm-indexer/ subfolder
 * (not the whole static/ root) so it can't leak the host app's other static assets.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(WebController.BASE + "/**")
                .addResourceLocations("classpath:/static" + WebController.BASE + "/");
    }
}
