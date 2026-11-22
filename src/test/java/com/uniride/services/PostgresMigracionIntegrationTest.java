package com.uniride.services;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;

@Tag("postgres")
class PostgresMigracionIntegrationTest {
    private final String url = System.getenv().getOrDefault("UNIRIDE_TEST_PG_URL", "jdbc:postgresql://localhost:55432/uniride_audit");
    private final String user = System.getenv().getOrDefault("UNIRIDE_TEST_PG_USER", "postgres");
    private final String password = System.getenv().getOrDefault("UNIRIDE_TEST_PG_PASSWORD", "uniride_ephemeral_test_only");

    @Test void baselineExistenteConservaFilasAplicaV2YSeReejecutaSinCambios() throws Exception {
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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(7);
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

    @Test void v8PreservaLasConfirmacionesDeV7YAceptaLosNuevosTipos() throws Exception {
        String schema = "confirmaciones_" + UUID.randomUUID().toString().replace("-", "");
        var configuration = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .cleanDisabled(true);
        Flyway v7 = configuration.target(MigrationVersion.fromVersion("7")).load();
        assertThat(v7.migrate().migrationsExecuted).isEqualTo(7);

        String table = schema + ".agent_confirmaciones";
        String[] tiposPrevios = {"CREAR_RUTA", "CREAR_VIAJE", "SOLICITAR_UNIRSE", "REGISTRAR_METODO"};
        String[] tiposNuevos = {"ACTUALIZAR_METODO", "CAMBIAR_ESTADO_METODO", "ELIMINAR_METODO"};
        try (var connection = DriverManager.getConnection(url, user, password)) {
            long owner = insertarPropietario(connection, schema);
            try (var constraints = connection.prepareStatement("""
                    SELECT conname FROM pg_constraint
                    WHERE conrelid = CAST(? AS regclass) AND contype = 'c'
                    AND pg_get_constraintdef(oid) LIKE '%CREAR_RUTA%'
                    """)) {
                constraints.setString(1, table);
                try (var result = constraints.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("agent_confirmaciones_tipo_check");
                    assertThat(result.next()).isFalse();
                }
            }
            for (String tipo : tiposPrevios) insertarPendiente(connection, table, owner, tipo);
            var anteriores = filasConfirmacion(connection, table);
            assertThat(anteriores).hasSize(tiposPrevios.length);
            assertThatThrownBy(() -> insertarPendiente(connection, table, owner, "ACTUALIZAR_METODO"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));

            Flyway v8 = configuration.target(MigrationVersion.fromVersion("8")).load();
            assertThat(v8.migrate().migrationsExecuted).isEqualTo(1);
            v8.validate();
            assertThat(v8.info().current().getVersion().getVersion()).isEqualTo("8");
            assertThat(filasConfirmacion(connection, table)).containsExactlyElementsOf(anteriores);

            for (String tipo : tiposPrevios) insertarPendiente(connection, table, owner, tipo);
            for (String tipo : tiposNuevos) insertarPendiente(connection, table, owner, tipo);
            assertThat(filasConfirmacion(connection, table)).hasSize(11);
            assertThatThrownBy(() -> insertarPendiente(connection, table, owner, "OTRA_OPERACION"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        }
    }

    private long insertarPropietario(Connection connection, String schema) throws SQLException {
        String sql = """
                INSERT INTO %s.usuarios (correo_institucional, telefono, nombre, apellidos, contrasena_hash,
                    acepta_terminos, cuenta_verificada, fecha_registro, rol_principal)
                VALUES ('prueba-v8@upc.edu.pe', '+51987654321', 'Ana', 'Prueba', 'fixture',
                    true, true, CURRENT_TIMESTAMP, 'PASAJERO') RETURNING id
                """.formatted(schema);
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private void insertarPendiente(Connection connection, String table, long owner, String tipo) throws SQLException {
        String sql = """
                INSERT INTO %s (id, usuario_id, tipo, estado, parametros, snapshot, resumen, creada, expira)
                VALUES (?, ?, ?, 'PENDIENTE', ?, '{}', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                """.formatted(table);
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setLong(2, owner);
            statement.setString(3, tipo);
            statement.setString(4, "{\"tipo\":\"FAVOR\",\"descripcion\":\"Apuntes\",\"activo\":true}");
            statement.setString(5, "Confirmación de prueba " + tipo);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private List<String> filasConfirmacion(Connection connection, String table) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT row_to_json(c)::text FROM " + table + " c ORDER BY c.id")) {
            while (result.next()) rows.add(result.getString(1));
        }
        return rows;
    }
}
