-- UniRide: ejecutar UNA sola vez en la base PostgreSQL existente de desarrollo.
-- Realizar backup ANTES. Las tablas se crean sin eliminar usuarios, rutas o solicitudes.
-- Reejecución segura: IF NOT EXISTS y ON CONFLICT, con restricciones condicionadas.
BEGIN;

CREATE TABLE IF NOT EXISTS conductores (
    usuario_id BIGINT PRIMARY KEY REFERENCES usuarios(id),
    fecha_alta TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS pasajeros (
    usuario_id BIGINT PRIMARY KEY REFERENCES usuarios(id),
    fecha_alta TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Reconstruir conductores por rol activo o por uso histórico como conductor.
INSERT INTO conductores (usuario_id, fecha_alta)
SELECT DISTINCT x.usuario_id, CURRENT_TIMESTAMP
FROM (
    SELECT id AS usuario_id FROM usuarios WHERE rol_principal = 'CONDUCTOR'
    UNION
    SELECT conductor_id AS usuario_id FROM rutas
    UNION
    SELECT conductor_id AS usuario_id FROM vehiculos
) x
ON CONFLICT (usuario_id) DO NOTHING;

-- Reconstruir pasajeros por rol activo o por solicitudes históricas.
INSERT INTO pasajeros (usuario_id, fecha_alta)
SELECT DISTINCT x.usuario_id, CURRENT_TIMESTAMP
FROM (
    SELECT id AS usuario_id FROM usuarios WHERE rol_principal = 'PASAJERO'
    UNION
    SELECT pasajero_id AS usuario_id FROM solicitudes
) x
ON CONFLICT (usuario_id) DO NOTHING;

-- Integridad relacional: además de usuarios, conductor_id y pasajero_id
-- deben existir en sus tablas específicas. No se eliminan FKs previas.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_rutas_perfil_conductor') THEN
    ALTER TABLE rutas ADD CONSTRAINT fk_rutas_perfil_conductor
      FOREIGN KEY (conductor_id) REFERENCES conductores(usuario_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_vehiculos_perfil_conductor') THEN
    ALTER TABLE vehiculos ADD CONSTRAINT fk_vehiculos_perfil_conductor
      FOREIGN KEY (conductor_id) REFERENCES conductores(usuario_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_solicitudes_perfil_pasajero') THEN
    ALTER TABLE solicitudes ADD CONSTRAINT fk_solicitudes_perfil_pasajero
      FOREIGN KEY (pasajero_id) REFERENCES pasajeros(usuario_id);
  END IF;
END $$;

COMMIT;
