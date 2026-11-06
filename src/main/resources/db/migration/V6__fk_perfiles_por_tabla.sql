-- V2 consultaba nombres de restricciones globales. PostgreSQL permite repetirlos
-- en otras tablas/esquemas: comprobar también la tabla que recibe cada FK.
-- Reparación aditiva; conservar los checksums de migraciones ya aplicadas.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conname = 'fk_rutas_perfil_conductor' AND conrelid = 'rutas'::regclass) THEN
    ALTER TABLE rutas ADD CONSTRAINT fk_rutas_perfil_conductor
      FOREIGN KEY (conductor_id) REFERENCES conductores(usuario_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conname = 'fk_vehiculos_perfil_conductor' AND conrelid = 'vehiculos'::regclass) THEN
    ALTER TABLE vehiculos ADD CONSTRAINT fk_vehiculos_perfil_conductor
      FOREIGN KEY (conductor_id) REFERENCES conductores(usuario_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conname = 'fk_solicitudes_perfil_pasajero' AND conrelid = 'solicitudes'::regclass) THEN
    ALTER TABLE solicitudes ADD CONSTRAINT fk_solicitudes_perfil_pasajero
      FOREIGN KEY (pasajero_id) REFERENCES pasajeros(usuario_id);
  END IF;
END $$;
