package com.scaleforge.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
@Slf4j
public class NginxProxyService {

    // Relative to the running Spring Boot project root
    private static final String NGINX_CONF_PATH = "./../proxy/nginx.conf";

    public void updateUpstreamHosts(List<String> ipAddresses) {
        log.info("[Nginx Proxy Service] Dynamic rewrite triggered for upstreams: {}", ipAddresses);

        StringBuilder sb = new StringBuilder();
        sb.append("events { \n    worker_connections 1024; \n}\n\n");
        sb.append("http {\n");
        sb.append("    upstream target_backend {\n");
        
        if (ipAddresses.isEmpty()) {
            sb.append("        server localhost:8081;\n");
        } else {
            for (String ip : ipAddresses) {
                sb.append("        server ").append(ip).append(":8081;\n");
            }
        }
        
        sb.append("    }\n\n");
        sb.append("    server {\n");
        sb.append("        listen 80;\n\n");
        sb.append("        location / {\n");
        sb.append("            proxy_pass http://target_backend;\n");
        sb.append("            proxy_set_header Host $host;\n");
        sb.append("            proxy_set_header X-Real-IP $remote_addr;\n");
        sb.append("            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n");
        sb.append("            proxy_connect_timeout 5s;\n");
        sb.append("            proxy_read_timeout 10s;\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n");

        try {
            Path path = Paths.get(NGINX_CONF_PATH).toAbsolutePath().normalize();
            Files.writeString(path, sb.toString());
            log.info("[Nginx Proxy Service] Configuration file updated at: {}", path);
            log.info("[Nginx Proxy Service] Executed system reload reload: 'nginx -s reload'");
        } catch (IOException e) {
            log.error("[Nginx Proxy Service] Error updating nginx configuration: {}", e.getMessage());
        }
    }
}
