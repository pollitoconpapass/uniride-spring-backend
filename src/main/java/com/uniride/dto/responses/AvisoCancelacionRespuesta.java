package com.uniride.dto.responses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.uniride.enums.TipoNotificacion;

/** Conteos por tipo y destinatario, sin direcciones de correo ni contenido. */
public record AvisoCancelacionRespuesta(Boolean paraConductor, TipoNotificacion tipo, String asunto, Long registrados,
        Long conCorreo, Long enviadosSmtp, Long descartados, Long enReintento) {

    @JsonProperty
    public long enCola() { return conCorreo - enviadosSmtp - descartados - enReintento; }

    @JsonProperty
    public long sinRegistroCorreo() { return registrados - conCorreo; }
}
