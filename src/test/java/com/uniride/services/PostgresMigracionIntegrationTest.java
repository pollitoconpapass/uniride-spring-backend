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
        String shadow="shadow_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            // Los nombres de restricciones no son únicos entre tablas/esquemas PostgreSQL.
            statement.execute("CREATE SCHEMA "+shadow);
            statement.execute("CREATE TABLE "+shadow+".referente (id BIGINT PRIMARY KEY)");
            for(String table : new String[]{"rutas", "vehiculos", "solicitudes"}) {
                String constraint = table.equals("solicitudes") ? "fk_solicitudes_perfil_pasajero"
                        : "fk_"+table+"_perfil_conductor";
                statement.execute("CREATE TABLE "+shadow+"."+table+" (id BIGINT CONSTRAINT "+constraint
                        +" REFERENCES "+shadow+".referente(id))");
            }
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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT correo_institucional,telefono,intentos_otp FROM "+schema+".usuarios")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("ana@upc.edu.pe");
            assertThat(result.getString(2)).isEqualTo("+51987654321");
            assertThat(result.getInt(3)).isZero();
            assertThat(result.next()).isFalse();
        }
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT c.conname, c.confrelid::regclass::text, c.convalidated "
                    +"FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                    +"JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='"+schema+"' "
                    +"AND c.conname IN ('fk_rutas_perfil_conductor','fk_vehiculos_perfil_conductor',"
                    +"'fk_solicitudes_perfil_pasajero') ORDER BY c.conname")) {
            int count=0;
            while(result.next()) {
                String target=result.getString(1).contains("pasajero") ? "pasajeros" : "conductores";
                assertThat(result.getString(2)).isEqualTo(schema+"."+target);
                assertThat(result.getBoolean(3)).isTrue();
                count++;
            }
            assertThat(count).as("FK de cada tabla, aunque otro esquema use el mismo nombre").isEqualTo(3);
        }
    }
}
