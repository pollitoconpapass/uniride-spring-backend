ALTER TABLE correo_outbox ADD COLUMN descartado TIMESTAMPTZ;
ALTER TABLE correo_outbox ADD COLUMN expira TIMESTAMPTZ;
ALTER TABLE correo_outbox ADD COLUMN otp_usuario_id BIGINT;
ALTER TABLE correo_outbox ADD COLUMN otp_hash VARCHAR(255);
