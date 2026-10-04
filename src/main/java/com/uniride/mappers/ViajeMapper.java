package com.uniride.mappers;

import com.uniride.dto.requests.ViajeRequest;
import com.uniride.dto.responses.ViajeRespuesta;
import com.uniride.entities.Viaje;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ViajeMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "ruta", ignore = true)
    @Mapping(target = "dia", ignore = true)
    @Mapping(target = "confirmado", ignore = true)
    @Mapping(target = "fechaConfirmacion", ignore = true)
    @Mapping(target = "estado", ignore = true)
    @Mapping(target = "pasajeros", ignore = true)
    @Mapping(target = "recordatorioEnviado", ignore = true)
    Viaje toViaje(ViajeRequest request);

    @Mapping(source = "ruta.id", target = "rutaId")
    @Mapping(target = "mensaje", ignore = true)
    ViajeRespuesta toViajeRespuesta(Viaje viaje);

    @Mapping(source = "viaje.ruta.id", target = "rutaId")
    @Mapping(source = "mensaje", target = "mensaje")
    ViajeRespuesta toViajeRespuesta(Viaje viaje, String mensaje);
}
