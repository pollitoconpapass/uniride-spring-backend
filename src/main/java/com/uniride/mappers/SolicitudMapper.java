package com.uniride.mappers;

import com.uniride.dto.requests.SolicitudRequest;
import com.uniride.dto.responses.SolicitudRespuesta;
import com.uniride.entities.Solicitud;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SolicitudMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "viaje", ignore = true)
    @Mapping(target = "pasajero", ignore = true)
    @Mapping(target = "estado", ignore = true)
    @Mapping(target = "motivoRechazo", ignore = true)
    @Mapping(target = "fechaCreacion", ignore = true)
    @Mapping(target = "metodoCompensacion", ignore = true)
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
    @Mapping(source = "metodoCompensacion.id", target = "metodoCompensacionId")
    @Mapping(source = "metodoCompensacion.tipo", target = "metodoCompensacionTipo")
    @Mapping(source = "metodoCompensacion.descripcion", target = "metodoCompensacionDescripcion")
    SolicitudRespuesta toSolicitudRespuesta(Solicitud solicitud);

    @Mapping(source = "advertencia", target = "advertencia")
    @Mapping(source = "carrera", target = "pasajeroCarrera")
    @Mapping(source = "distrito", target = "pasajeroDistrito")
    @Mapping(source = "solicitud.viaje.id", target = "viajeId")
    @Mapping(source = "solicitud.viaje.fecha", target = "fecha")
    @Mapping(source = "solicitud.viaje.hora", target = "hora")
    @Mapping(source = "solicitud.viaje.dia", target = "dia")
    @Mapping(source = "solicitud.viaje.ruta.id", target = "rutaId")
    @Mapping(source = "solicitud.viaje.ruta.origen", target = "origen")
    @Mapping(source = "solicitud.viaje.ruta.destino", target = "destino")
    @Mapping(source = "solicitud.pasajero.id", target = "pasajeroId")
    @Mapping(source = "solicitud.pasajero.nombre", target = "pasajeroNombre")
    @Mapping(source = "solicitud.pasajero.correoInstitucional", target = "pasajeroCorreo")
    @Mapping(source = "solicitud.acuerdo.terminos", target = "acuerdoTerminos")
    @Mapping(source = "solicitud.acuerdo.estado", target = "acuerdoEstado")
    @Mapping(source = "solicitud.metodoCompensacion.id", target = "metodoCompensacionId")
    @Mapping(source = "solicitud.metodoCompensacion.tipo", target = "metodoCompensacionTipo")
    @Mapping(source = "solicitud.metodoCompensacion.descripcion", target = "metodoCompensacionDescripcion")
    SolicitudRespuesta toSolicitudRespuesta(Solicitud solicitud, String advertencia,
            String carrera, String distrito);
}
