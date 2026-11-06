package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

@Tag("postgres")
class PostgresMigracionIntegrationTest {
    @Test void baselineExistenteConservaFilasAplicaV2YSeReejecutaSinCambios() throws Exception {
        String url=System.getenv().getOrDefault("UNIRIDE_TEST_PG_URL","jdbc:postgresql://localhost:55432/uniride_audit");
        String user=System.getenv().getOrDefault("UNIRIDE_TEST_PG_USER","postgres");
        String password=System.getenv().getOrDefault("UNIRIDE_TEST_PG_PASSWORD","uniride_ephemeral_test_only");
        String schema="legacy_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA "+schema);
            statement.execute("SET search_path TO "+schema);
            String sql=new String(getClass().getResourceAsStream("/db/migration/V1__schema_inicial.sql").readAllBytes(),StandardCharsets.UTF_8);
            // Fixture anterior al hardening: sin outbox, autor ni contadores OTP.
            sql=sql.replaceAll("(?m)^create table correo_outbox.*\\R?","")
                    .replace("intentos_otp integer default 0 not null, ","")
                    .replace("otp_bloqueado_hasta timestamp(6), ","").replace("ultimo_envio_otp timestamp(6), ","")
                    .replace("cancelado_por_id bigint, ","").replace("fecha_cancelacion timestamp(6), ","")
                    .replaceAll("(?m)^alter table if exists viajes.*foreign key \\(cancelado_por_id\\).*\\R?","");
            statement.execute(sql);
            statement.execute("INSERT INTO usuarios (correo_institucional,telefono,nombre,apellidos,contrasena_hash,acepta_terminos,cuenta_verificada,fecha_registro,rol_principal) VALUES ('ANA@UPC.EDU.PE','0051987654321','Ana','Perez','hash',true,false,CURRENT_TIMESTAMP,'PASAJERO')");
        }
        Flyway flyway=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                .baselineVersion("1").cleanDisabled(true).load();
        flyway.baseline();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT correo_institucional,telefono,intentos_otp FROM "+schema+".usuarios")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("ana@upc.edu.pe");
            assertThat(result.getString(2)).isEqualTo("+51987654321");
            assertThat(result.getInt(3)).isZero();
            assertThat(result.next()).isFalse();
        }
    }
}
