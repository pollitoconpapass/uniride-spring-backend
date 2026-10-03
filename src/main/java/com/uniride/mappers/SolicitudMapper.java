package com.uniride.mappers;

import com.uniride.dto.SolicitudRequest;
import com.uniride.dto.SolicitudRespuesta;
import com.uniride.entities.Solicitud;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface SolicitudMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "viaje", ignore = true)
    @Mapping(target = "pasajero", ignore = true)
    @Mapping(target = "estado", ignore = true)
    @Mapping(target = "motivoRechazo", ignore = true)
    @Mapping(target = "fechaCreacion", ignore = true)
    Solicitud toSolicitud(SolicitudRequest request);

    @Mapping(target = "advertencia", ignore = true)
    @Mapping(target = "pasajeroCarrera", ignore = true)
    @Mapping(target = "pasajeroDistrito", ignore = true)
    @Mapping(source = "viaje.id", target = "viajeId")
    @Mapping(source = "viaje.fecha", target = "fecha")
    @Mapping(source = "viaje.hora", target = "hora")
    @Mapping(source = "viaje.dia", target = "dia")
    @Mapping(source = "viaje.ruta.id", target = "rutaId")
    @Mapping(source = "viaje.ruta.origen", target = "origen")
    @Mapping(source = "viaje.ruta.destino", target = "destino")
    @Mapping(source = "pasajero.id", target = "pasajeroId")
    @Mapping(source = "pasajero.nombre", target = "pasajeroNombre")
    @Mapping(source = "pasajero.correoInstitucional", target = "pasajeroCorreo")
    @Mapping(source = "acuerdo.terminos", target = "acuerdoTerminos")
    @Mapping(source = "acuerdo.estado", target = "acuerdoEstado")
    SolicitudRespuesta toSolicitudRespuesta(Solicitud solicitud);
}
