package com.uniride.mappers;

import com.uniride.dto.requests.InfoAdicionalRequest;
import com.uniride.dto.requests.PerfilRequest;
import com.uniride.dto.responses.PerfilRespuesta;
import com.uniride.entities.Perfil;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PerfilMapper {

    Perfil toPerfil(PerfilRequest request);

    void actualizarPerfil(PerfilRequest request, @MappingTarget Perfil perfil);

    void actualizarInfoAdicional(InfoAdicionalRequest request, @MappingTarget Perfil perfil);

    PerfilRespuesta toPerfilRespuesta(Perfil perfil);

    List<PerfilRespuesta> toListaPerfilRespuesta(List<Perfil> perfiles);
}
