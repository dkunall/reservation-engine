package com.reservation.engine.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;

/**
 * Since Spring 6, {@link JdbcTemplate} defaults to {@code SQLExceptionSubclassTranslator}. The PostgreSQL driver does
 * not throw the JDBC 4 exception subclasses, so a lock timeout (SQLSTATE 55P03) from a JdbcTemplate statement surfaced
 * as {@code UncategorizedSQLException} (HTTP 500, no whole-transaction retry) instead of
 * {@code CannotAcquireLockException}. Found by the load benchmark; this switches the auto-configured JdbcTemplate
 * (also used by every NamedParameterJdbcTemplate) to the error-code translator, whose bundled PostgreSQL mapping
 * covers 55P03 and 40P01, so both become {@code PessimisticLockingFailureException} like the JPA path already does.
 */
@Configuration(proxyBeanMethods = false)
public class JdbcExceptionTranslationConfig {

    @Bean
    static BeanPostProcessor postgresJdbcExceptionTranslator() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof JdbcTemplate template && template.getDataSource() != null) {
                    template.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator(template.getDataSource()));
                }
                return bean;
            }
        };
    }
}
