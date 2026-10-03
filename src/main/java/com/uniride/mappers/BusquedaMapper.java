package com.uniride.mappers;

import com.uniride.dto.BusquedaRespuesta;
import com.uniride.entities.Viaje;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BusquedaMapper {

    @Mapping(source = "id", target = "viajeId")
    @Mapping(source = "ruta.id", target = "rutaId")
    @Mapping(source = "ruta.origen", target = "origen")
    @Mapping(source = "ruta.destino", target = "destino")
    @Mapping(source = "ruta.puntosReferencia", target = "puntosReferencia")
    @Mapping(source = "ruta.zonasSinParada", target = "zonasSinParada")
    @Mapping(source = "ruta.notaContribucion", target = "notaContribucion")
    @Mapping(source = "ruta.capacidadMaxima", target = "capacidadMaxima")
    @Mapping(source = "ruta.conductor.id", target = "conductorId")
    @Mapping(source = "ruta.conductor.nombre", target = "conductorNombre")
    BusquedaRespuesta toBusquedaRespuesta(Viaje viaje);
}
