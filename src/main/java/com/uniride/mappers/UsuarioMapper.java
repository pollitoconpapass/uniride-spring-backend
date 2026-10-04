package com.uniride.mappers;

import com.uniride.dto.responses.UsuarioRespuesta;
import com.uniride.entities.Usuario;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UsuarioMapper {

    @Mapping(source = "correoInstitucional", target = "correo")
    @Mapping(source = "rolPrincipal", target = "rol")
    UsuarioRespuesta toUsuarioRespuesta(Usuario usuario);

    List<UsuarioRespuesta> toListaUsuarioRespuesta(List<Usuario> usuarios);
}
