-- Versionar la restricción aplicada manualmente en US08.
-- Ejecutar solo en PostgreSQL con una BD UniRide ya creada.
BEGIN;
ALTER TABLE rutas DROP CONSTRAINT IF EXISTS rutas_estado_check;
ALTER TABLE rutas ADD CONSTRAINT rutas_estado_check
    CHECK (estado IN ('ACTIVA', 'LLENA', 'VENCIDA', 'ELIMINADA'));
COMMIT;
