package com.expensetracker.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseUrlTest {

    @Test
    void neonStyleUrlBecomesJdbcSettings() {
        var p = DatabaseUrlEnvironmentPostProcessor.toJdbc(
                "postgresql://arth_owner:p%40ss%2Fw0rd@ep-cool-sun-123456.ap-southeast-1.aws.neon.tech/arth?sslmode=require&channel_binding=require");
        assertEquals("jdbc:postgresql://ep-cool-sun-123456.ap-southeast-1.aws.neon.tech/arth?sslmode=require", p.get("spring.datasource.url"));
        assertEquals("arth_owner", p.get("spring.datasource.username"));
        assertEquals("p@ss/w0rd", p.get("spring.datasource.password"));
    }

    @Test
    void portAndPostgresSchemeAndJdbcPassThrough() {
        var p = DatabaseUrlEnvironmentPostProcessor.toJdbc("postgres://u:pw@db.example.com:6543/postgres");
        assertEquals("jdbc:postgresql://db.example.com:6543/postgres", p.get("spring.datasource.url"));
        assertEquals("jdbc:postgresql://h/db", DatabaseUrlEnvironmentPostProcessor.toJdbc("jdbc:postgresql://h/db").get("spring.datasource.url"));
        assertTrue(DatabaseUrlEnvironmentPostProcessor.toJdbc("mysql://x").isEmpty());
    }
}
