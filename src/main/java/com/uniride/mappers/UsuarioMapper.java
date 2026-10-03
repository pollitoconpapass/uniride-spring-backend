package com.uniride.mappers;

import com.uniride.dto.UsuarioRespuesta;
import com.uniride.entities.Usuario;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UsuarioMapper {

    UsuarioRespuesta toUsuarioRespuesta(Usuario usuario);

    List<UsuarioRespuesta> toListaUsuarioRespuesta(List<Usuario> usuarios);
}
