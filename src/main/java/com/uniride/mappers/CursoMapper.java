package com.uniride.mappers;

import com.uniride.dto.CursoRequest;
import com.uniride.dto.CursoRespuesta;
import com.uniride.entities.Curso;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CursoMapper {

    Curso toCurso(CursoRequest request);

    CursoRespuesta toCursoRespuesta(Curso curso);

    List<CursoRespuesta> toListaCursoRespuesta(List<Curso> cursos);
}
