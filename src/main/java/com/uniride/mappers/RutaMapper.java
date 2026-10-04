package com.uniride.mappers;

import com.uniride.dto.requests.RutaRequest;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.entities.Ruta;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface RutaMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "conductor", ignore = true)
    @Mapping(target = "destino", ignore = true)
    @Mapping(target = "estado", ignore = true)
    @Mapping(target = "fechaCreacion", ignore = true)
    Ruta toRuta(RutaRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "conductor", ignore = true)
    @Mapping(target = "destino", ignore = true)
    @Mapping(target = "estado", ignore = true)
    @Mapping(target = "fechaCreacion", ignore = true)
    void actualizarRuta(RutaRequest request, @MappingTarget Ruta ruta);

    @Mapping(source = "conductor.id", target = "conductorId")
    @Mapping(source = "conductor.nombre", target = "conductorNombre")
    RutaRespuesta toRutaRespuesta(Ruta ruta);
}
