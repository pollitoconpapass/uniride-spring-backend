ALTER TABLE solicitudes ADD COLUMN afectada_por_cancelacion_viaje BOOLEAN NOT NULL DEFAULT FALSE;
-- Sin backfill inferido: la participación efectiva en cancelaciones antiguas no es verificable.
ALTER TABLE correo_outbox ADD CONSTRAINT fk_outbox_notificacion
 FOREIGN KEY (notificacion_id) REFERENCES notificaciones(id);
