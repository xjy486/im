package com.jitong.im.platform.health;

import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.platform.error.ApiErrorResponse;
import com.jitong.im.platform.observability.RequestContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
class ServiceHealthController {

    private final HealthEndpoint healthEndpoint;

    ServiceHealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/api/v1/system/health")
    ResponseEntity<?> health(HttpServletRequest request) {
        HealthComponent health = healthEndpoint.health();
        if (!Status.UP.equals(health.getStatus())) {
            ApiErrorDefinition error = ApiErrorDefinition.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(error.status())
                    .body(ApiErrorResponse.create(error, RequestContextFilter.requestId(request)));
        }

        return ResponseEntity.ok(ServiceHealthResponse.up(dependencyStatuses(health)));
    }

    private Map<String, String> dependencyStatuses(HealthComponent health) {
        Map<String, String> dependencies = new LinkedHashMap<>();
        if (health instanceof CompositeHealth composite) {
            addStatus(dependencies, "postgresql", composite.getComponents().get("db"));
            addStatus(dependencies, "minio", composite.getComponents().get("minio"));
        }
        return dependencies;
    }

    private void addStatus(Map<String, String> dependencies, String name, HealthComponent component) {
        if (component != null) {
            dependencies.put(name, component.getStatus().getCode());
        }
    }
}
