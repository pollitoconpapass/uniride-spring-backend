-- Additive only: UUID belongs to a user; business writes and result share a transaction.
CREATE TABLE agent_confirmaciones (
    id UUID PRIMARY KEY,
    usuario_id BIGINT NOT NULL REFERENCES usuarios(id),
    tipo VARCHAR(32) NOT NULL CHECK (tipo IN ('CREAR_RUTA','CREAR_VIAJE','SOLICITAR_UNIRSE','REGISTRAR_METODO')),
    estado VARCHAR(16) NOT NULL CHECK (estado IN ('PENDIENTE','EJECUTADA','CANCELADA','EXPIRADA')),
    parametros TEXT NOT NULL,
    snapshot TEXT NOT NULL,
    resumen TEXT NOT NULL,
    recurso_id BIGINT,
    creada TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expira TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    ejecutada TIMESTAMP(6) WITH TIME ZONE,
    resultado TEXT,
    CONSTRAINT ck_agent_confirmacion_plazo CHECK (expira > creada),
    CONSTRAINT ck_agent_confirmacion_resultado CHECK (
        (estado = 'EJECUTADA' AND ejecutada IS NOT NULL AND resultado IS NOT NULL)
        OR (estado <> 'EJECUTADA' AND ejecutada IS NULL AND resultado IS NULL))
);
CREATE INDEX ix_agent_confirmaciones_usuario_estado ON agent_confirmaciones(usuario_id, estado, expira);
