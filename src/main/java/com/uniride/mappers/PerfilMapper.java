package com.uniride.mappers;

import com.uniride.dto.InfoAdicionalRequest;
import com.uniride.dto.PerfilRequest;
import com.uniride.dto.PerfilRespuesta;
import com.uniride.entities.Perfil;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface PerfilMapper {

    Perfil toPerfil(PerfilRequest request);

    void actualizarPerfil(PerfilRequest request, @MappingTarget Perfil perfil);

    void actualizarInfoAdicional(InfoAdicionalRequest request, @MappingTarget Perfil perfil);

    PerfilRespuesta toPerfilRespuesta(Perfil perfil);

    List<PerfilRespuesta> toListaPerfilRespuesta(List<Perfil> perfiles);
}
