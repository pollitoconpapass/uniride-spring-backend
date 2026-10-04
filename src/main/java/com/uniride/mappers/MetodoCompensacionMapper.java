package com.uniride.mappers;

import com.uniride.dto.MetodoCompensacionRequest;
import com.uniride.dto.MetodoCompensacionRespuesta;
import com.uniride.entities.MetodoCompensacion;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface MetodoCompensacionMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "usuario", ignore = true)
    @Mapping(target = "activo", ignore = true)
    MetodoCompensacion toMetodoCompensacion(MetodoCompensacionRequest request);

    MetodoCompensacionRespuesta toMetodoCompensacionRespuesta(MetodoCompensacion metodo);

    List<MetodoCompensacionRespuesta> toListaMetodoCompensacionRespuesta(List<MetodoCompensacion> metodos);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "usuario", ignore = true)
    @Mapping(target = "activo", ignore = true)
    void actualizarMetodoCompensacion(MetodoCompensacionRequest request,
            @MappingTarget MetodoCompensacion metodo);
}
