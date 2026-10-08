package pe.buildshield.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Trazabilidad y Auditoría de BuildShield (microservicio, puerto 8088, base audit_db). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BuildshieldAuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildshieldAuditApplication.class, args);
    }
}
