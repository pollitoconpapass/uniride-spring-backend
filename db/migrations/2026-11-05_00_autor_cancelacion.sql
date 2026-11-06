-- Compatibilidad temporal para bases existentes; se incorporará a la cadena versionada.
-- NULL significa autor desconocido. No inferir autores desde penalidades históricas.
BEGIN;
ALTER TABLE viajes ADD COLUMN IF NOT EXISTS cancelado_por_id BIGINT REFERENCES usuarios(id);
ALTER TABLE viajes ADD COLUMN IF NOT EXISTS fecha_cancelacion TIMESTAMP;
COMMIT;
