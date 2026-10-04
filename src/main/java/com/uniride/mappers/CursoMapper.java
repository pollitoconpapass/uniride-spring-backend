package com.uniride.mappers;

import com.uniride.dto.requests.CursoRequest;
import com.uniride.dto.responses.CursoRespuesta;
import com.uniride.entities.Curso;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CursoMapper {

    Curso toCurso(CursoRequest request);

    CursoRespuesta toCursoRespuesta(Curso curso);

    List<CursoRespuesta> toListaCursoRespuesta(List<Curso> cursos);
}
